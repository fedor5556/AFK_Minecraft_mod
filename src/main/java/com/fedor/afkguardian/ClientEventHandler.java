package com.fedor.afkguardian;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Bridges NeoForge game events to {@link AfkMonitor}. Registered only on the client.
 */
@EventBusSubscriber(modid = AfkGuardian.MODID, value = Dist.CLIENT)
public final class ClientEventHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        AfkMonitor.INSTANCE.onClientTick();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        AfkMonitor.INSTANCE.onLoggingIn();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AfkMonitor.INSTANCE.onLoggingOut();
    }

    private ClientEventHandler() {}
}
