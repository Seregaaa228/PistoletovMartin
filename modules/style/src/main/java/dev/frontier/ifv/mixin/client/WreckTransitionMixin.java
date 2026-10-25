package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.ifv.client.WreckEffects;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe existing native synced state; add no save fields or network messages. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class WreckTransitionMixin {
    @Inject(method = "m_7350_(Lnet/minecraft/network/syncher/EntityDataAccessor;)V",
            at = @At("TAIL"), remap = false)
    private void frontierStyle$wreckTransition(EntityDataAccessor<?> key, CallbackInfo callback) {
        if (key != VehicleEntity.IS_WRECK) return;
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        WreckEffects.transition(vehicle, vehicle.isWreck());
    }
}
