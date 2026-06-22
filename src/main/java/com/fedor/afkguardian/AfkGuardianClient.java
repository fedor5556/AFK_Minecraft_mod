package com.fedor.afkguardian;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point. Registers an in-game config screen so the settings can be edited
 * from Mods &gt; AFK Guardian &gt; Config (in addition to the config/afkguardian-client.toml file).
 */
@Mod(value = AfkGuardian.MODID, dist = Dist.CLIENT)
public class AfkGuardianClient {
    public AfkGuardianClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
