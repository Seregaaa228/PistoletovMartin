package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanPlayers;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ServerPlayer's separate server-PvP check and ordinary hurt checks remain in place. */
@Mixin(value = Player.class, remap = false)
public abstract class PlayerTeamDamage {
    @Inject(method = "m_7099_(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void aviation$humanTeamDamage(Player other, CallbackInfoReturnable<Boolean> result) {
        Player self = (Player)(Object)this;
        if (HumanPlayers.attacks(self, other) && HumanPlayers.isHuman(other)) result.setReturnValue(true);
    }
}
