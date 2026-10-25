package dev.frontier.nations.flag;

import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.world.RecruitsFaction;
import java.util.UUID;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import javax.annotation.Nullable;

/**
 * Client-side leader test for editor buttons. Uses the same native
 * {@code teamLeaderID} field as the server check, read from the already
 * synchronized {@code ClientManager.ownFaction}.
 */
@OnlyIn(Dist.CLIENT)
public final class FlagLeadership {
    private FlagLeadership() {}

    public static boolean isLocalLeader(@Nullable RecruitsFaction ownFaction) {
        if (ownFaction == null) return false;
        try {
            UUID leader = ownFaction.getTeamLeaderUUID();
            if (leader == null) return false;
            if (net.minecraft.client.Minecraft.getInstance().player == null) return false;
            return leader.equals(net.minecraft.client.Minecraft.getInstance().player.getUUID());
        } catch (RuntimeException missing) {
            return false;
        }
    }

    public static boolean isLocalLeader() {
        try {
            return isLocalLeader(ClientManager.ownFaction);
        } catch (RuntimeException | NoClassDefFoundError missing) {
            return false;
        }
    }
}
