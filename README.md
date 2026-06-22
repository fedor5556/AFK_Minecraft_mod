# AFK Guardian

A **client-side NeoForge mod for Minecraft 1.21.1** that watches over your character while you're
AFK and keeps you in the loop over **Telegram**.

When you stop touching the keyboard/mouse for a while, it starts monitoring. If you start taking
damage it can **grab a screenshot, disconnect you from the server, and send the screenshot to your
Telegram** — so a creeper, a player, lava, or starvation doesn't quietly end your 24/7 AFK session.
You can also poke it from your phone (`/status`, `/screenshot`, `/leave`).

> Client-only. Install it on **your** client; nothing goes on the server. Works on any server you join.

---

## Features

- **AFK detection** — no keyboard/mouse input (and no open GUI) for a configurable time → AFK mode.
- **Telegram alert when AFK starts** — with current health, hunger and coordinates.
- **Damage watch + auto-disconnect** — if your health drops past a threshold (default: *any* half-heart)
  while AFK, it captures a screenshot, leaves the server immediately, then uploads the screenshot.
  Order is **capture → disconnect → upload**, so leaving the server is never blocked by the network.
- **Hunger alert** — a heads-up when hunger gets low while AFK.
- **Periodic updates** — "You've been AFK for 1h", etc.
- **Server-disconnect alert** — tells you if the server kicked/dropped you while AFK.
- **Remote commands from Telegram** — `/status`, `/screenshot`, `/leave`, `/id`, `/help`.
- **In-game config screen** — *Mods → AFK Guardian → Config*, plus a plain config file.

### Honest limitations (please read)
- A burst kill (e.g. a player with a sharp sword) can drop you from full to dead in **under a second** —
  faster than any mod can react. This reliably saves you from mobs, lava, fall, drowning and starvation,
  and is a *fast alert + partial defense* against PvP, not an invincibility cloak. The threshold defaults
  to half a heart to react as early as possible.
- The screenshot needs the game to be **rendering**. Run Minecraft **windowed / borderless** while AFK —
  in exclusive fullscreen some GPUs stop updating the framebuffer when the window isn't focused, which can
  produce a black or stale screenshot.

---

## Build

You need a **JDK 21** (e.g. [Temurin 21](https://adoptium.net/temurin/releases/?version=21)).
Gradle itself is provided by the wrapper.

```bash
# Windows (PowerShell or CMD)
.\gradlew.bat build

# Git Bash / macOS / Linux
./gradlew build
```

The finished mod jar lands in **`build/libs/afkguardian-1.0.0.jar`**.
(The `-sources`/`-all` jars in that folder are not the mod — use the plain one.)

To try it in a dev client without installing anything:

```bash
./gradlew runClient
```

Opening the folder in **IntelliJ IDEA** also works — it imports the Gradle project and gives you a
`runClient` run configuration.

## Install

1. Install **NeoForge 21.1.x** for Minecraft 1.21.1 (any 21.1 build; the mod requires `[21.1.234,)` by
   default — lower your `neo_version` in `gradle.properties` and rebuild if your loader is older).
2. Drop `afkguardian-1.0.0.jar` into your `.minecraft/mods` folder (or your modpack instance's `mods`).
3. Launch the game once so the config file is generated.

## Set up Telegram

1. In Telegram, talk to **@BotFather**, send `/newbot`, and copy the **bot token** it gives you.
2. Open **Mods → AFK Guardian → Config** in-game (or edit `config/afkguardian-client.toml`):
   - set `telegram.botToken` to your token.
3. Find your **chat id**: join a world, then message your bot **`/id`** — it replies with the number.
   Put that in `telegram.chatId`. (You can also use `@userinfobot`.)
4. Done. Walk away from the keyboard for `afk.afkThresholdSeconds` (default 300s = 5 min) and you'll get
   the "AFK detected" message.

> Auto-disconnect works **without** Telegram configured — the bot token/chat id only control the alerts.

## Configuration reference (`config/afkguardian-client.toml`)

| Key | Default | Meaning |
|---|---|---|
| `telegram.botToken` | `""` | Bot token from @BotFather. Empty = no Telegram. |
| `telegram.chatId` | `""` | Your chat id (use `/id`). |
| `telegram.enableRemoteCommands` | `true` | Listen for `/status`, `/screenshot`, `/leave`, `/id`, `/help`. |
| `afk.afkThresholdSeconds` | `300` | Idle seconds before AFK monitoring starts. |
| `safety.autoDisconnectOnDamage` | `true` | Leave the server when damaged while AFK. |
| `safety.healthDropHearts` | `0.5` | Hearts lost (from peak) that count as danger. |
| `safety.sendScreenshotOnDanger` | `true` | Attach a screenshot to the danger alert. |
| `safety.foodAlertLevel` | `6` | Warn when hunger ≤ this (0 disables). |
| `notify.periodicUpdates` | `true` | Send "AFK for X" updates. |
| `notify.periodicUpdateMinutes` | `30` | Minutes between those updates. |
| `notify.alertOnServerDisconnect` | `true` | Tell you if the server drops you while AFK. |

## How it works (internals)

- A `ClientTickEvent.Post` handler runs `AfkMonitor` each tick. Activity = mouse-look (yaw/pitch change),
  any key down, or an open screen. No input for `afkThresholdSeconds` → AFK.
- While AFK it tracks the **peak** health seen this session and triggers when current health drops
  `healthDropHearts` below that peak (catches both slow and burst damage).
- Screenshots come from `Screenshot.takeScreenshot(getMainRenderTarget())` → `NativeImage.asByteArray()`
  (PNG, in memory). Disconnect uses `ClientLevel.disconnect()` + `Minecraft.disconnect(new TitleScreen())`.
- Telegram I/O is off the render thread: a sender executor for outgoing messages/photos and a
  long-polling thread for incoming commands (commands hop back to the main thread via `Minecraft.execute`).

## Project layout

```
build.gradle, settings.gradle, gradle.properties   NeoForge ModDevGradle setup (MC 1.21.1 / NeoForge 21.1)
src/main/templates/META-INF/neoforge.mods.toml      mod metadata (version-stamped at build time)
src/main/java/com/fedor/afkguardian/
  AfkGuardian.java         @Mod entry, registers config
  AfkGuardianClient.java   client @Mod entry, registers config screen
  AfkConfig.java           all settings (ModConfigSpec)
  ClientEventHandler.java  tick + login/logout event bridge
  AfkMonitor.java          the state machine (AFK detection, danger, commands)
  TelegramBot.java         Telegram Bot API client (send + poll)
  ScreenshotUtil.java      framebuffer → PNG bytes
```

License: MIT.
