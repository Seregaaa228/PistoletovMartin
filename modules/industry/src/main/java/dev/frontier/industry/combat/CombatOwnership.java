package dev.frontier.industry.combat;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import net.minecraftforge.fml.ModList;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class CombatOwnership {
    static final String STATION = "frontierIndustryMilitaryStation";
    private CombatOwnership() { }
    static UUID parse(String value) {
        try { return value == null || value.isEmpty() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static UUID storedOwner(CompoundTag tag, String key) {
        return tag.hasUUID(key) ? tag.getUUID(key) : parse(tag.getString(key));
    }
    static UUID owner(Entity entity) {
        if (entity instanceof LauncherEntity station) return station.ownerUUID();
        if (entity instanceof BallisticMissileEntity missile) return missile.ownerUUID();
        if (entity instanceof InterceptorEntity interceptor) return interceptor.ownerUUID();
        if (entity.getClass().getName().equals("dev.sbw.deepstrike.game.GuidedMissileEntity")) {
            try { Object id = entity.getClass().getMethod("ownerId").invoke(entity); if (id instanceof UUID owner) return owner; }
            catch (ReflectiveOperationException ignored) { return null; }
        }
        if (entity.getClass().getName().startsWith("dev.sbw.deepstrike.vehicle.") && entity.getPersistentData().hasUUID("DeepStrikeOwner"))
            return entity.getPersistentData().getUUID("DeepStrikeOwner");
        if (entity instanceof Player player) return player.getUUID();
        if (entity instanceof TamableAnimal animal && animal.getOwnerUUID() != null) return animal.getOwnerUUID();
        CompoundTag data = entity.getPersistentData();
        UUID marked = storedOwner(data, "frontierIndustryOwner");
        if (marked != null) return marked;
        if (isRecruit(entity)) {
            try {
                if (Boolean.TRUE.equals(entity.getClass().getMethod("getIsOwned").invoke(entity)))
                    return (UUID) entity.getClass().getMethod("getOwnerUUID").invoke(entity);
            } catch (ReflectiveOperationException | ClassCastException ignored) { return null; }
        }
        // Installed Superb Warfare aircraft remember their actual last driver; no guessed owner field.
        if (entity.getClass().getName().startsWith("com.atsuishio.superbwarfare.")
                || entity.getClass().getName().startsWith("ru.lavafrai.uncomplicatedfpv.")
                || entity.getClass().getName().startsWith("dev.sbw.deepstrike.vehicle.")) {
            try {
                Object driver = entity.getClass().getMethod("getLastDriverUUID").invoke(entity);
                if (driver instanceof String text) { UUID id = parse(text); if (id != null) return id; }
            } catch (ReflectiveOperationException ignored) { }
        }
        for (Entity passenger : entity.getPassengers()) {
            UUID passengerOwner = owner(passenger);
            if (passengerOwner != null) return passengerOwner;
        }
        // VehicleOperations writes UUID NBT. A parked claimed aircraft retains its owner,
        // while an actual native drone driver or mounted crew above takes precedence.
        return storedOwner(data, "frontierIndustryVehicleOwner");
    }
    static boolean isRecruit(Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("com.talhanation.recruits.entities.AbstractRecruitEntity")) return true;
        return false;
    }
    static boolean eligibleOperator(LivingEntity entity, UUID owner, UUID station) {
        if (owner == null || !entity.isAlive() || !owner.equals(owner(entity))) return false;
        CompoundTag tag = entity.getPersistentData();
        if (!tag.getBoolean("frontierIndustryWorker") && !isRecruit(entity)) return false;
        if (!tag.getString("frontierIndustrySite").isEmpty() || tag.contains("frontierIndustryVehicle")) return false;
        UUID assigned = parse(tag.getString(STATION));
        if (assigned != null && !assigned.equals(station)) return false;
        return !entity.isPassenger() || (entity.getVehicle() != null && entity.getVehicle().getUUID().equals(station));
    }
    private static Team scoreboardTeam(ServerLevel level, UUID owner) {
        var online = level.getServer().getPlayerList().getPlayer(owner);
        if (online != null) return online.getTeam();
        // Native mobs use their UUID string as their persistent scoreboard entry.
        Team team = level.getScoreboard().getPlayersTeam(owner.toString());
        if (team != null) return team;
        var profile = level.getServer().getProfileCache().get(owner);
        return profile.isPresent() ? level.getScoreboard().getPlayersTeam(profile.get().getName()) : null;
    }
    private static boolean scoreboardFriends(ServerLevel level, UUID a, UUID b) {
        Team first = scoreboardTeam(level, a), second = scoreboardTeam(level, b);
        return first != null && first.isAlliedTo(second);
    }
    /** Read-only UUID -> native country lookup. Never creates a faction, claims land or loads a chunk. */
    static String country(ServerLevel level, UUID owner) {
        if (owner == null || owner.equals(new UUID(0, 0)) || FactionEvents.recruitsFactionManager == null) return null;
        Team current = scoreboardTeam(level, owner);
        if (current != null) {
            // The live/persisted scoreboard assignment takes precedence over stale roster membership.
            if (FactionEvents.recruitsFactionManager.getFactionByStringID(current.getName()) == null) return null;
            return canonicalCountry(level, current.getName());
        }
        Set<String> found = new HashSet<>();
        for (var faction : FactionEvents.recruitsFactionManager.getFactions()) {
            if (owner.equals(faction.getTeamLeaderUUID()) || faction.getMembers().stream().anyMatch(member -> owner.equals(member.getUUID()))) {
                String id = canonicalCountry(level, faction.getStringID());
                if (id == null) return null;
                found.add(id);
            }
        }
        if (ModList.get().isLoaded("recruitsvillages") && !NativeVillages.findCountries(level, owner, found)) return null;
        return found.size() == 1 ? found.iterator().next() : null; // Ambiguous/orphan ownership is never an enemy.
    }
    private static String canonicalCountry(ServerLevel level, String id) {
        if (id == null || id.isEmpty()) return null;
        return ModList.get().isLoaded("recruitsvillages") ? NativeVillages.canonical(level, id) : id;
    }
    private static final class NativeVillages {
        private static String capitalCountry(com.diego.recruitsvillages.data.LivingVillagesSavedData data,
                                             com.diego.recruitsvillages.data.FactionData village) {
            if (!village.isCapital() && data.getFaction(village.getCapitalId()) == null) return null;
            String id = data.getCapital(village).getStringID();
            return id == null || id.isEmpty() || FactionEvents.recruitsFactionManager.getFactionByStringID(id) == null ? null : id;
        }
        static String canonical(ServerLevel level, String id) {
            var data = com.diego.recruitsvillages.data.LivingVillagesSavedData.get(level.getServer());
            String resolved = null;
            boolean matched = false;
            for (var village : data.getFactions()) {
                if (!id.equals(village.getStringID())) continue;
                String capital = capitalCountry(data, village);
                if (capital == null || (matched && !capital.equals(resolved))) return null;
                resolved = capital; matched = true;
            }
            return matched ? resolved : id;
        }
        static boolean findCountries(ServerLevel level, UUID owner, Set<String> found) {
            var data = com.diego.recruitsvillages.data.LivingVillagesSavedData.get(level.getServer());
            for (var village : data.getFactions()) {
                boolean matches = owner.equals(village.getId()) || owner.equals(village.getOwnerUuid()) || owner.equals(village.getLeaderRef())
                        || village.getRoster().stream().anyMatch(unit -> owner.equals(unit.getId()) || owner.equals(unit.getBodyId()));
                if (!matches) continue;
                String id = capitalCountry(data, village);
                if (id == null) return false;
                found.add(id);
            }
            return true;
        }
    }
    static boolean sameTeam(ServerLevel level, UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b) || scoreboardFriends(level, a, b)) return true;
        String first = country(level, a), second = country(level, b);
        if (first == null || second == null) return false;
        if (first.equals(second)) return true;
        if (activeTreaty(first, second)) return true;
        var diplomacy = FactionEvents.recruitsDiplomacyManager;
        return diplomacy != null && (diplomacy.getRelation(first, second) == DiplomacyStatus.ALLY
                || diplomacy.getRelation(second, first) == DiplomacyStatus.ALLY);
    }
    static boolean hostile(ServerLevel level, UUID owner, Entity target) {
        UUID other = owner(target);
        if (owner == null) return false;
        if (other == null) return target instanceof Enemy; // Wild hostile tagged mobs retain native threat behavior.
        if (owner.equals(other) || scoreboardFriends(level, owner, other)) return false;
        String first = country(level, owner), second = country(level, other);
        var diplomacy = FactionEvents.recruitsDiplomacyManager;
        if (first == null || second == null || first.equals(second) || diplomacy == null) return false;
        if (activeTreaty(first, second)) return false;
        // Native relations are directional. Any explicit alliance protects both sides;
        // acquiring a target additionally requires our own country's explicit ENEMY order.
        return diplomacy.getRelation(first, second) == DiplomacyStatus.ENEMY
                && diplomacy.getRelation(second, first) != DiplomacyStatus.ALLY;
    }
    private static boolean activeTreaty(String first, String second) {
        var treaties = FactionEvents.recruitsTreatyManager;
        return treaties != null && treaties.hasTreaty(first, second);
    }
    static void stopNavigation(LivingEntity entity) {
        if (entity instanceof Mob mob) mob.getNavigation().stop();
    }
}
