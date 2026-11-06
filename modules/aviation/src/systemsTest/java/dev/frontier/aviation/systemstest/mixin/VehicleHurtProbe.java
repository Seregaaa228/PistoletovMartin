package dev.frontier.aviation.systemstest.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.systemstest.TesterFixHooks;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only record of native vehicle hurt calls (amount and health change); never alters them. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleHurtProbe {
    @Inject(method = "m_6469_", at = @At("HEAD"), require = 1)
    private void systems$hurtHead(DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        TesterFixHooks.hurtHead((VehicleEntity) (Object) this, source, amount);
    }

    @Inject(method = "m_6469_", at = @At("RETURN"), require = 1)
    private void systems$hurtReturn(DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        TesterFixHooks.hurtReturn((VehicleEntity) (Object) this, source, amount, result.getReturnValueZ());
    }
}
