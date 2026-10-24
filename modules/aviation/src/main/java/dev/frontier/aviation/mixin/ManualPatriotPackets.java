package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanManualDefense;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.network.PatriotDefenseNetwork", remap = false)
public abstract class ManualPatriotPackets {
    @Redirect(method = "fire", at = @At(value = "INVOKE", target =
            "Ldev/sbw/deepstrike/network/PatriotDefenseNetwork;fireFromCommand(Lnet/minecraft/server/level/ServerPlayer;Ldev/sbw/deepstrike/vehicle/PatriotTruckEntity;Ljava/util/UUID;I)Z"),
            remap = false, require = 1)
    private static boolean aviation$humanPacket(ServerPlayer player, @Coerce Object station, UUID target, int warhead) {
        return HumanManualDefense.patriotFire(player, station, target, warhead, true);
    }

    @Redirect(method = "lambda$fireFromCommand$17", at = @At(value = "INVOKE", target =
            "Ldev/sbw/deepstrike/vehicle/PatriotTruckEntity$RadarContact;friendly()Z"), remap = false, require = 1)
    private static boolean aviation$humanIff(@Coerce Object contact) {
        return HumanManualDefense.friendly(contact);
    }

    @Redirect(method = "fireFromCommand", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;m_7967_(Lnet/minecraft/world/entity/Entity;)Z"),
            remap = false, require = 1)
    private static boolean aviation$recordHumanLaunch(ServerLevel level, Entity projectile) {
        return HumanManualDefense.addPatriot(level, projectile);
    }
}
