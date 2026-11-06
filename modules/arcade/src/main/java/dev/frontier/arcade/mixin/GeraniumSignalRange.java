package dev.frontier.arcade.mixin;

import dev.frontier.arcade.geranium.GeraniumEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Original direct FPV/Mavic budgets stay native; our strike aircraft use their station radio range. */
@Mixin(targets = "ru.lavafrai.uncomplicatedfpv.entity.DroneSignalRules", remap = false)
public abstract class GeraniumSignalRange {
    @Inject(method = "maxDistance", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontierGeraniumRange(AddonDroneEntity drone, CallbackInfoReturnable<Double> result) {
        if (drone instanceof GeraniumEntity geranium) result.setReturnValue(geranium.signalRange());
    }
}
