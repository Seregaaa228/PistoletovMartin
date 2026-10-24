package dev.frontier.flamingofix;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Frontier's NPC policy applies after a launcher has explicitly joined Frontier crew control. */
public final class LauncherPolicy {
    private LauncherPolicy() {}
    public static boolean requiresFrontierCrew(Object candidate) {
        if (!(candidate instanceof BlockEntity block)
                || !candidate.getClass().getName().equals("dev.sbw.deepstrike.game.LauncherBlockEntity")) return false;
        CompoundTag state = block.getPersistentData();
        // Keep the policy for owned, assigned, released, held and stale/dead-crew installations.
        // Native DeepStrike placement and notebook binding do not write any of these markers.
        return state.contains("frontierNativeDefenseOwner")
                || state.contains("frontierNativeDefenseCrew")
                || state.contains("frontierNativeDefenseHold");
    }
}
