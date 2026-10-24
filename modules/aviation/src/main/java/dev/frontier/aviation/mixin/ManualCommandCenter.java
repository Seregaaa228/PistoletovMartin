package dev.frontier.aviation.mixin;

import dev.frontier.aviation.policy.HumanManualDefense;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Scope only the defense action already authorized by the native command center. */
@Pseudo
@Mixin(targets = "dev.sbw.deepstrike.game.CommandCenterData", remap = false)
public abstract class ManualCommandCenter {
    @Redirect(method = "action", at = @At(value = "INVOKE", target =
            "Ldev/sbw/deepstrike/game/CommandDefense;action(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;Lnet/minecraft/nbt/CompoundTag;)Ljava/lang/String;"),
            remap = false, require = 1)
    private String aviation$authorizedDefense(ServerPlayer player, CompoundTag session, String action, CompoundTag request) {
        return HumanManualDefense.commandAction(player, session, action, request);
    }
}
