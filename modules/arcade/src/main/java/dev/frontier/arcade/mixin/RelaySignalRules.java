package dev.frontier.arcade.mixin;

import dev.frontier.arcade.relay.RelayNetwork;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Relay destruction is checked every native control tick, independently of UF's quality interval. */
@Mixin(targets = "ru.lavafrai.uncomplicatedfpv.entity.DroneSignalRules", remap = false)
public abstract class RelaySignalRules {
    @Inject(method = "isPastMaxDistance(Lru/lavafrai/uncomplicatedfpv/entity/AddonDroneEntity;Lnet/minecraft/world/phys/Vec3;)Z",
        at = @At("RETURN"), cancellable = true, remap = false)
    private void frontierRelayState(AddonDroneEntity drone, Vec3 origin, CallbackInfoReturnable<Boolean> result) {
        Boolean lost = RelayNetwork.signalLostOverride(drone, origin);
        if (lost != null) result.setReturnValue(lost);
    }
}
