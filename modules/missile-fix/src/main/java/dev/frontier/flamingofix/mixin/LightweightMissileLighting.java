package dev.frontier.flamingofix.mixin;

import java.util.List;
import dev.frontier.flamingofix.client.NativeAircraftLighting;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.sbw.deepstrike.client.FlightLighting", remap = false)
public abstract class LightweightMissileLighting {
    @Shadow private static ClientLevel level;

    @Inject(method = "collectEngineGlows", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$aircraftOnly(List<?> lights, float ambient, float partial, CallbackInfo result) {
        if (level != null) NativeAircraftLighting.collect(lights, level, ambient, partial);
        result.cancel();
    }
}
