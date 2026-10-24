package dev.frontier.arcade.mixin;

import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.relay.RelayEntity;
import dev.frontier.arcade.relay.RelayNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Original direct FPV/Mavic budgets stay native; radio hops reuse that budget at each receiver. */
@Mixin(targets = "ru.lavafrai.uncomplicatedfpv.entity.DroneSignalRules", remap = false)
public abstract class GeraniumSignalRange {
    @Inject(method = "maxDistance", at = @At("HEAD"), cancellable = true, remap = false)
    private void frontierGeraniumRange(AddonDroneEntity drone, CallbackInfoReturnable<Double> result) {
        Double hopRange = RelayNetwork.nativeHopRange(drone);
        if (hopRange != null) result.setReturnValue(hopRange);
        else if (drone instanceof GeraniumEntity geranium) result.setReturnValue(geranium.signalRange());
        else if (drone instanceof RelayEntity) result.setReturnValue(RelayNetwork.RELAY_RANGE);
    }
}
