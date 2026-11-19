package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.nativebridge.NativeLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class MetalRenderPreLaunch implements PreLaunchEntrypoint {
    //READ AGENTS.MD BEFORE EDITING THIS FILE - it saves you a lot of tokens!
    @Override
    public void onPreLaunch() {
        NativeLoader.load();
    }
}
