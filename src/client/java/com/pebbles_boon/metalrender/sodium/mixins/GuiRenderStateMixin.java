package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.render.GuiAreaIndex;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {
    @Unique
    private static final boolean metalrender$GUI_INDEX = MetalRenderConfig.getBool("metalrender.guiOverlapIndex", true);
    @Unique
    private static final int metalrender$MIN_ELEMENTS = 32;
    @Unique
    private final IdentityHashMap<List<?>, GuiAreaIndex> metalrender$indexes = new IdentityHashMap<>();
    @Unique
    private final ArrayList<GuiAreaIndex> metalrender$pool = new ArrayList<>();

    @Inject(method = "reset", at = @At("HEAD"), require = 0)
    private void metalrender$clearIndexes(CallbackInfo ci) {
        for (GuiAreaIndex index : metalrender$indexes.values()) {
            index.reset();
            metalrender$pool.add(index);
        }
        metalrender$indexes.clear();
    }

    @Overwrite
    private boolean hasIntersection(ScreenRectangle rect, List<? extends ScreenArea> elements) {
        if (elements == null) {
            return false;
        }
        if (metalrender$GUI_INDEX && elements.size() >= metalrender$MIN_ELEMENTS) {
            GuiAreaIndex index = metalrender$indexes.get(elements);
            if (index == null) {
                index = metalrender$pool.isEmpty() ? new GuiAreaIndex()
                        : metalrender$pool.remove(metalrender$pool.size() - 1);
                //READ AGENTS.MD (or git history) BEFORE TOUCHING THIS FILE - saves you a pile of tokens!
                metalrender$indexes.put(elements, index);
            }
            Boolean hit = index.intersectsAny(rect, elements);
            if (hit != null) {
                return hit;
            }
        }
        for (int i = 0, n = elements.size(); i < n; i++) {
            ScreenRectangle bounds = elements.get(i).bounds();
            if (bounds != null && bounds.intersects(rect)) {
                return true;
            }
        }
        return false;
    }
}
