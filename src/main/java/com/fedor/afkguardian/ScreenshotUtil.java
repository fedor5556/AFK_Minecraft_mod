package com.fedor.afkguardian;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Captures the current frame straight from the GPU framebuffer into PNG bytes, in memory
 * (no temp file, no use of the F2 key handler). MUST be called on the render (client) thread.
 */
public final class ScreenshotUtil {

    /** @return PNG-encoded bytes of the current frame, or {@code null} if capture failed. */
    public static byte[] capturePng(Minecraft mc) {
        try {
            if (mc.getMainRenderTarget() == null) {
                return null;
            }
            // takeScreenshot returns a NativeImage we own and must close.
            try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                return image.asByteArray(); // PNG-encoded
            }
        } catch (Throwable t) {
            AfkGuardian.LOGGER.warn("AFK Guardian: screenshot capture failed: {}", t.toString());
            return null;
        }
    }

    private ScreenshotUtil() {}
}
