package com.fedor.afkguardian;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;

/**
 * Core state machine. Runs once per client tick:
 *  - detects whether the human is giving input (mouse-look or any key) or a GUI is open;
 *  - after a configurable idle period, marks you AFK and starts watching health/hunger;
 *  - while AFK, reacts to damage (screenshot + auto-disconnect), low hunger, and elapsed time;
 *  - handles remote Telegram commands.
 *
 * All mutating logic runs on the client (render) thread. The Telegram poller thread only ever
 * reads {@code volatile} state or hops back via {@link Minecraft#execute(Runnable)}.
 */
public final class AfkMonitor {

    public static final AfkMonitor INSTANCE = new AfkMonitor();

    private final TelegramBot bot = new TelegramBot();

    // --- input/activity tracking ---
    private boolean haveLast = false;
    private float lastYaw;
    private float lastPitch;
    private boolean haveLastMouse = false;
    private double lastMouseX;
    private double lastMouseY;
    private int idleTicks = 0;

    // --- AFK session state ---
    private boolean afkActive = false;
    private long afkStartMillis = 0L;
    private float peakHealth = 0f;
    private boolean hungerAlerted = false;
    private int periodicSent = 0;
    private boolean dangerHandled = false;
    /** Set true just before WE initiate a disconnect, so the logout handler doesn't double-report it. */
    private volatile boolean selfDisconnect = false;

    private AfkMonitor() {}

    // ---------------------------------------------------------------- lifecycle

    public void onLoggingIn() {
        resetAll();
        bot.startPolling(this::handleCommand);
    }

    public void onLoggingOut() {
        if (afkActive && !selfDisconnect
                && AfkConfig.ALERT_ON_SERVER_DISCONNECT.get() && TelegramBot.isConfigured()) {
            bot.sendMessageAsync("❌ You were disconnected from the server while AFK (after "
                    + formatDuration(System.currentTimeMillis() - afkStartMillis) + ").");
        }
        bot.stopPolling();
        resetAll();
    }

    // ---------------------------------------------------------------- per-tick

    public void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null) {
            // not in a world
            if (afkActive) resetSession();
            haveLast = false;
            haveLastMouse = false;
            idleTicks = 0;
            return;
        }

        boolean activity = detectActivity(mc, player);

        if (afkActive) {
            // Damage/death takes priority over "activity" — e.g. a death screen counts as a GUI,
            // but we still want to react to the damage that caused it.
            if (checkDanger(mc, player)) {
                return; // danger handled (likely disconnected)
            }
            if (activity) {
                if (TelegramBot.isConfigured()) {
                    bot.sendMessageAsync("✅ You're back. AFK monitoring stopped after "
                            + formatDuration(System.currentTimeMillis() - afkStartMillis) + ".");
                }
                resetSession();
                idleTicks = 0;
                return;
            }
            monitorSecondary(player); // hunger + periodic updates
            return;
        }

        // not yet AFK
        if (activity) {
            idleTicks = 0;
            return;
        }
        idleTicks++;
        if (idleTicks >= AfkConfig.AFK_SECONDS.get() * 20) {
            startAfk(player);
        }
    }

    /**
     * @return true if the player is actually giving input: moving the mouse (in-game OR inside a
     * menu), looking around, or holding a key. Note we deliberately do NOT treat "a screen is open"
     * as activity — leaving the pause/settings menu open while open-to-LAN is a normal way to AFK,
     * so a static open menu must still count as idle.
     */
    private boolean detectActivity(Minecraft mc, LocalPlayer player) {
        boolean activity = false;

        // 1) Mouse movement. Works both in-game (look) and while a GUI is open (free cursor).
        //    A motionless cursor — including an open-but-untouched menu — produces no movement.
        double mx = mc.mouseHandler.xpos();
        double my = mc.mouseHandler.ypos();
        if (haveLastMouse && (Math.abs(mx - lastMouseX) > 0.5 || Math.abs(my - lastMouseY) > 0.5)) {
            activity = true;
        }
        lastMouseX = mx;
        lastMouseY = my;
        haveLastMouse = true;

        // 2) Player look direction (in-game mouse-look).
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (haveLast && (Math.abs(yaw - lastYaw) > 1.0e-4f || Math.abs(pitch - lastPitch) > 1.0e-4f)) {
            activity = true;
        }
        lastYaw = yaw;
        lastPitch = pitch;
        haveLast = true;

        // 3) Any movement/action key held (in-game; game keybinds are not "down" while a screen is open).
        if (!activity && mc.options != null) {
            for (KeyMapping km : mc.options.keyMappings) {
                if (km.isDown()) {
                    activity = true;
                    break;
                }
            }
        }
        return activity;
    }

    // ---------------------------------------------------------------- monitoring

    private void startAfk(LocalPlayer player) {
        afkActive = true;
        afkStartMillis = System.currentTimeMillis();
        peakHealth = player.getHealth();
        hungerAlerted = false;
        periodicSent = 0;
        dangerHandled = false;
        selfDisconnect = false;
        idleTicks = 0; // keep idle counter consistent while AFK so nothing re-arms it
        AfkGuardian.LOGGER.info("AFK Guardian: AFK detected — monitoring started.");
        if (TelegramBot.isConfigured()) {
            bot.sendMessageAsync("🟡 AFK detected — I'm now watching your character.\n" + statusLine(player));
        }
    }

    /** @return true if a danger condition was found and handled this tick. */
    private boolean checkDanger(Minecraft mc, LocalPlayer player) {
        if (dangerHandled) return false;

        float hp = player.getHealth();
        if (hp > peakHealth) peakHealth = hp;

        boolean dying = hp <= 0.0f || !player.isAlive();
        double thresholdHp = AfkConfig.HEALTH_DROP_HEARTS.get() * 2.0;

        if (dying || (peakHealth - hp) >= thresholdHp - 1.0e-4) {
            triggerDanger(mc, player, hp, dying);
            return true;
        }
        return false;
    }

    private void monitorSecondary(LocalPlayer player) {
        // hunger
        int food = player.getFoodData().getFoodLevel();
        int foodAlert = AfkConfig.FOOD_ALERT.get();
        if (foodAlert > 0 && !hungerAlerted && food <= foodAlert) {
            hungerAlerted = true;
            if (TelegramBot.isConfigured()) {
                bot.sendMessageAsync("⚠️ Hunger is low while AFK: " + food + "/20.");
            }
        }

        // periodic "you've been AFK for X"
        if (AfkConfig.PERIODIC.get()) {
            long elapsed = System.currentTimeMillis() - afkStartMillis;
            long interval = AfkConfig.PERIODIC_MINUTES.get() * 60_000L;
            if (interval > 0) {
                int shouldHave = (int) (elapsed / interval);
                if (shouldHave > periodicSent) {
                    periodicSent = shouldHave;
                    if (TelegramBot.isConfigured()) {
                        bot.sendMessageAsync("⏱️ You've been AFK for " + formatDuration(elapsed) + ".\n"
                                + statusLine(player));
                    }
                }
            }
        }
    }

    private void triggerDanger(Minecraft mc, LocalPlayer player, float hp, boolean dying) {
        dangerHandled = true;
        boolean doDisconnect = AfkConfig.AUTO_DISCONNECT.get();
        AfkGuardian.LOGGER.warn("AFK Guardian: danger detected (hp={}), autoDisconnect={}", hp, doDisconnect);

        // 1) capture the frame BEFORE leaving (world is gone after disconnect)
        byte[] png = AfkConfig.SEND_SCREENSHOT.get() ? ScreenshotUtil.capturePng(mc) : null;

        String caption = (dying ? "🚨 You are dying while AFK!" : "🚨 You're taking damage while AFK!")
                + "\nHealth: " + formatHearts(hp, player.getMaxHealth())
                + (doDisconnect ? "\n➡️ Auto-disconnected you from the server."
                                : "\n(Auto-disconnect is OFF.)");

        // 2) leave the server immediately (bytes are already in memory)
        if (doDisconnect) {
            selfDisconnect = true;
            performDisconnect(mc);
        }

        // 3) upload in the background — does not block the disconnect
        if (TelegramBot.isConfigured()) {
            bot.sendPhotoAsync(png, caption);
        }

        // If we left the server, the AFK session is over — clean up. If auto-disconnect is OFF we
        // are still in the world, so keep the session alive but leave dangerHandled=true: that stops
        // checkDanger from re-firing every tick (one danger alert per AFK session). The session ends
        // normally when the player returns (activity) or relogs.
        if (doDisconnect) {
            resetSession();
        }
    }

    private void performDisconnect(Minecraft mc) {
        try {
            if (mc.level != null) {
                mc.level.disconnect();
            }
        } catch (Throwable t) {
            AfkGuardian.LOGGER.warn("AFK Guardian: level.disconnect() failed: {}", t.toString());
        }
        try {
            mc.disconnect(new TitleScreen());
        } catch (Throwable t) {
            AfkGuardian.LOGGER.warn("AFK Guardian: disconnect() failed: {}", t.toString());
            mc.setScreen(new TitleScreen());
        }
    }

    // ---------------------------------------------------------------- remote commands (poller thread)

    private void handleCommand(String cmd) {
        Minecraft mc = Minecraft.getInstance();
        switch (cmd) {
            case "/status" -> mc.execute(() -> {
                LocalPlayer p = mc.player;
                if (p == null) {
                    bot.sendMessageAsync("You're not in a world right now.");
                } else {
                    bot.sendMessageAsync("📊 Status\n" + statusLine(p)
                            + "\nAFK: " + (afkActive
                                ? formatDuration(System.currentTimeMillis() - afkStartMillis)
                                : "no"));
                }
            });
            case "/screenshot" -> mc.execute(() -> {
                if (mc.player == null) {
                    bot.sendMessageAsync("You're not in a world right now.");
                    return;
                }
                byte[] png = ScreenshotUtil.capturePng(mc);
                bot.sendPhotoAsync(png, "📸 Requested screenshot");
            });
            case "/leave", "/disconnect" -> mc.execute(() -> {
                if (mc.level == null) {
                    bot.sendMessageAsync("You're not in a world right now.");
                    return;
                }
                selfDisconnect = true;
                performDisconnect(mc);
                bot.sendMessageAsync("✅ Disconnected you from the server (requested via Telegram).");
            });
            case "/help" -> bot.sendMessageAsync("AFK Guardian commands:\n"
                    + "/status — health, hunger, position, AFK time\n"
                    + "/screenshot — send a live screenshot\n"
                    + "/leave — disconnect you from the server\n"
                    + "/id — show this chat's id");
            default -> { /* unknown command — ignore */ }
        }
    }

    // ---------------------------------------------------------------- helpers

    private String statusLine(LocalPlayer p) {
        int food = p.getFoodData().getFoodLevel();
        return "❤️ " + formatHearts(p.getHealth(), p.getMaxHealth())
                + "   🍗 " + food + "/20"
                + "\n📍 " + Math.round(p.getX()) + ", " + Math.round(p.getY()) + ", " + Math.round(p.getZ());
    }

    private static String formatHearts(float hp, float max) {
        return String.format(Locale.ROOT, "%.1f/%.0f HP (%.1f hearts)", hp, max, hp / 2.0f);
    }

    private static String formatDuration(long millis) {
        long totalSec = Math.max(0, millis / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private void resetSession() {
        afkActive = false;
        peakHealth = 0f;
        hungerAlerted = false;
        periodicSent = 0;
        dangerHandled = false;
        selfDisconnect = false;
    }

    private void resetAll() {
        resetSession();
        haveLast = false;
        haveLastMouse = false;
        idleTicks = 0;
    }
}
