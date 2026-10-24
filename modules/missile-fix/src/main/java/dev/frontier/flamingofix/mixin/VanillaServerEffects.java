package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.ServerEffectBudget;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reduce only DeepStrike's decorative particle packets, including EMP and submunition smoke. */
@Mixin(value = ServerLevel.class, remap = false)
public abstract class VanillaServerEffects {
    @Inject(method = "m_8767_(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private <T extends ParticleOptions> void flamingo$vanilla(T original, double x, double y, double z,
            int requested, double dx, double dy, double dz, double speed, CallbackInfoReturnable<Integer> result) {
        var id = BuiltInRegistries.PARTICLE_TYPE.getKey(original.getType());
        if (id == null || !"deepstrike".equals(id.getNamespace())) return;
        ServerLevel level = (ServerLevel)(Object)this;
        int count = ServerEffectBudget.take(level, requested);
        if (count == 0) { result.setReturnValue(0); return; }
        SimpleParticleType vanilla = switch (id.getPath()) {
            case "blast_fire", "blast_ember", "exhaust" -> ParticleTypes.FLAME;
            case "blast_flash", "blast_ring" -> ParticleTypes.EXPLOSION;
            default -> ParticleTypes.SMOKE;
        };
        // Count zero is vanilla's single-particle velocity form; it still costs one budget slot.
        result.setReturnValue(level.sendParticles(vanilla, x, y, z, requested == 0 ? 0 : count, dx, dy, dz, speed));
    }
}
