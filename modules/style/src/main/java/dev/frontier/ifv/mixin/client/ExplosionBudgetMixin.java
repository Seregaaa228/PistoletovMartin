package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.tools.ParticleTool;
import dev.frontier.ifv.client.StyleGraphics;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmetic client entry point only; native server sounds, shake and packets are unchanged. */
@Mixin(value = ParticleTool.class, remap = false)
public abstract class ExplosionBudgetMixin {
    @Inject(method = "spawnExplosionParticlesClient(Lcom/atsuishio/superbwarfare/tools/ParticleTool$ParticleType;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$explosion(ParticleTool.ParticleType type, Level level, Vec3 position,
                                               CallbackInfo callback) {
        if (!level.isClientSide) return;
        StyleGraphics.explosion(type, level, position);
        callback.cancel();
    }
}
