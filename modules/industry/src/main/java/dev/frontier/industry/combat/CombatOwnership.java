package dev.frontier.industry.combat;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.scores.Team;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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
    /**
     * Public-method lookup resolved once per runtime class. An absent method is cached as empty,
     * so hot paths (launcher scans, interceptor ticks) never re-throw NoSuchMethodException for
     * entity types that simply lack it (e.g. SBW SwarmDroneEntity has no getLastDriverUUID).
     */
    static ClassValue<Optional<Method>> publicMethod(String name, Class<?>... parameters) {
        return new ClassValue<>() {
            @Override protected Optional<Method> computeValue(Class<?> type) {
                try { return Optional.of(type.getMethod(name, parameters)); }
                catch (NoSuchMethodException | SecurityException | LinkageError absent) { return Optional.empty(); }
            }
        };
    }
    /** Invokes a cached public method; null when absent or failing. */
    static Object invoke(ClassValue<Optional<Method>> method, Object target, Object... arguments) {
        Method found = method.get(target.getClass()).orElse(null);
        if (found == null) return null;
        try { return found.invoke(target, arguments); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return null; }
    }
    private static final ClassValue<Optional<Method>> GUIDED_OWNER_ID = publicMethod("ownerId");
    private static final ClassValue<Optional<Method>> RECRUIT_IS_OWNED = publicMethod("getIsOwned");
    private static final ClassValue<Optional<Method>> RECRUIT_OWNER = publicMethod("getOwnerUUID");
    private static final ClassValue<Optional<Method>> LAST_DRIVER = publicMethod("getLastDriverUUID");
    /** SBW VehicleEntity.getNthEntity(int): seat-ordered occupant, seat 0 is the pilot/driver. */
    private static final ClassValue<Optional<Method>> SEAT_OCCUPANT = publicMethod("getNthEntity", int.class);

    static UUID owner(Entity entity) {
        if (entity instanceof LauncherEntity station) return station.ownerUUID();
        if (entity instanceof BallisticMissileEntity missile) return missile.ownerUUID();
        if (entity instanceof InterceptorEntity interceptor) return interceptor.ownerUUID();
        String className = entity.getClass().getName();
        if (className.equals("dev.sbw.deepstrike.game.GuidedMissileEntity")
                && invoke(GUIDED_OWNER_ID, entity) instanceof UUID owner) return owner;
        if (entity instanceof Player player) return player.getUUID();
        if (entity instanceof TamableAnimal animal && animal.getOwnerUUID() != null) return animal.getOwnerUUID();
        CompoundTag data = entity.getPersistentData();
        UUID marked = storedOwner(data, "frontierIndustryOwner");
        if (marked != null) return marked;
        if (isRecruit(entity) && Boolean.TRUE.equals(invoke(RECRUIT_IS_OWNED, entity)))
            return invoke(RECRUIT_OWNER, entity) instanceof UUID owner ? owner : null;
        // Installed Superb Warfare aircraft remember their actual last driver; no guessed owner field.
        if (className.startsWith("com.atsuishio.superbwarfare.")
                || className.startsWith("ru.lavafrai.uncomplicatedfpv.")
                || className.startsWith("dev.sbw.deepstrike.vehicle.")) {
            UUID controller = droneController(entity);
            if (controller != null) return controller;
            // Native SBW swarm drones are vanilla projectiles: their launcher/shooter is the owner.
            if (entity instanceof Projectile projectile && projectile.getOwner() != null && !(projectile.getOwner() instanceof Projectile)) {
                UUID shooter = owner(projectile.getOwner());
                if (shooter != null) return shooter;
            }
            // Whoever is actually flying it decides friend-or-foe: neither SBW nor DeepStrike stops a
            // non-owner from boarding (Su24mEntity.canOperate accepts any occupant of seats 0/1), so a
            // stolen jet must not keep its deploy-owner's identity. Deploy-owner/last driver apply only when vacant.
            UUID occupant = occupantOwner(entity);
            if (occupant != null) return occupant;
            if (className.startsWith("dev.sbw.deepstrike.vehicle.") && data.hasUUID("DeepStrikeOwner"))
                return data.getUUID("DeepStrikeOwner");
            if (invoke(LAST_DRIVER, entity) instanceof String text) { UUID id = parse(text); if (id != null) return id; }
        } else {
            UUID occupant = occupantOwner(entity);
            if (occupant != null) return occupant;
        }
        // VehicleOperations writes UUID NBT. A parked claimed aircraft retains its owner,
        // while an actual native drone driver or mounted crew above takes precedence.
        return storedOwner(data, "frontierIndustryVehicleOwner");
    }
    /** Pilot first (vanilla controlling passenger, else SBW seat 0), then any other occupant with an owner. */
    private static UUID occupantOwner(Entity vehicle) {
        if (!vehicle.isVehicle()) return null;
        Entity pilot = vehicle.getControllingPassenger();
        if (pilot == null && invoke(SEAT_OCCUPANT, vehicle, 0) instanceof Entity seated && seated.getVehicle() == vehicle) pilot = seated;
        if (pilot != null && pilot != vehicle) {
            UUID pilotOwner = owner(pilot);
            if (pilotOwner != null) return pilotOwner;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger == pilot) continue;
            UUID passengerOwner = owner(passenger);
            if (passengerOwner != null) return passengerOwner;
        }
        return null;
    }
    private static final String SBW_DRONE = "com.atsuishio.superbwarfare.entity.vehicle.DroneEntity";
    private static EntityDataAccessor<?> droneControllerKey;
    private static boolean droneControllerResolved;
    /**
     * SBW's remotely flown DroneEntity has no rider and no last driver; the linking player's
     * UUID string lives in its synced {@code DroneEntity.CONTROLLER} (Kotlin @JvmField static),
     * with "undefined"/"none" when unlinked.
     */
    private static UUID droneController(Entity entity) {
        Class<?> drone = null;
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(SBW_DRONE)) { drone = type; break; }
        if (drone == null) return null;
        if (!droneControllerResolved) {
            droneControllerResolved = true;
            try { if (drone.getField("CONTROLLER").get(null) instanceof EntityDataAccessor<?> key) droneControllerKey = key; }
            catch (ReflectiveOperationException ignored) { }
        }
        if (droneControllerKey == null) return null;
        try { return entity.getEntityData().get(droneControllerKey) instanceof String id ? parse(id) : null; }
        catch (RuntimeException unexpected) { return null; }
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
        return hostileOwners(level, owner, other);
    }
    private static boolean hostileOwners(ServerLevel level, UUID owner, UUID other) {
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
    /*
     * Hot-loop relation cache (launcher scans every 5 ticks, each interceptor every tick).
     * country() can walk every faction member and LivingVillages roster, so the owner-pair
     * verdict is reused for 20 server ticks. The target's owner is still resolved live, so a
     * pilot change is immediate; only a diplomacy change may lag by <=1s, which is the accepted
     * IFF tradeoff ("don't change relations mid-engagement; re-check IFF on missiles already in
     * flight" - interceptors re-check through this every tick). Exact checks (canTarget, fixtures)
     * keep calling the uncached hostile()/sameTeam(). Server thread only.
     */
    private static final int RELATION_TTL = 20;
    private record RelationKey(UUID a, UUID b, boolean allied) { }
    private static final Map<RelationKey, long[]> RELATIONS = new HashMap<>();
    private static MinecraftServer relationServer;
    static boolean hostileCached(ServerLevel level, UUID owner, Entity target) {
        if (owner == null) return false;
        UUID other = owner(target);
        if (other == null) return target instanceof Enemy;
        return cachedRelation(level, new RelationKey(owner, other, false), () -> hostileOwners(level, owner, other));
    }
    static boolean sameTeamCached(ServerLevel level, UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return cachedRelation(level, new RelationKey(a, b, true), () -> sameTeam(level, a, b));
    }
    private static boolean cachedRelation(ServerLevel level, RelationKey key, BooleanSupplier compute) {
        MinecraftServer server = level.getServer();
        long now = server.getTickCount();
        if (relationServer != server) { RELATIONS.clear(); relationServer = server; }
        long[] entry = RELATIONS.get(key);
        if (entry != null && now >= entry[0] && now - entry[0] < RELATION_TTL) return entry[1] != 0;
        if (RELATIONS.size() > 512) RELATIONS.values().removeIf(old -> now < old[0] || now - old[0] >= RELATION_TTL);
        boolean result = compute.getAsBoolean();
        RELATIONS.put(key, new long[] { now, result ? 1 : 0 });
        return result;
    }
    private static boolean activeTreaty(String first, String second) {
        var treaties = FactionEvents.recruitsTreatyManager;
        return treaties != null && treaties.hasTreaty(first, second);
    }
    static void stopNavigation(LivingEntity entity) {
        if (entity instanceof Mob mob) mob.getNavigation().stop();
    }
}
