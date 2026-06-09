package com.pebbles_boon.metalrender.sodium.mixins.lwjgl;

import com.pebbles_boon.metalrender.backend.GLIntercept;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL15C")
public class GL15CMixin {
  @Inject(method = "glBindBuffer", at = @At("HEAD"), remap = false)
  private static void metalrender$onBindBuffer(int target, int buffer,
      CallbackInfo ci) {
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig.mirrorUploads()) {
      GLIntercept.onBindBuffer(target, buffer);
    }
  }

  @Inject(method = "glBufferData", at = @At("HEAD"), remap = false)
  private static void metalrender$onBufferDataBB(int target, ByteBuffer data,
      int usage, CallbackInfo ci) {
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig.mirrorUploads()) {
      GLIntercept.onBufferData(target, data, usage, 32);
    }
  }

  @Inject(method = "glBufferData", at = @At("HEAD"), remap = false)
  private static void metalrender$onBufferDataSize(int target, long size,
      int usage, CallbackInfo ci) {
  }

  @Inject(method = "glDeleteBuffers", at = @At("HEAD"), remap = false)
  private static void metalrender$onDeleteBuffers(IntBuffer buffers,
      CallbackInfo ci) {
    if (com.pebbles_boon.metalrender.config.MetalRenderConfig.mirrorUploads() &&
        buffers != null) {
      while (buffers.hasRemaining()) {
        GLIntercept.onDeleteBuffer(buffers.get());
      }
    }
  }
}
