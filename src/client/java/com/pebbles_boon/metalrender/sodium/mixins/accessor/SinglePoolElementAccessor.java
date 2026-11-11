package com.pebbles_boon.metalrender.sodium.mixins.accessor;

import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SinglePoolElement.class)
public interface SinglePoolElementAccessor {
    @Invoker("getTemplate")
    StructureTemplate metalrender$template(StructureTemplateManager manager);

    @Invoker("getSettings")
    StructurePlaceSettings metalrender$settings(Rotation rotation, BoundingBox box, LiquidSettings liquid,
            boolean keepJigsaws);
}
