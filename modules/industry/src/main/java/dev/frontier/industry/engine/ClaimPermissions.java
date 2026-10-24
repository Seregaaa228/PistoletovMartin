package dev.frontier.industry.engine;

import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.config.RecruitsServerConfig;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.ModList;

/** Read the native claim index; never call the claim event handler which loads Overworld chunks. */
public final class ClaimPermissions {
    private ClaimPermissions() {}
    public static boolean allows(ServerLevel level, UUID owner, String savedTeam, BlockPos pos) {
        return allows(level,owner,savedTeam,pos,false);
    }
    public static boolean allowsBreaking(ServerLevel level, UUID owner, String savedTeam, BlockPos pos) {
        return allows(level,owner,savedTeam,pos,true);
    }
    private static boolean allows(ServerLevel level,UUID owner,String savedTeam,BlockPos pos,boolean breaking) {
        if (!level.getWorldBorder().isWithinBounds(pos) || level.isOutsideBuildHeight(pos)) return false;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player != null && !level.mayInteract(player, pos)) return false;
        if (!ModList.get().isLoaded("recruits")) return true;
        return NativeClaims.allows(level, owner, savedTeam, pos, player,breaking);
    }
    private static final class NativeClaims {
        static boolean allows(ServerLevel level, UUID owner, String savedTeam, BlockPos pos, ServerPlayer player,boolean breaking) {
            // Recruits 1.15.2 maintains one claim map for the Overworld only.
            if (level != level.getServer().overworld()) return true;
            if (ClaimEvents.recruitsClaimManager == null) return false;
            var claim = ClaimEvents.recruitsClaimManager.getClaim(new ChunkPos(pos));
            if (claim == null) return !RecruitsServerConfig.BlockPlacingBreakingOnlyWhenClaimed.get();
            if (player != null && player.isCreative() && player.hasPermissions(2)) return true;
            if (claim.isAdmin) return breaking?claim.isBlockBreakingAllowed():claim.isBlockPlacementAllowed();
            // A claim loaded from NBT contains an old faction snapshot; resolve its live roster.
            String factionId=claim.getOwnerFactionStringID();
            if(factionId==null||factionId.isBlank())return breaking?claim.isBlockBreakingAllowed():claim.isBlockPlacementAllowed();
            var manager=com.talhanation.recruits.FactionEvents.recruitsFactionManager;
            if(manager==null)return false;
            var faction=manager.getFactionByStringID(factionId);
            if(faction==null)return false;
            if (owner.equals(faction.getTeamLeaderUUID())) return true;
            if (faction.getMembers().stream().anyMatch(member -> owner.equals(member.getUUID()))) return true;
            // Saved site/scoreboard team strings are historical labels, not current membership.
            return false;
        }
    }
}
