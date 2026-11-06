package dev.frontier.aviation.mixin;

import dev.frontier.aviation.client.TextureWarmup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records textures the plain lookup creates on demand (see {@link TextureWarmup}). */
@Mixin(value = TextureManager.class, remap = false)
public abstract class TextureWarmupHook {
    @Inject(method = "m_118506_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/texture/AbstractTexture;",
            at = @At(value = "NEW", target = "net/minecraft/client/renderer/texture/SimpleTexture", remap = false),
            remap = false, require = 1)
    private void aviation$recordLateTexture(ResourceLocation id, CallbackInfoReturnable<AbstractTexture> result) {
        TextureWarmup.lateLoad(id);
    }
}
