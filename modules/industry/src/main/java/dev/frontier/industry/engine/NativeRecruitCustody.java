package dev.frontier.industry.engine;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import dev.frontier.industry.engine.IndustryData.Worker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;

/** Native Recruits1.15.2 ledger transitions, separate from entity NBT custody and guarded by its journal. */
public final class NativeRecruitCustody {
    private NativeRecruitCustody() {}
    public static boolean ready(AbstractRecruitEntity recruit) {
        return RecruitEvents.recruitsPlayerUnitManager != null && RecruitEvents.recruitsGroupsManager != null
            && FactionEvents.recruitsFactionManager != null && recruit.getOwnerUUID() != null
            && RecruitEvents.recruitsPlayerUnitManager.getRecruitCount(recruit.getOwnerUUID()) > 0;
    }
    public static boolean debit(IndustryData data, Worker worker, AbstractRecruitEntity recruit) {
        if (worker.nativeDebited) return true;
        if (!ready(recruit) || !worker.owner.equals(recruit.getOwnerUUID()) || !worker.sourceId.equals(recruit.getUUID())) return false;
        ServerLevel level = (ServerLevel) recruit.level();
        worker.nativeTeam = recruit.getTeam() == null ? "" : recruit.getTeam().getName();
        worker.nativeGroup = recruit.getGroup();
        if (worker.nativeGroup != null) {
            var group = RecruitEvents.recruitsGroupsManager.getGroup(worker.nativeGroup);
            if (group != null && worker.owner.equals(group.getPlayerUUID()) && group.members.contains(worker.sourceId)) {
                RecruitEvents.recruitsGroupsManager.removeMember(worker.nativeGroup, worker.sourceId, level);
                worker.nativeGroupRemoved = true;
            }
            recruit.setGroupUUID(null);
        }
        Team team = recruit.getTeam();
        if (team != null) {
            var faction = FactionEvents.recruitsFactionManager.getFactionByStringID(team.getName());
            if (faction != null && faction.getNPCs() > 0) {
                faction.addNPCs(-1); worker.nativeFactionDebited = true;
                FactionEvents.recruitsFactionManager.broadcastToFactionPlayers(team.getName(), level);
            }
            FactionEvents.removeRecruitFromTeam(recruit, team, level);
        }
        RecruitEvents.recruitsPlayerUnitManager.removeRecruits(worker.owner, 1);
        worker.nativeDebited = true; data.setDirty();
        saveNative(level, worker.owner);
        return true;
    }
    public static boolean credit(IndustryData data, Worker worker, AbstractRecruitEntity recruit) {
        if (!worker.nativeDebited || worker.nativeCredited) return true;
        if (worker.dead || RecruitEvents.recruitsPlayerUnitManager == null || RecruitEvents.recruitsGroupsManager == null || FactionEvents.recruitsFactionManager == null) return false;
        ServerLevel level = (ServerLevel) recruit.level();
        if (!worker.owner.equals(recruit.getOwnerUUID()) || !worker.sourceId.equals(recruit.getUUID())) return false;
        if (worker.nativeGroupRemoved && worker.nativeGroup != null) {
            var group = RecruitEvents.recruitsGroupsManager.getGroup(worker.nativeGroup);
            if (group != null && !group.removed && group.disbandContext == null && worker.owner.equals(group.getPlayerUUID())) {
                if (!group.members.contains(worker.sourceId)) RecruitEvents.recruitsGroupsManager.addMember(group.getUUID(), worker.sourceId, level);
                recruit.setGroupUUID(group.getUUID());
            } else recruit.setGroupUUID(null);
        } else recruit.setGroupUUID(null);
        // Rejoin the owner's current country. Removed countries/groups are never resurrected from archived NBT.
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(worker.owner);
        Team team = owner == null ? level.getScoreboard().getPlayerTeam(worker.nativeTeam) : owner.getTeam();
        Team current = recruit.getTeam();
        if (current != null && current != team) FactionEvents.removeRecruitFromTeam(recruit, current, level);
        if (team != null) {
            if (recruit.getTeam() != team) FactionEvents.addRecruitToTeam(recruit, team, level);
            var faction = FactionEvents.recruitsFactionManager.getFactionByStringID(team.getName());
            if (faction != null) { faction.addNPCs(1); FactionEvents.recruitsFactionManager.broadcastToFactionPlayers(team.getName(), level); }
        }
        RecruitEvents.recruitsPlayerUnitManager.addRecruits(worker.owner, 1);
        worker.nativeCredited = true; data.setDirty();
        recruit.needsTeamUpdate = false; recruit.needsGroupUpdate = false;
        saveNative(level, worker.owner);
        return true;
    }
    private static void saveNative(ServerLevel level, java.util.UUID owner) {
        RecruitEvents.recruitsPlayerUnitManager.save(level.getServer().overworld());
        FactionEvents.recruitsFactionManager.save(level.getServer().overworld());
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) RecruitEvents.recruitsPlayerUnitManager.broadCastUnitInfoToPlayer(player);
    }
}
