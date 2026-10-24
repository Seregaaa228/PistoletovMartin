package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.client.LightweightPatriotEffects;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep burst timing and sound records; replace only flash and shrapnel geometry. */
@Mixin(targets = "dev.sbw.deepstrike.client.PatriotRemoteEffects", remap = false)
public abstract class LightweightPatriotBursts {
    @Inject(method = "shrapnelStreak", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$noShrapnelMesh(CallbackInfo result) { result.cancel(); }

    @Redirect(method = "accept(Ldev/sbw/deepstrike/network/PatriotDefenseNetwork$InterceptorBurst;)V",
            at = @At(value = "INVOKE", target = "Ldev/sbw/deepstrike/client/FlightLighting;flash(Lnet/minecraft/world/phys/Vec3;FI)V"),
            remap = false, require = 1)
    private static void flamingo$vanillaBurst(Vec3 position, float radius, int duration) {
        LightweightPatriotEffects.burst(position);
    }
}
