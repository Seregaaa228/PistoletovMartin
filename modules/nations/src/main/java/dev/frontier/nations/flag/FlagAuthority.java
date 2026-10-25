package dev.frontier.nations.flag;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsFactionManager;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import javax.annotation.Nullable;

/**
 * Single leader lookup shared by the flag packet handler and the map screen.
 * Truth lives in the native Recruits faction manager; only the faction's
 * {@code teamLeaderID} may overwrite that faction's flag.
 */
public final class FlagAuthority {
    private FlagAuthority() {}

    /** Ignore queued work from a disconnected player, including an older connection with the same UUID. */
    public static boolean isConnected(ServerPlayer player){
        return player!=null&&player.server.getPlayerList().getPlayer(player.getUUID())==player;
    }

    /** Server-side leader test for C2S packets. */
    public static boolean isLeader(ServerPlayer player, String factionId) {
        if (player == null || factionId == null || factionId.isBlank()) return false;
        RecruitsFaction faction = faction(factionId);
        if (faction == null) return false;
        UUID leader = faction.getTeamLeaderUUID();
        return leader != null && leader.equals(player.getUUID());
    }

    @Nullable
    public static RecruitsFaction faction(String factionId) {
        RecruitsFactionManager manager = FactionEvents.recruitsFactionManager;
        if (manager == null) return null;
        try {
            return manager.getFactionByStringID(factionId);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    /** Native lookup used by server handlers: faction by the sender's current membership. */
    @Nullable
    public static String ownFactionId(ServerPlayer player) {
        RecruitsFactionManager manager = FactionEvents.recruitsFactionManager;
        if (manager == null || player == null) return null;
        // Matches RecruitsFactionManager.broadcastOwnFactionToPlayer in pinned Recruits 1.15.2.
        if (player.getTeam() == null) return null;
        String id = player.getTeam().getName();
        return manager.getFactionByStringID(id) == null ? null : id;
    }
}
