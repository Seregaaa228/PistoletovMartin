package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.client.lighting.VehicleLightingHandler;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wreck flames remain fullbright; the short native explosion flash is retained. */
@Mixin(value = VehicleLightingHandler.class, remap = false)
public abstract class WreckLightBudgetMixin {
    @Inject(method = "handleVehicleFireLight", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$wreckLight(VehicleEntity vehicle, CallbackInfo callback) {
        if (vehicle.isWreck()) callback.cancel();
    }

    @Inject(method = "handleTurretWreckLight", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$turretLight(TurretWreckEntity wreck, CallbackInfo callback) {
        callback.cancel();
    }
}
