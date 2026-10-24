package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanPlayers;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep every recruit/NPC policy; only an actual player's damage ignores diplomacy. */
@Pseudo
@Mixin(targets = "com.talhanation.recruits.RecruitEvents", remap = false)
public abstract class HumanRecruitDamage {
    @Inject(method = "canHarmTeam(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void aviation$humanDamage(LivingEntity attacker, LivingEntity target,
                                            CallbackInfoReturnable<Boolean> result) {
        if (HumanPlayers.attacks(attacker, target)) result.setReturnValue(true);
    }
}
