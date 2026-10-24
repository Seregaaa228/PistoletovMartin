package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanManualDefense;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.vehicle.IronDomeEntity", remap = false)
public abstract class ManualIronDomeLaunch {
    @Redirect(method = "launchTamir", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;m_7967_(Lnet/minecraft/world/entity/Entity;)Z"),
            remap = false, require = 1)
    private boolean aviation$recordHumanDome(ServerLevel level, Entity projectile) {
        return HumanManualDefense.addPatriot(level, projectile);
    }
}
