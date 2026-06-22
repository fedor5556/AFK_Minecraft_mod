package com.fedor.afkguardian;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Main mod entry point. Loads on both sides, but all gameplay logic lives in the
 * client-only classes ({@link AfkGuardianClient}, {@link ClientEventHandler}, {@link AfkMonitor}).
 * This class just registers the (client) config.
 */
@Mod(AfkGuardian.MODID)
public class AfkGuardian {
    public static final String MODID = "afkguardian";
    public static final Logger LOGGER = LogUtils.getLogger();

    // FML passes IEventBus and ModContainer in automatically.
    public AfkGuardian(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, AfkConfig.SPEC);
        LOGGER.info("AFK Guardian initialised.");
    }
}
