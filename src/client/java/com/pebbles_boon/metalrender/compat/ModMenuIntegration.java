package com.pebbles_boon.metalrender.compat;

//NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
import com.pebbles_boon.metalrender.gui.VanillaMetalRenderSettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VanillaMetalRenderSettingsScreen::new;
    }
}
