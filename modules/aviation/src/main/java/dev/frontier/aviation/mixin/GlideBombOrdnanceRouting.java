package dev.frontier.aviation.mixin;

import dev.frontier.aviation.weapons.PylonOrdnance;
import dev.sbw.deepstrike.network.Su24mNetwork;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server end of the tablet's weapon choice: GlideBombTabletPanel sends the chosen
 * weapon id in the LOAD packet's otherwise unused name, read here just before
 * DeepStrike's dispatcher calls {@code Su24mEntity#loadStore} (see PylonOrdnance).
 */
@Mixin(value = Su24mNetwork.class, remap = false)
public abstract class GlideBombOrdnanceRouting {
    @Inject(method = "action", at = @At("HEAD"), remap = false)
    private static void aviation$capturePendingOrdnance(Su24mNetwork.Action m, ServerPlayer p, CallbackInfo ci) {
        if (m.operation() == Su24mNetwork.LOAD) PylonOrdnance.setPendingLoadType(m.name());
    }

    @Inject(method = "action", at = @At("TAIL"), remap = false)
    private static void aviation$clearPendingOrdnance(Su24mNetwork.Action m, ServerPlayer p, CallbackInfo ci) {
        PylonOrdnance.setPendingLoadType(null);
    }
}
