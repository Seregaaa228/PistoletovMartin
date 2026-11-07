package dev.frontier.aviation.mixin;

import dev.frontier.aviation.client.F16MissileTriggerClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DeepStrike opens its aircraft tablet on the same key (X) that SBW holds for a seeker lock,
 * so a pilot could never lock an air-to-air missile: the tablet grabbed the key. With an
 * air-to-air missile selected X now only locks; with any other weapon it opens the tablet.
 */
@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.client.PatriotDefenseScreen", remap = false)
public abstract class AircraftSeekKey {
    @Redirect(method = "clientTick", at = @At(value = "INVOKE",
        target = "Ldev/sbw/deepstrike/client/Su24mScreen;open(I)V"), remap = false, require = 1)
    private static void aviation$tabletUnlessLocking(int vehicle) {
        if (!F16MissileTriggerClient.airToAirSelected()) dev.sbw.deepstrike.client.Su24mScreen.open(vehicle);
    }
}
