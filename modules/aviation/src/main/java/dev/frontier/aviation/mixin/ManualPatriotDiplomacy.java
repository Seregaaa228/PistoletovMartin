package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanManualDefense;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only manual-shot validation changes; allowed()/hostile() for NPC defense are untouched. */
@Pseudo
@Mixin(targets = "dev.frontier.industry.combat.NativeCombatEvents", remap = false)
public abstract class ManualPatriotDiplomacy {
    @Redirect(method = "manualError", at = @At(value = "INVOKE", target =
            "Ldev/frontier/industry/combat/CombatOwnership;hostile(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;Lnet/minecraft/world/entity/Entity;)Z"),
            remap = false, require = 1)
    private static boolean aviation$manualDiplomacy(ServerLevel calledLevel, UUID calledOwner, Entity calledTarget,
                                                   ServerLevel level, Entity station, Entity target, UUID owner) {
        return HumanManualDefense.manualHostile(calledLevel, station, calledOwner, calledTarget);
    }

    @Inject(method = "validInterceptor", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void aviation$recordedHumanShot(ServerLevel level, Entity projectile,
                                                 CallbackInfoReturnable<Boolean> result) {
        Boolean allowed = HumanManualDefense.validateMarkedPatriot(level, projectile);
        if (allowed != null) result.setReturnValue(allowed);
    }
}
