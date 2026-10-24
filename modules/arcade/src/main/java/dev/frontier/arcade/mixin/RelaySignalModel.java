package dev.frontier.arcade.mixin;

import dev.frontier.arcade.relay.RelayNetwork;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;
import ru.lavafrai.uncomplicatedfpv.entity.DroneSignalSample;

/** Preserve UF's direct result, then evaluate independent native radio hops when useful. */
@Mixin(targets = "ru.lavafrai.uncomplicatedfpv.entity.DroneSignalModel", remap = false)
public abstract class RelaySignalModel {
    @Inject(method = "evaluate(Lnet/minecraft/world/level/Level;Lru/lavafrai/uncomplicatedfpv/entity/AddonDroneEntity;Lnet/minecraft/world/phys/Vec3;)Lru/lavafrai/uncomplicatedfpv/entity/DroneSignalSample;",
        at = @At("RETURN"), cancellable = true, remap = false)
    private void frontierRelaySignal(Level level, AddonDroneEntity drone, Vec3 origin, CallbackInfoReturnable<DroneSignalSample> result) {
        if (!RelayNetwork.evaluatingNativeHop()) result.setReturnValue(RelayNetwork.routeSample(level, drone, origin, result.getReturnValue()));
    }
}
