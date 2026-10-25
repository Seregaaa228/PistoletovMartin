package dev.frontier.industry.construction;

import net.minecraft.world.entity.player.Player;

/** Building kits are an on-foot tool; vehicle controls must never place a site. */
public final class ConstructionAccess {
    private ConstructionAccess() {}

    public static boolean canBuild(Player player) {
        return player != null && player.isAlive() && !player.isSpectator() && !player.isPassenger();
    }
}
