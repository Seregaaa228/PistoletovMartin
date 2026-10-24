package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanManualDefense;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.network.IronDomeNetwork", remap = false)
public abstract class ManualIronDomePackets {
    @Redirect(method = "handleFire", at = @At(value = "INVOKE", target =
            "Ldev/sbw/deepstrike/vehicle/IronDomeEntity;fireManual(Ljava/util/UUID;Lnet/minecraft/server/level/ServerPlayer;)V"),
            remap = false, require = 1)
    private static void aviation$humanDome(@Coerce Object station, UUID target, ServerPlayer player) {
        HumanManualDefense.domeFire(station, target, player);
    }
}
