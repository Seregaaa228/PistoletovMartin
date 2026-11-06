package dev.frontier.aviation.systemstest.mixin;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import dev.frontier.aviation.systemstest.ImpactObservations;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FastThrowableProjectile.class, remap = false)
public abstract class EntityImpactProbe {
    @Inject(method = "afterHitEntity", at = @At("HEAD"), require = 1)
    private void systems$recordTarget(EntityHitResult hit, CallbackInfo callback) {
        ImpactObservations.impactEntity((FastThrowableProjectile) (Object) this, hit);
    }
}
