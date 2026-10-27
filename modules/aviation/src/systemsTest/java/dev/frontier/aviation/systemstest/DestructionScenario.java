package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

/** Actual native death and serialized-wreck round trip in the guarded disposable world. */
public final class DestructionScenario {
    private static final String[] CASES = {"groundlethal", "airbornelethal", "oldwreck"};
    private final ServerLevel level;
    private final BlockPos origin;
    private final ToIntFunction<UUID> explosions;
    private final JsonObject result = new JsonObject(), cases = new JsonObject();
    private final JsonArray reasons = new JsonArray();
    private VehicleEntity aircraft;
    private int index, ticks;
    private boolean done;

    public static DestructionScenario start(ServerLevel level, BlockPos origin, ToIntFunction<UUID> explosionCount) {
        return new DestructionScenario(level, origin, explosionCount);
    }

    private DestructionScenario(ServerLevel level, BlockPos origin, ToIntFunction<UUID> explosionCount) {
        this.level = level;
        this.origin = origin.immutable();
        explosions = explosionCount;
        result.addProperty("pass", false);
        result.add("cases", cases);
        result.add("reasons", reasons);
        spawn();
    }

    private void spawn() {
        if (!level.hasChunkAt(origin)) throw new IllegalStateException("destruction fixture requires a loaded origin");
        if (index == 0) for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++)
            level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
        aircraft = AircraftProfile.F16.entity.get().create(level);
        if (aircraft == null) throw new IllegalStateException("F16 creation failed");
        aircraft.moveTo(origin.getX() + .5, origin.getY() + (index == 1 ? 48 : .05), origin.getZ() + .5, 0, 0);
        if (index == 2) {
            aircraft.setHealth(0);
            aircraft.setWreck(true);
            aircraft.setSympatheticDetonated(true);
            var saved = new CompoundTag();
            if (!aircraft.save(saved)) throw new IllegalStateException("wreck serialization failed");
            var loaded = EntityType.loadEntityRecursive(saved, level, entity -> entity);
            if (!(loaded instanceof VehicleEntity wreck) || !wreck.isWreck()
                    || !wreck.getSympatheticDetonated() || wreck.getHealth() > 0)
                throw new IllegalStateException("saved native wreck did not survive reload");
            aircraft = wreck;
        }
        if (!level.addFreshEntity(aircraft)) throw new IllegalStateException("destruction fixture spawn failed");
        if (index < 2) {
            aircraft.setOnGround(index == 0);
            var attacker = level.players().stream().findFirst().orElseThrow(
                () -> new IllegalStateException("destruction fixture requires a real observing player"));
            // Native VehicleEntity applies onHurt, then returns Entity.hurt's
            // false value. Health, not that boolean, confirms the damage.
            aircraft.hurt(aircraft.damageSources().playerAttack(attacker), 10000);
            if (aircraft.getHealth() > 0) throw new IllegalStateException("damage did not kill native aircraft");
        }
    }

    public boolean tick() {
        if (done) return true;
        ticks++;
        int count = explosions.applyAsInt(aircraft.getUUID());
        if (aircraft.isRemoved() && level.getEntity(aircraft.getUUID()) == null) {
            boolean pass = count == (index == 2 ? 0 : 1);
            var entry = new JsonObject();
            entry.addProperty("pass", pass);
            entry.addProperty("removed", true);
            entry.addProperty("explosions", count);
            entry.addProperty("worldTick", level.getGameTime());
            entry.addProperty("health", aircraft.getHealth());
            cases.add(CASES[index], entry);
            if (!pass) reasons.add(CASES[index] + " unexpected explosion count " + count);
            index++;
            if (index == CASES.length) {
                result.addProperty("pass", reasons.isEmpty());
                done = true;
                return true;
            }
            spawn();
        } else if (ticks >= 150) {
            reasons.add(CASES[index] + " remained after 150 server ticks");
            aircraft.discard();
            done = true;
            return true;
        }
        return false;
    }

    public JsonObject result() { return result; }
}
