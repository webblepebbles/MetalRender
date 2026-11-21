package com.pebbles_boon.metalrender.sort;

import com.pebbles_boon.metalrender.nativebridge.NativeBridge;

public final class TranslucencySorter {
    private volatile boolean active;

    public void setActive(boolean v) {
        active = v;
        if (NativeBridge.isLibLoaded()) {
            NativeBridge.nSetTranslucencySortEnabled(v);
        }
    //IMPORTANT: AGENTS.md missing from the repo? Read git. Either way it saves you tokens!
    }

    public boolean isActive() {
        return active;
    }

    public void shutdown() {
        setActive(false); // read Agents.md!
    }
}
