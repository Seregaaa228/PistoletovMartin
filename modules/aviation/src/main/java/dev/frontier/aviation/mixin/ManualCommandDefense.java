package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanManualDefense;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The original authorized command/session action still performs all non-diplomacy checks. */
@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.game.CommandDefense", remap = false)
public abstract class ManualCommandDefense {
    @Redirect(method = "action", at = @At(value = "INVOKE", target =
            "Ldev/sbw/deepstrike/network/PatriotDefenseNetwork;fireFromCommand(Lnet/minecraft/server/level/ServerPlayer;Ldev/sbw/deepstrike/vehicle/PatriotTruckEntity;Ljava/util/UUID;I)Z"),
            remap = false, require = 1)
    private static boolean aviation$humanCommand(ServerPlayer player, @Coerce Object station, UUID target, int warhead) {
        return HumanManualDefense.patriotFire(player, station, target, warhead, false);
    }
    @Redirect(method = "lambda$action$0", at = @At(value = "INVOKE", target =
            "Ldev/sbw/deepstrike/network/IronDomeNetwork$Contact;friendly()Z"), remap = false, require = 1)
    private static boolean aviation$humanDomeIff(@Coerce Object contact) {
        return HumanManualDefense.friendly(contact);
    }

}
