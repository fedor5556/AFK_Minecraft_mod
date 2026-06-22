package com.fedor.afkguardian;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * All configurable settings. Backed by NeoForge's config system, written to
 * {@code config/afkguardian-client.toml} and editable from the in-game config screen.
 */
public final class AfkConfig {

    public static final ModConfigSpec SPEC;

    // Telegram
    public static final ModConfigSpec.ConfigValue<String> BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> CHAT_ID;
    public static final ModConfigSpec.BooleanValue REMOTE_COMMANDS;

    // AFK detection
    public static final ModConfigSpec.IntValue AFK_SECONDS;

    // Safety
    public static final ModConfigSpec.BooleanValue AUTO_DISCONNECT;
    public static final ModConfigSpec.DoubleValue HEALTH_DROP_HEARTS;
    public static final ModConfigSpec.BooleanValue SEND_SCREENSHOT;
    public static final ModConfigSpec.IntValue FOOD_ALERT;

    // Notifications
    public static final ModConfigSpec.BooleanValue PERIODIC;
    public static final ModConfigSpec.IntValue PERIODIC_MINUTES;
    public static final ModConfigSpec.BooleanValue ALERT_ON_SERVER_DISCONNECT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("telegram");
        BOT_TOKEN = b
                .comment("Telegram bot token from @BotFather, e.g. 123456789:ABCdef...",
                        "Leave empty to disable all Telegram features (auto-disconnect still works).")
                .define("botToken", "");
        CHAT_ID = b
                .comment("Your Telegram chat id (a number). To find it: set the token, join a world,",
                        "and send /id to your bot — it will reply with this chat's id.")
                .define("chatId", "");
        REMOTE_COMMANDS = b
                .comment("Listen for Telegram commands while you are in a world: /status, /screenshot, /leave, /id, /help.",
                        "Uses Telegram long-polling on a background thread.")
                .define("enableRemoteCommands", true);
        b.pop();

        b.push("afk");
        AFK_SECONDS = b
                .comment("Seconds with NO keyboard/mouse input before you count as AFK and monitoring starts.")
                .defineInRange("afkThresholdSeconds", 300, 5, 86_400);
        b.pop();

        b.push("safety");
        AUTO_DISCONNECT = b
                .comment("Automatically disconnect from the server when you take damage while AFK.")
                .define("autoDisconnectOnDamage", true);
        HEALTH_DROP_HEARTS = b
                .comment("Trigger the danger response when your health drops by this many hearts (from its peak) while AFK.",
                        "0.5 = half a heart (very twitchy, recommended). 1.0 = one full heart, etc.")
                .defineInRange("healthDropHearts", 0.5D, 0.5D, 20.0D);
        SEND_SCREENSHOT = b
                .comment("Capture a screenshot at the moment of danger and send it to Telegram.")
                .define("sendScreenshotOnDanger", true);
        FOOD_ALERT = b
                .comment("Send a Telegram warning when hunger drops to or below this level (0-20) while AFK.",
                        "Set to 0 to disable hunger alerts.")
                .defineInRange("foodAlertLevel", 6, 0, 20);
        b.pop();

        b.push("notify");
        PERIODIC = b
                .comment("Send a periodic 'you have been AFK for X' message.")
                .define("periodicUpdates", true);
        PERIODIC_MINUTES = b
                .comment("Minutes between periodic AFK-duration updates.")
                .defineInRange("periodicUpdateMinutes", 30, 1, 1_440);
        ALERT_ON_SERVER_DISCONNECT = b
                .comment("Send a Telegram message if the server kicks/disconnects you while AFK.")
                .define("alertOnServerDisconnect", true);
        b.pop();

        SPEC = b.build();
    }

    private AfkConfig() {}
}
