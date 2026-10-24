package dev.frontier.aviation.policy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;

/** Tests the actual source, never a vehicle owner or a recruit's employer. */
public final class HumanPlayers {
    private HumanPlayers() { }

    public static boolean isHuman(Entity source) {
        return source instanceof ServerPlayer player && !(player instanceof FakePlayer)
                && player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    public static boolean attacks(Entity source, Entity target) {
        return target != null && isHuman(source) && source.level() == target.level();
    }

    public static boolean seeking(Entity source) {
        if (!(source instanceof Player) || source instanceof FakePlayer) return false;
        // Client seeking only chooses a target. Every actual attack stays server-authorized.
        return source.level().isClientSide || isHuman(source);
    }
}
