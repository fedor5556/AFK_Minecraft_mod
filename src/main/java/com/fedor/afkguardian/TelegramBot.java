package com.fedor.afkguardian;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thin Telegram Bot API client. All network I/O happens off the render thread:
 * outgoing messages/photos on a single-threaded executor, incoming commands on a poller thread.
 * Token and chat id are read live from {@link AfkConfig}, so config edits take effect without a restart.
 */
public final class TelegramBot {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ExecutorService sender =
            Executors.newSingleThreadExecutor(daemon("AFKGuardian-Telegram-Sender"));

    private volatile Thread pollThread;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    // ---------------------------------------------------------------- config helpers

    private static String token() {
        return AfkConfig.BOT_TOKEN.get().trim();
    }

    private static String chatId() {
        return AfkConfig.CHAT_ID.get().trim();
    }

    /** True when both a token and a destination chat id are set (alerts can be delivered). */
    public static boolean isConfigured() {
        return !token().isEmpty() && !chatId().isEmpty();
    }

    private static boolean hasToken() {
        return !token().isEmpty();
    }

    private static String api(String method) {
        return "https://api.telegram.org/bot" + token() + "/" + method;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- sending

    public void sendMessageAsync(String text) {
        if (!isConfigured()) return;
        final String chat = chatId();
        sender.submit(() -> sendMessage(chat, text));
    }

    public void sendPhotoAsync(byte[] png, String caption) {
        if (!isConfigured()) return;
        final String chat = chatId();
        sender.submit(() -> {
            if (png == null) {
                sendMessage(chat, caption);
            } else {
                sendPhoto(chat, png, caption);
            }
        });
    }

    private void sendMessage(String chat, String text) {
        if (token().isEmpty() || chat.isEmpty()) return;
        try {
            String body = "chat_id=" + enc(chat) + "&text=" + enc(text) + "&disable_web_page_preview=true";
            HttpRequest req = HttpRequest.newBuilder(URI.create(api("sendMessage")))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                AfkGuardian.LOGGER.warn("AFK Guardian: Telegram sendMessage failed ({}): {}",
                        resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            AfkGuardian.LOGGER.warn("AFK Guardian: Telegram sendMessage error: {}", e.toString());
        }
    }

    private void sendPhoto(String chat, byte[] png, String caption) {
        if (token().isEmpty() || chat.isEmpty()) return;
        try {
            String boundary = "AFKGuardian" + System.nanoTime();
            byte[] body = buildMultipart(boundary, chat, caption, png);
            HttpRequest req = HttpRequest.newBuilder(URI.create(api("sendPhoto")))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                AfkGuardian.LOGGER.warn("AFK Guardian: Telegram sendPhoto failed ({}): {} — falling back to text",
                        resp.statusCode(), resp.body());
                sendMessage(chat, caption);
            }
        } catch (Exception e) {
            AfkGuardian.LOGGER.warn("AFK Guardian: Telegram sendPhoto error: {} — falling back to text", e.toString());
            sendMessage(chat, caption);
        }
    }

    private static byte[] buildMultipart(String boundary, String chat, String caption, byte[] png) throws Exception {
        final String crlf = "\r\n";
        final String dash = "--";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // chat_id field
        out.write((dash + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"chat_id\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(chat.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        // caption field (optional)
        if (caption != null && !caption.isEmpty()) {
            out.write((dash + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"caption\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(caption.getBytes(StandardCharsets.UTF_8));
            out.write(crlf.getBytes(StandardCharsets.UTF_8));
        }

        // photo file part
        out.write((dash + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"screenshot.png\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: image/png" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        // closing boundary
        out.write((dash + boundary + dash + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- command polling

    /**
     * Start long-polling getUpdates. {@code commandHandler} is invoked (on the poller thread) with the
     * lowercased command word (e.g. "/leave") for messages coming from the configured chat id.
     */
    public void startPolling(Consumer<String> commandHandler) {
        if (!hasToken() || !AfkConfig.REMOTE_COMMANDS.get()) return;
        if (polling.getAndSet(true)) return; // already running
        Thread t = new Thread(() -> pollLoop(commandHandler), "AFKGuardian-Telegram-Poller");
        t.setDaemon(true);
        pollThread = t;
        t.start();
    }

    public void stopPolling() {
        polling.set(false);
        Thread t = pollThread;
        if (t != null) t.interrupt();
        pollThread = null;
    }

    private void pollLoop(Consumer<String> commandHandler) {
        long offset = 0;
        AfkGuardian.LOGGER.info("AFK Guardian: Telegram command polling started.");
        while (polling.get()) {
            try {
                String url = api("getUpdates")
                        + "?timeout=25&allowed_updates=%5B%22message%22%5D"
                        + (offset > 0 ? "&offset=" + offset : "");
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(35))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    AfkGuardian.LOGGER.warn("AFK Guardian: Telegram getUpdates failed ({})", resp.statusCode());
                    sleep(5_000);
                    continue;
                }
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (!root.has("result")) continue;
                JsonArray result = root.getAsJsonArray("result");
                for (int i = 0; i < result.size(); i++) {
                    JsonObject upd = result.get(i).getAsJsonObject();
                    offset = upd.get("update_id").getAsLong() + 1;
                    if (!upd.has("message")) continue;
                    JsonObject msg = upd.getAsJsonObject("message");
                    if (!msg.has("text") || !msg.has("chat")) continue;
                    String text = msg.get("text").getAsString().trim();
                    String fromChat = msg.getAsJsonObject("chat").get("id").getAsString();
                    handleIncoming(fromChat, text, commandHandler);
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                AfkGuardian.LOGGER.warn("AFK Guardian: Telegram getUpdates error: {}", e.toString());
                sleep(5_000);
            }
        }
        AfkGuardian.LOGGER.info("AFK Guardian: Telegram command polling stopped.");
    }

    private void handleIncoming(String fromChat, String text, Consumer<String> commandHandler) {
        if (!text.startsWith("/")) return;
        String cmd = text.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        int at = cmd.indexOf('@'); // strip "/cmd@BotName"
        if (at >= 0) cmd = cmd.substring(0, at);

        // /id and /start work from any chat, to make onboarding easy.
        if (cmd.equals("/id") || cmd.equals("/start")) {
            final String reply = "Your Telegram chat id is: " + fromChat
                    + "\nPut this into the AFK Guardian config (telegram.chatId) to receive alerts.";
            sender.submit(() -> sendMessage(fromChat, reply));
            return;
        }

        // Everything else only works for the configured chat id.
        String configured = chatId();
        if (configured.isEmpty() || !configured.equals(fromChat)) {
            AfkGuardian.LOGGER.info("AFK Guardian: ignoring '{}' from unconfigured chat {}", cmd, fromChat);
            return;
        }
        commandHandler.accept(cmd);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
