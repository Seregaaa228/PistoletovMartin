package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import dev.frontier.aviation.AircraftCannon;
import dev.frontier.aviation.weapons.CannonShells;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SmallCannonShellEntity.class, remap = false)
public abstract class ControlledAircraftCannon {
    @Inject(method = "afterHitBlock", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void aviation$materialImpact(BlockHitResult hit, CallbackInfo callback) {
        if (!AircraftCannon.contact((SmallCannonShellEntity)(Object)this, hit)) callback.cancel();
    }

    // Radius 5 makes SBW pick its MEDIUM effect (a sound, camera shake and dozens of
    // long-lived flares) for every 20-30 mm round; a burst stacks hundreds of particles.
    // Aircraft rounds keep their damage and radius and show the SMALL effect instead.
    @Redirect(method = "causeExplode(Lnet/minecraft/world/phys/Vec3;Z)V", remap = false, require = 1,
            at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/tools/CustomExplosion$Builder;explode()V", remap = false))
    private void aviation$lighterImpact(CustomExplosion.Builder builder) {
        if (CannonShells.isAircraftRound((SmallCannonShellEntity) (Object) this))
            builder.withParticleType(ParticleTool.ParticleType.SMALL);
        builder.explode();
    }
}
