package dev.frontier.arcade.airdefense;

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;

/**
 * Everything that flies, per level, kept as entities join and leave. Radars read this short list
 * instead of sweeping every entity in the level (DeepStrike's batteries each sweep all entities),
 * so the cost of a scan is the number of aircraft, not the size of the world.
 */
public final class AirPicture {
    /** Industry's air-target list: SBW/UF drones, SBW and our aircraft, phantoms. */
    private static final TagKey<EntityType<?>> AIR_TARGETS = TagKey.create(Registries.ENTITY_TYPE,
        new ResourceLocation("frontier_industry", "combat_air_targets"));
    private static final String GUIDED_MISSILE = "dev.sbw.deepstrike.game.GuidedMissileEntity";
    /** Per level: entity to its kind, or {@link #UNKNOWN} until an SBW vehicle has bound its data. */
    private static final Map<Level, Map<Entity, Object>> LEVELS = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Object UNKNOWN = new Object();

    private AirPicture() { }

    /** What a radar would call this entity, or null if it is not an air contact. */
    public static SamEnvelope.Kind kind(Entity entity) {
        if (entity instanceof VehicleEntity vehicle) {
            VehicleType type = vehicle.getVehicleType();
            if (type == null) return null;
            return switch (type) {
                case AIRPLANE, AIRSHIP -> SamEnvelope.Kind.JET;
                case HELICOPTER -> SamEnvelope.Kind.HELICOPTER;
                case DRONE -> SamEnvelope.Kind.DRONE;
                default -> null;
            };
        }
        if (entity.getClass().getName().equals(GUIDED_MISSILE)) return SamEnvelope.Kind.MISSILE;
        if (entity.getType().is(AIR_TARGETS)) return SamEnvelope.Kind.DRONE;
        return null;
    }

    public static void join(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getEntity() instanceof SamMissileEntity) return;
        Entity entity = event.getEntity();
        // SBW vehicles read their type from data that is only bound once added; test the class first.
        if (entity instanceof VehicleEntity || entity.getClass().getName().equals(GUIDED_MISSILE) || entity.getType().is(AIR_TARGETS))
            LEVELS.computeIfAbsent(level, key -> new IdentityHashMap<>()).put(entity, UNKNOWN);
    }

    public static void leave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Map<Entity, Object> map = LEVELS.get(level);
        if (map != null) map.remove(event.getEntity());
    }

    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) LEVELS.remove(level);
    }

    /**
     * Live air contacts of a level (a copy; safe to iterate while entities change). Ground vehicles
     * that joined the list are classified once and dropped, so scans touch only aircraft.
     */
    public static List<Entity> contacts(ServerLevel level) {
        Map<Entity, Object> map = LEVELS.get(level);
        if (map == null || map.isEmpty()) return List.of();
        List<Entity> out = new ArrayList<>();
        for (var iterator = map.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            Entity entity = entry.getKey();
            if (entity.isRemoved()) { iterator.remove(); continue; }
            if (entry.getValue() == UNKNOWN) {
                SamEnvelope.Kind kind = kind(entity);
                if (kind != null) entry.setValue(kind);
                else if (!(entity instanceof VehicleEntity vehicle) || vehicle.getVehicleType() != null
                        && vehicle.getVehicleType() != VehicleType.EMPTY) { iterator.remove(); continue; }
                else continue;
            }
            if (entity.isAlive()) out.add(entity);
        }
        return out;
    }

    /** Cached kind of a listed contact. */
    public static SamEnvelope.Kind cachedKind(ServerLevel level, Entity entity) {
        Map<Entity, Object> map = LEVELS.get(level);
        Object kind = map == null ? null : map.get(entity);
        return kind instanceof SamEnvelope.Kind known ? known : kind(entity);
    }
}
