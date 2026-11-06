package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.impact.MidAirCollisions;
import dev.frontier.aviation.weapons.GuidedOrdnance;
import dev.frontier.aviation.weapons.KabEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Test-only scenarios for the tester-fix commit (cannon vs walls/entities, KAB penetrator,
 * mid-air collisions, FPV vs jets, seeker self-lock, worker look). Each records raw
 * observations; nothing here changes production behaviour.
 */
final class TesterFix {
    private TesterFix() {}

    interface Scenario { boolean tick(); JsonObject result(); }

    // ---------------------------------------------------------------- observations
    static final List<JsonObject> VEHICLE_HURTS = new ArrayList<>();
    static final List<JsonObject> LIVING_HURTS = new ArrayList<>();
    static final class Det { Entity source; Vec3 pos; List<BlockPos> blocks; List<String> entities; long tick; }
    static final List<Det> DETONATIONS = new ArrayList<>();
    private static final Map<VehicleEntity, Float> PENDING = new IdentityHashMap<>();
    static volatile boolean recording;

    static void clearObservations() { VEHICLE_HURTS.clear(); LIVING_HURTS.clear(); DETONATIONS.clear(); PENDING.clear(); }

    public static void vehicleHurtHead(VehicleEntity vehicle, DamageSource source, float amount) {
        if (!recording || vehicle.level().isClientSide) return;
        PENDING.put(vehicle, vehicle.getHealth());
    }

    public static void vehicleHurtReturn(VehicleEntity vehicle, DamageSource source, float amount, boolean accepted) {
        if (!recording || vehicle.level().isClientSide) return;
        Float before = PENDING.remove(vehicle);
        if (VEHICLE_HURTS.size() > 4000) return;
        var row = new JsonObject();
        Entity body = vehicle;
        row.addProperty("uuid", body.getUUID().toString());
        row.addProperty("tick", body.level().getGameTime());
        row.addProperty("msgId", source.getMsgId());
        row.addProperty("direct", source.getDirectEntity() == null ? "none" : String.valueOf(EntityType.getKey(source.getDirectEntity().getType())));
        row.addProperty("attacker", source.getEntity() == null ? "none" : String.valueOf(EntityType.getKey(source.getEntity().getType())));
        row.addProperty("amount", amount);
        row.addProperty("accepted", accepted);
        row.addProperty("healthBefore", before == null ? -1 : before);
        row.addProperty("healthAfter", vehicle.getHealth());
        VEHICLE_HURTS.add(row);
    }

    static void livingHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        if (!recording || entity.level().isClientSide || LIVING_HURTS.size() > 4000) return;
        var source = event.getSource();
        var row = new JsonObject();
        row.addProperty("uuid", entity.getUUID().toString());
        row.addProperty("type", String.valueOf(EntityType.getKey(entity.getType())));
        row.addProperty("tick", entity.level().getGameTime());
        row.addProperty("msgId", source.getMsgId());
        row.addProperty("direct", source.getDirectEntity() == null ? "none" : String.valueOf(EntityType.getKey(source.getDirectEntity().getType())));
        row.addProperty("amount", event.getAmount());
        row.addProperty("healthBefore", entity.getHealth());
        LIVING_HURTS.add(row);
    }

    static void detonate(ExplosionEvent.Detonate event) {
        if (!recording || event.getLevel().isClientSide || DETONATIONS.size() > 200) return;
        var det = new Det();
        det.source = event.getExplosion().getDirectSourceEntity();
        det.pos = event.getExplosion().getPosition();
        det.blocks = new ArrayList<>(event.getAffectedBlocks());
        det.entities = new ArrayList<>();
        for (Entity entity : event.getAffectedEntities()) det.entities.add(String.valueOf(EntityType.getKey(entity.getType())));
        det.tick = event.getLevel().getGameTime();
        DETONATIONS.add(det);
    }

    static JsonArray hurtsOf(List<JsonObject> rows, Entity entity) {
        var out = new JsonArray();
        String id = entity.getUUID().toString();
        for (var row : rows) if (row.get("uuid").getAsString().equals(id)) out.add(row);
        return out;
    }

    // ---------------------------------------------------------------- runner actions
    private static int actionSeq;

    static Path game() { return FMLPaths.GAMEDIR.get(); }
    static String runId() { return System.getProperty("frontier.aircraftSystemsRunId"); }

    /** Asks the runner for real client input / screenshots: [["key","F5"],["shot","name"],["sleep","1.0"]]. */
    static int requestActions(String... steps) {
        var actions = new JsonArray();
        for (String step : steps) {
            var parts = step.split(" ", 2);
            var pair = new JsonArray();
            pair.add(parts[0]);
            pair.add(parts.length > 1 ? parts[1] : "");
            actions.add(pair);
        }
        var request = new JsonObject();
        request.addProperty("runId", runId());
        request.addProperty("seq", ++actionSeq);
        request.add("actions", actions);
        try { Files.writeString(game().resolve("aircraft-action.json"), request.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        return actionSeq;
    }

    static boolean actionsDone(int seq) {
        try {
            var done = game().resolve("aircraft-action-done.txt");
            return Files.isRegularFile(done) && Files.readString(done).trim().equals(runId() + ":" + seq);
        } catch (java.io.IOException failure) { return false; }
    }

    static double tickMs(MinecraftServer server) {
        return server.tickTimes[server.getTickCount() % 100] / 1.0e6;
    }

    static AABB bounds(VehicleEntity vehicle) {
        vehicle.updateOBB();
        AABB bounds = null;
        for (var box : vehicle.getOBBs()) {
            AABB world = OBB.getWorldAABB(box);
            bounds = bounds == null ? world : bounds.minmax(world);
        }
        Entity body = vehicle;
        return bounds == null ? body.getBoundingBox() : bounds;
    }

    static AABB bounds(Entity entity) {
        return entity instanceof VehicleEntity vehicle ? bounds(vehicle) : entity.getBoundingBox();
    }

    static float health(Entity entity) {
        if (entity instanceof VehicleEntity vehicle) return vehicle.getHealth();
        if (entity instanceof LivingEntity living) return living.getHealth();
        return -1;
    }

    static int ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4); // heightmaps of an unloaded chunk read as the world bottom
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    static Entity create(ServerLevel level, String id) {
        var type = EntityType.byString(id).orElseThrow(() -> new IllegalStateException("no entity type " + id));
        Entity entity = type.create(level);
        if (entity == null) throw new IllegalStateException("cannot create " + id);
        if (entity instanceof Mob mob) { mob.setNoAi(true); mob.setPersistenceRequired(); }
        return entity;
    }

    /** Dismounted test player back on the ground with no fall pending. */
    static void land(ServerPlayer player) {
        if (player.isPassenger()) player.stopRiding();
        player.fallDistance = 0;
        int g = ground(player.serverLevel(), player.getBlockX(), player.getBlockZ());
        player.connection.teleport(player.getX(), g, player.getZ(), player.getYRot(), 0);
    }

    static double round(double value) { return Math.round(value * 100) / 100.0; }

    static JsonArray vec(Vec3 v) {
        var out = new JsonArray();
        out.add(round(v.x)); out.add(round(v.y)); out.add(round(v.z));
        return out;
    }

    // ================================================================ 1. cannon
    /** A hovering jet fires single rounds at a rebar concrete wall, a BMP-2, a truck and an iron golem. */
    static final class CannonTargets implements Scenario {
        private static final String[] TARGETS = {"superbwarfare:bmp_2", "superbwarfare:truck", "minecraft:villager", "minecraft:cow"};
        private static final int WALL_ROUNDS = 50, TARGET_ROUNDS = 20;
        private final ServerPlayer pilot;
        private final ServerLevel level;
        private final VehicleEntity plane;
        private final ControlLease lease;
        private final JsonObject result = new JsonObject();
        private final int baseX, baseZ;
        private final double flyY;
        private final List<BlockPos> backstop = new ArrayList<>();
        private final Set<Object> seen = new HashSet<>();
        private final Map<BlockPos, Integer> hits = new HashMap<>();
        private final Set<BlockPos> wall = new HashSet<>(), broken = new HashSet<>();
        private final JsonArray hitsToBreak = new JsonArray(), otherSample = new JsonArray();
        private int ticks, phase, phaseTicks, queued, wallZ, targetIndex, otherHits, wallHits;
        private Vec3 spawn;
        private Entity target;
        private Vec3 targetCentre, hold, centreOffset;
        private ImpactObservations.Trace lastAimed;
        private float targetBefore;

        CannonTargets(ServerPlayer pilot, AircraftProfile profile, int baseX, int baseZ) {
            this.pilot = pilot;
            this.level = pilot.serverLevel();
            this.baseX = baseX;
            this.baseZ = baseZ;
            if (pilot.isPassenger()) pilot.stopRiding();
            pilot.setGameMode(GameType.CREATIVE);
            int g = ground(level, baseX, baseZ);
            flyY = g + 6;
            pilot.teleportTo(level, baseX + .5, flyY, baseZ + .5, 0, 0);
            plane = profile.entity.get().create(level);
            if (plane == null) throw new IllegalStateException("aircraft creation failed");
            Entity body = plane;
            body.moveTo(baseX + .5, flyY, baseZ + .5, 0, 0);
            body.setNoGravity(true);
            body.setInvulnerable(true);
            plane.setEnergy(plane.getMaxEnergy());
            if (!level.addFreshEntity(body) || !pilot.startRiding(body, true)) throw new IllegalStateException("cannon mount failed");
            lease = AircraftController.acquire(pilot, plane).orElseThrow();
            ImpactObservations.clear();
            clearObservations();
            recording = true;
            result.addProperty("aircraft", profile.id.toString());
            result.addProperty("pass", false);
        }

        private List<ImpactObservations.Trace> fresh() {
            var out = new ArrayList<ImpactObservations.Trace>();
            for (var trace : ImpactObservations.owned(pilot.getUUID(), ((Entity) plane).getUUID()))
                if (trace.type.equals("superbwarfare:small_cannon_shell") && !seen.contains(trace) && (trace.hit != null || trace.hitEntity != null)) out.add(trace);
            return out;
        }

        private void markSeen() {
            seen.addAll(ImpactObservations.owned(pilot.getUUID(), ((Entity) plane).getUUID()));
        }

        private boolean fireOnce() {
            if (AircraftArmament.fire(pilot, plane, lease, "cannon", 1, null) == AircraftArmament.Result.QUEUED) { queued++; return true; }
            return false;
        }

        private Vec3 velocity;
        private double gravity;

        /** The shell's real path (CannonShells: p += v; v.y -= g) where it crosses z. */
        private Vec3 line(double z) { return path(spawn, velocity, z); }

        private Vec3 path(Vec3 from, Vec3 initial, double z) {
            Vec3 p = from, v = initial;
            for (int i = 0; i < 400 && p.z + v.z < z; i++) { p = p.add(v); v = v.add(0, -gravity, 0); }
            double f = v.z > 1e-6 ? (z - p.z) / v.z : 0;
            return p.add(v.scale(Math.max(0, Math.min(1, f))));
        }

        private void next(int to) { phase = to; phaseTicks = 0; queued = 0; }

        @Override public boolean tick() {
            ticks++;
            phaseTicks++;
            Entity body = plane;
            AircraftController.submit(pilot, lease, PilotCommand.neutral(3_000_000L + ticks));
            body.setDeltaMovement(0, 0, 0);
            if (pilot.getVehicle() != body) return finish("pilot dismounted");
            switch (phase) {
                case 0 -> {
                    if (phaseTicks == 5) {
                        if (!AircraftArmament.select(pilot, plane, lease, "cannon")) return finish("cannon selection failed");
                        for (int dx = -15; dx <= 15; dx++) for (int dy = -8; dy <= 8; dy++) {
                            var pos = BlockPos.containing(baseX + dx, flyY + dy, baseZ + 70);
                            level.setBlockAndUpdate(pos, Blocks.WHITE_WOOL.defaultBlockState());
                            backstop.add(pos);
                        }
                        next(1);
                    }
                }
                case 1 -> { // calibration
                    if (queued < 3 && phaseTicks > 2) fireOnce();
                    if (phaseTicks == 45) {
                        Vec3 s = Vec3.ZERO, v = Vec3.ZERO;
                        int n = 0;
                        var traces = ImpactObservations.owned(pilot.getUUID(), body.getUUID());
                        var spawns = new JsonArray();
                        for (var trace : traces) {
                            s = s.add(trace.spawn); v = v.add(trace.initialVelocity); n++;
                            gravity = ((com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity) trace.entity).getCustomGravity();
                            spawns.add(vec(trace.spawn)); spawns.add(vec(trace.initialVelocity));
                            if (trace.hit != null) spawns.add(vec(trace.hit));
                        }
                        result.addProperty("calibrationShots", traces.size());
                        result.add("calibrationTraces", spawns);
                        if (n == 0) return finish("no calibration rounds observed");
                        spawn = s.scale(1.0 / n);
                        velocity = v.scale(1.0 / n);
                        result.addProperty("shellGravity", gravity);
                        result.add("pathAt40", vec(line(baseZ + 40.5)));
                        markSeen();
                        // Rebar concrete wall 40 blocks ahead, centred on the round's path.
                        Block rebar = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("engineersdecor", "rebar_concrete"));
                        if (rebar == null || rebar == Blocks.AIR) return finish("engineersdecor:rebar_concrete missing");
                        BlockPos centre = BlockPos.containing(line(baseZ + 40.5));
                        wallZ = centre.getZ();
                        BlockState state = rebar.defaultBlockState();
                        result.addProperty("rebarHardness", state.getDestroySpeed(level, centre));
                        result.addProperty("rebarBlastResistance", rebar.getExplosionResistance());
                        // Tall enough for the slow nose-down drift of the hovering jet during the burst.
                        for (int dx = -3; dx <= 3; dx++) for (int dy = -9; dy <= 3; dy++) {
                            var pos = centre.offset(dx, dy, 0);
                            level.setBlockAndUpdate(pos, state);
                            wall.add(pos.immutable());
                        }
                        next(2);
                    }
                }
                case 2 -> { // rebar wall
                    if (queued < WALL_ROUNDS && phaseTicks % 5 == 0) fireOnce();
                    for (var trace : fresh()) {
                        seen.add(trace);
                        if (trace.hit == null) continue;
                        var pos = BlockPos.containing(trace.hit.x, trace.hit.y, wallZ + .5);
                        if (Math.abs(trace.hit.z - wallZ) < .6 && wall.contains(pos)) {
                            wallHits++;
                            int n = hits.merge(pos, 1, Integer::sum);
                            if (level.getBlockState(pos).isAir() && broken.add(pos)) hitsToBreak.add(n);
                        } else {
                            otherHits++;
                            if (otherSample.size() < 8) otherSample.add(vec(trace.hit));
                        }
                    }
                    if (phaseTicks == 7) {
                        int present = 0;
                        for (var pos : wall) if (!level.getBlockState(pos).isAir()) present++;
                        result.addProperty("rebarBlocksPresentAfterPlacement", present);
                        result.addProperty("rebarStateAtCentre", String.valueOf(level.getBlockState(wall.iterator().next())));
                    }
                    if (queued >= WALL_ROUNDS && phaseTicks > WALL_ROUNDS * 5 + 30) {
                        var wallResult = new JsonObject();
                        wallResult.addProperty("roundsQueued", queued);
                        wallResult.addProperty("hitsOnWall", wallHits);
                        wallResult.addProperty("hitsElsewhere", otherHits);
                        wallResult.add("hitsElsewhereSample", otherSample);
                        wallResult.addProperty("cannonShellsImpactsTotal", dev.frontier.aviation.weapons.CannonShells.impacts);
                        wallResult.addProperty("blocksBroken", broken.size());
                        wallResult.add("hitsToBreakEach", hitsToBreak);
                        var unbroken = new JsonArray();
                        for (var entry : hits.entrySet()) if (!broken.contains(entry.getKey())) unbroken.add(entry.getValue());
                        wallResult.add("hitsOnSurvivingBlocks", unbroken);
                        result.add("rebarWall", wallResult);
                        for (var pos : wall) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        ImpactObservations.clear();
                        seen.clear();
                        next(3);
                    }
                }
                case 3 -> { // entity targets
                    if (target == null) {
                        if (targetIndex >= TARGETS.length) return finish(null);
                        target = create(level, TARGETS[targetIndex]);
                        targetCentre = line(baseZ + 40.5);
                        target.moveTo(targetCentre.x, targetCentre.y - target.getBbHeight() / 2, targetCentre.z, 180, 0);
                        target.setNoGravity(true);
                        if (!level.addFreshEntity(target)) return finish("target rejected " + TARGETS[targetIndex]);
                        phaseTicks = 0;
                        queued = 0;
                        return false;
                    }
                    if (phaseTicks == 8) {
                        // Re-centre the real hit volume (OBB for vehicles) on the round path.
                        Vec3 c = bounds(target).getCenter();
                        target.setPos(target.getX() + targetCentre.x - c.x, target.getY() + targetCentre.y - c.y, target.getZ());
                        hold = target.position();
                        centreOffset = bounds(target).getCenter().subtract(hold);
                        result.add(TARGETS[targetIndex] + "_hitVolume", vec(bounds(target).getCenter()));
                    }
                    if (phaseTicks > 10 && centreOffset != null) {
                        // Each round is launched (and observed) a tick before it reaches the target:
                        // put the target's hit volume on that round's own path, as a pilot's aim would.
                        ImpactObservations.Trace newest = null;
                        for (var trace : ImpactObservations.owned(pilot.getUUID(), body.getUUID()))
                            if (trace.type.equals("superbwarfare:small_cannon_shell") && !seen.contains(trace) && trace.hit == null && trace.hitEntity == null) newest = trace;
                        if (newest != null && newest != lastAimed) {
                            lastAimed = newest;
                            Vec3 point = path(newest.spawn, newest.initialVelocity, hold.z + centreOffset.z);
                            hold = point.subtract(centreOffset);
                            target.setPos(hold.x, hold.y, hold.z);
                        }
                    }
                    if (phaseTicks == 10) {
                        targetBefore = health(target);
                        markSeen();
                    }
                    if (phaseTicks > 8 && target.isAlive()) { target.setDeltaMovement(0, 0, 0); target.setPos(hold.x, hold.y, hold.z); }
                    if (phaseTicks > 10 && queued < TARGET_ROUNDS && phaseTicks % 4 == 0 && target.isAlive()) fireOnce();
                    if (phaseTicks > 10 + TARGET_ROUNDS * 4 + 30 || (phaseTicks > 14 && !target.isAlive() && phaseTicks % 20 == 0)) {
                        var row = new JsonObject();
                        int entityHits = 0, blockHits = 0;
                        var where = new JsonArray();
                        for (var trace : fresh()) {
                            if (target.getUUID().equals(trace.hitEntity)) entityHits++;
                            else if (trace.hit != null) { blockHits++; if (where.size() < 4) where.add(vec(trace.hit)); }
                        }
                        row.add("blockHitSample", where);
                        row.addProperty("roundsQueued", queued);
                        row.addProperty("entityHitsObserved", entityHits);
                        row.addProperty("blockHitsInstead", blockHits);
                        row.addProperty("healthBefore", targetBefore);
                        row.addProperty("healthAfter", health(target));
                        row.addProperty("alive", target.isAlive());
                        row.addProperty("removal", String.valueOf(target.getRemovalReason()));
                        if (target instanceof VehicleEntity vehicle) row.addProperty("wreck", vehicle.isWreck());
                        var hurts = target instanceof VehicleEntity ? hurtsOf(VEHICLE_HURTS, target) : hurtsOf(LIVING_HURTS, target);
                        row.addProperty("hurtEvents", hurts.size());
                        var sample = new JsonArray();
                        for (int i = 0; i < Math.min(4, hurts.size()); i++) sample.add(hurts.get(i));
                        row.add("hurtSample", sample);
                        result.add(TARGETS[targetIndex], row);
                        target.discard();
                        target = null;
                        centreOffset = null;
                        targetIndex++;
                        ImpactObservations.clear();
                        seen.clear();
                    }
                }
                default -> { }
            }
            return ticks > 2400 && finish("cannon timeout");
        }

        private boolean finish(String error) {
            if (error != null) result.addProperty("error", error);
            else {
                boolean pass = result.has("rebarWall") && result.getAsJsonObject("rebarWall").get("blocksBroken").getAsInt() > 0;
                for (String id : TARGETS) pass &= result.has(id)
                    && (result.getAsJsonObject(id).get("healthAfter").getAsFloat() < result.getAsJsonObject(id).get("healthBefore").getAsFloat()
                        || result.getAsJsonObject(id).get("hurtEvents").getAsInt() > 0);
                result.addProperty("pass", pass);
            }
            recording = false;
            AircraftController.release(pilot, lease);
            land(pilot);
            ((Entity) plane).discard();
            if (target != null) target.discard();
            for (var pos : backstop) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (var pos : wall) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ================================================================ 2. KAB craters
    /** Real KAB stores dropped on built terrain: open ground, a brick building and water. */
    static final class KabCraters implements Scenario {
        private record Spec(String name, int variant, String kind, int half, int depth) {}
        private static final Spec[] SITES = {
            new Spec("kab500_ground", 0, "ground", 15, 16),
            new Spec("kab500_building", 0, "building", 15, 12),
            new Spec("kab500_water", 0, "water", 15, 16),
            new Spec("kab1500_ground", 1, "ground", 40, 30)};
        private final ServerPlayer observer;
        private final ServerLevel level;
        private final JsonObject result = new JsonObject();
        private final boolean include1500;
        private int ticks, site = -1, stage, stageTicks, layer, cx, cz, base, top, height, impactTick, releaseTick, actionSeq;
        private byte[] original;
        private Entity bomb;
        private Vec3 impact;
        private final List<Entity> golems = new ArrayList<>();
        private final List<String> golemRoles = new ArrayList<>();
        private final List<Double> tickTimes = new ArrayList<>();
        private final List<Long> gameTimes = new ArrayList<>();
        private boolean pass = true;

        KabCraters(ServerPlayer observer, boolean include1500) {
            this.observer = observer;
            this.level = observer.serverLevel();
            this.include1500 = include1500;
            if (observer.isPassenger()) observer.stopRiding();
            observer.setGameMode(GameType.SPECTATOR);
            ImpactObservations.clear();
            clearObservations();
            recording = true;
            nextSite();
        }

        private Spec spec() { return SITES[site]; }

        private void nextSite() {
            site++;
            stage = 0; stageTicks = 0; layer = 0;
            golems.clear(); golemRoles.clear(); tickTimes.clear(); gameTimes.clear();
            bomb = null; impact = null;
            if (site >= SITES.length || (!include1500 && SITES[site].variant > 0)) return;
            Spec s = spec();
            cx = 400 + site * 160;
            cz = 0;
            base = ground(level, cx, cz);
            height = s.depth + 12;
            int w = 2 * s.half + 1;
            original = new byte[w * w * height];
            force(true);
            observer.connection.teleport(cx + .5, base + s.depth + 45, cz - 75.5, 0, 28);
        }

        private void force(boolean on) {
            int half = spec().half + 16;
            for (int x = (cx - half) >> 4; x <= (cx + half) >> 4; x++)
                for (int z = (cz - half) >> 4; z <= (cz + half) >> 4; z++) level.setChunkForced(x, z, on);
        }

        private int index(int dx, int dy, int dz) {
            int w = 2 * spec().half + 1;
            return ((dx + spec().half) * height + dy) * w + (dz + spec().half);
        }

        private void put(int dx, int dy, int dz, BlockState state, byte kind) {
            level.setBlock(new BlockPos(cx + dx, base + dy, cz + dz), state, 2);
            original[index(dx, dy, dz)] = kind;
        }

        /** Builds a few layers per tick; true when the site is complete. */
        private boolean buildLayers() {
            Spec s = spec();
            int perTick = s.half > 20 ? 2 : 6;
            for (int n = 0; n < perTick && layer < height; n++, layer++) {
                int dy = layer;
                for (int dx = -s.half; dx <= s.half; dx++) for (int dz = -s.half; dz <= s.half; dz++) {
                    BlockState state = Blocks.AIR.defaultBlockState();
                    byte kind = 0;
                    boolean inner = Math.abs(dx) <= s.half - 2 && Math.abs(dz) <= s.half - 2;
                    if (dy < s.depth) {
                        kind = 1;
                        state = Blocks.STONE.defaultBlockState();
                        if (s.kind.equals("ground") && dy >= s.depth - 3) state = dy == s.depth - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                        if (s.kind.equals("water") && inner && dy >= s.depth - 7) { state = Blocks.WATER.defaultBlockState(); kind = 2; }
                    } else if (s.kind.equals("building") && Math.abs(dx) <= 5 && Math.abs(dz) <= 5) {
                        int up = dy - s.depth;
                        boolean edge = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                        if (up <= 7 && edge) { state = Blocks.BRICKS.defaultBlockState(); kind = 1; }
                        else if (up == 4) { state = Blocks.OAK_PLANKS.defaultBlockState(); kind = 1; }
                        else if (up == 8) { state = Blocks.BRICKS.defaultBlockState(); kind = 1; }
                    }
                    put(dx, dy, dz, state, kind);
                }
            }
            return layer >= height;
        }

        private void golem(String role, int dx, int dy, int dz, boolean carve) {
            if (carve) for (int x = -1; x <= 1; x++) for (int y = 0; y <= 2; y++) for (int z = -1; z <= 1; z++)
                put(dx + x, dy + y, dz + z, Blocks.AIR.defaultBlockState(), (byte) 0);
            Entity golem = create(level, "minecraft:cow");
            golem.moveTo(cx + dx + .5, base + dy, cz + dz + .5, 0, 0);
            if (!level.addFreshEntity(golem)) throw new IllegalStateException("golem rejected");
            golems.add(golem);
            golemRoles.add(role);
        }

        @Override public boolean tick() {
            ticks++;
            if (site >= SITES.length || (!include1500 && SITES[site].variant > 0)) return finish();
            stageTicks++;
            Spec s = spec();
            switch (stage) {
                case 0 -> { if (buildLayers()) { stage = 1; stageTicks = 0; } }
                case 1 -> {
                    top = base + s.depth + (s.kind.equals("building") ? 9 : 0);
                    int topDy = top - base;
                    int burst = s.variant == 0 ? 6 : 15;
                    if (s.kind.equals("ground")) {
                        golem("cavity_near_penetrator", 2, topDy - burst, 0, true);
                        golem("surface", s.variant == 0 ? 8 : 20, topDy, 0, false);
                    } else if (s.kind.equals("building")) {
                        golem("inside_building_ground_floor", 2, s.depth, 2, false);
                        golem("surface_outside", 9, s.depth, 0, false);
                    }
                    stage = 2; stageTicks = 0;
                }
                case 2 -> {
                    if (stageTicks < 20) return false;
                    KabEntity kab = GuidedOrdnance.KAB_500.get().create(level);
                    if (kab == null) return fail("no KAB");
                    kab.setVariant(s.variant);
                    Entity body = kab;
                    body.setPos(cx + .5, top + 60, cz + .5);
                    body.setDeltaMovement(0, -2.5, 0);
                    ((net.minecraft.world.entity.projectile.Projectile) (Object) kab).setOwner(observer);
                    kab.setGuideType(1);
                    kab.setTargetUUID("none");
                    kab.setTargetPos(new Vec3(cx + .5, top, cz + .5));
                    DETONATIONS.clear();
                    LIVING_HURTS.clear();
                    if (!level.addFreshEntity(body)) return fail("KAB rejected");
                    bomb = body;
                    releaseTick = ticks;
                    stage = 3; stageTicks = 0;
                }
                case 3 -> {
                    tickTimes.add(tickMs(level.getServer()));
                    gameTimes.add(level.getGameTime());
                    if (bomb.isRemoved() && impact == null) { impact = bomb.position(); impactTick = ticks; }
                    if (impact != null && ticks - impactTick >= 120) { measure(); stage = 4; stageTicks = 0; }
                    else if (stageTicks > 600) return fail("KAB never detonated");
                }
                case 4 -> {
                    if (stageTicks == 1) {
                        observer.connection.teleport(cx + .5, top + 28, cz - 38.5, 0, 38);
                        actionSeq = requestActions("sleep 1.5", "key F1", "shot kab-" + s.name, "key F1");
                    }
                    if (actionsDone(actionSeq) || stageTicks > 400) {
                        for (var golem : golems) golem.discard();
                        force(false);
                        nextSite();
                    }
                }
                default -> { }
            }
            return ticks > 9000 && fail("kab timeout");
        }

        private void measure() {
            Spec s = spec();
            var row = new JsonObject();
            row.addProperty("variant", s.variant == 0 ? "KAB-500" : "KAB-1500");
            row.addProperty("surfaceY", top);
            row.add("impact", vec(impact));
            row.addProperty("ticksToImpact", impactTick - releaseTick);
            int brokenSolid = 0, minAll = Integer.MAX_VALUE, minCentre = Integer.MAX_VALUE, buildingBroken = 0;
            int w = 2 * s.half + 1;
            for (int dx = -s.half; dx <= s.half; dx++) for (int dy = 0; dy < height; dy++) for (int dz = -s.half; dz <= s.half; dz++) {
                if (original[index(dx, dy, dz)] != 1) continue;
                BlockState now = level.getBlockState(new BlockPos(cx + dx, base + dy, cz + dz));
                if (!now.isAir() && now.getFluidState().isEmpty()) continue;
                brokenSolid++;
                if (dy >= s.depth) buildingBroken++;
                int y = base + dy;
                minAll = Math.min(minAll, y);
                double hx = cx + dx + .5 - impact.x, hz = cz + dz + .5 - impact.z;
                if (hx * hx + hz * hz <= 2.25) minCentre = Math.min(minCentre, y);
            }
            int groundTop = base + s.depth; // top of the base terrain (building floor, basin rim)
            row.addProperty("solidBlocksBroken", brokenSolid);
            if (s.kind.equals("building")) row.addProperty("buildingBlocksBroken", buildingBroken);
            int reference = s.kind.equals("building") ? top : s.kind.equals("water") ? groundTop - 7 : top;
            row.addProperty("craterReferenceY", reference);
            row.addProperty("craterDepthCentre", minCentre == Integer.MAX_VALUE ? 0 : reference - minCentre);
            row.addProperty("craterDepthMax", minAll == Integer.MAX_VALUE ? 0 : reference - minAll);
            if (s.kind.equals("building")) row.addProperty("craterDepthBelowBuildingFloor", minCentre == Integer.MAX_VALUE ? 0 : Math.max(0, groundTop - minCentre));
            var dets = new JsonArray();
            int kabDets = 0;
            for (Det det : DETONATIONS) {
                if (det.source != bomb) continue;
                kabDets++;
                var d = new JsonObject();
                d.add("pos", vec(det.pos));
                d.addProperty("tick", det.tick);
                int idx = gameTimes.indexOf(det.tick);
                if (idx >= 0) d.addProperty("serverTickMsOfThisTick", round(tickTimes.get(idx)));
                d.addProperty("blocks", det.blocks.size());
                int low = Integer.MAX_VALUE;
                for (var pos : det.blocks) {
                    double hx = pos.getX() + .5 - impact.x, hz = pos.getZ() + .5 - impact.z;
                    if (hx * hx + hz * hz <= 2.25) low = Math.min(low, pos.getY());
                }
                d.addProperty("centreDepthReached", low == Integer.MAX_VALUE ? 0 : reference - low);
                var names = new JsonArray();
                det.entities.forEach(names::add);
                d.add("affectedEntities", names);
                dets.add(d);
            }
            row.add("kabDetonations", dets);
            boolean penetratorClean = kabDets < 2 || ((JsonObject) dets.get(1)).getAsJsonArray("affectedEntities").size() == 0;
            row.addProperty("penetratorAffectedEntitiesEmpty", penetratorClean);
            var others = new JsonArray();
            for (Det det : DETONATIONS) if (det.source != bomb) others.add(det.source == null ? "null" : String.valueOf(EntityType.getKey(det.source.getType())));
            row.add("otherDetonations", others);
            var golemRows = new JsonArray();
            for (int i = 0; i < golems.size(); i++) {
                var g = new JsonObject();
                g.addProperty("role", golemRoles.get(i));
                var hurts = hurtsOf(LIVING_HURTS, golems.get(i));
                g.addProperty("hurtEvents", hurts.size());
                g.add("hurts", hurts);
                g.addProperty("alive", golems.get(i).isAlive());
                g.addProperty("removal", String.valueOf(golems.get(i).getRemovalReason()));
                golemRows.add(g);
            }
            row.add("golems", golemRows);
            double max = 0, sum = 0;
            int at = 0;
            for (int i = 0; i < tickTimes.size(); i++) { sum += tickTimes.get(i); if (tickTimes.get(i) > max) { max = tickTimes.get(i); at = i; } }
            row.addProperty("tickMsMax", round(max));
            row.addProperty("tickMsMaxAtTicksAfterRelease", at + 1);
            row.addProperty("tickMsMean", round(sum / Math.max(1, tickTimes.size())));
            int impactIndex = impactTick - releaseTick - 1;
            var around = new JsonArray();
            for (int i = Math.max(0, impactIndex - 1); i < Math.min(tickTimes.size(), impactIndex + 8); i++) around.add(round(tickTimes.get(i)));
            row.add("tickMsAroundImpact", around);
            result.add(s.name, row);
            pass &= penetratorClean && brokenSolid > 0;
        }

        private boolean fail(String error) {
            result.addProperty("error", (spec() == null ? "" : spec().name + ": ") + error);
            pass = false;
            return finish();
        }

        private boolean finish() {
            recording = false;
            observer.setGameMode(GameType.CREATIVE);
            result.addProperty("pass", pass);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ================================================================ 3. mid-air
    /** Driven aircraft pairs on crossing / head-on paths, then parked and taxiing pairs. */
    static final class MidAir implements Scenario {
        private final ServerPlayer observer;
        private final ServerLevel level;
        private final JsonObject result = new JsonObject();
        private int ticks, part = -1, partTicks, cx, cz, y;
        private VehicleEntity a, b;
        private Vec3 startA, startB, dirA, dirB;
        private double speedA, speedB;
        private boolean driving, contact;
        private int contactTick;
        private long strikesBefore;
        private float a0, b0;

        MidAir(ServerPlayer observer) {
            this.observer = observer;
            this.level = observer.serverLevel();
            if (observer.isPassenger()) observer.stopRiding();
            observer.setGameMode(GameType.SPECTATOR);
            clearObservations();
            recording = true;
            ExplosionObservations.clear();
            nextPart();
        }

        private VehicleEntity spawn(AircraftProfile profile, Vec3 at, float yaw, boolean air) {
            VehicleEntity plane = profile.entity.get().create(level);
            if (plane == null) throw new IllegalStateException("aircraft creation failed");
            Entity body = plane;
            body.moveTo(at.x, at.y, at.z, yaw, 0);
            body.setNoGravity(air);
            plane.setEnergy(plane.getMaxEnergy());
            if (!level.addFreshEntity(body)) throw new IllegalStateException("aircraft rejected");
            return plane;
        }

        private void nextPart() {
            part++;
            partTicks = 0;
            driving = false;
            contact = false;
            if (part > 2) return;
            b = null;
            cx = -400;
            cz = part * 200;
            int g = ground(level, cx, cz);
            y = part < 2 ? g + 90 : g;
            observer.connection.teleport(cx + .5, (part < 2 ? y + 15 : y + 12), cz - 40.5, 0, part < 2 ? 18 : 15);
            Vec3 c = new Vec3(cx + .5, y, cz + .5);
            if (part == 0) { // F-16 x F-16 crossing at right angles, 4 blocks/tick each
                dirA = new Vec3(1, 0, 0); dirB = new Vec3(0, 0, 1); speedA = speedB = 4;
                startA = c.subtract(dirA.scale(speedA * 25)); startB = c.subtract(dirB.scale(speedB * 25));
                a = spawn(AircraftProfile.F16, startA, -90, true);
                b = spawn(AircraftProfile.F16, startB, 0, true);
                driving = true;
            } else if (part == 1) { // Su-25 x J-10 head-on, 3 blocks/tick each
                dirA = new Vec3(0, 0, 1); dirB = new Vec3(0, 0, -1); speedA = speedB = 3;
                startA = c.subtract(dirA.scale(speedA * 25)); startB = c.subtract(dirB.scale(speedB * 25));
                a = spawn(AircraftProfile.SU25, startA, 0, true);
                b = spawn(AircraftProfile.J10, startB, 180, true);
                driving = true;
            } else { // parked F-16 pair with overlapping wings; then one taxies into the other
                a = spawn(AircraftProfile.F16, c, 0, false);
                b = null;
            }
            strikesBefore = MidAirCollisions.strikes;
            a0 = a.getHealth();
            b0 = b == null ? 0 : b.getHealth();
        }

        @Override public boolean tick() {
            ticks++;
            if (part > 2) return finish(null);
            partTicks++;
            if (part == 2 && b == null) {
                if (partTicks < 2) return false;
                // Same type and yaw: B's hit volume starts 1.5 blocks inside A's (wings overlap); spawned there, never teleported.
                AABB boxA = bounds(a);
                Entity bodyA0 = a;
                b = spawn(AircraftProfile.F16, new Vec3(bodyA0.getX() + (boxA.maxX - 1.5) - boxA.minX, bodyA0.getY(), bodyA0.getZ()), 0, false);
            }
            Entity bodyA = a, bodyB = b;
            if (part < 2) {
                if (driving) {
                    Vec3 pa = startA.add(dirA.scale(speedA * partTicks)), pb = startB.add(dirB.scale(speedB * partTicks));
                    bodyA.setDeltaMovement(dirA.scale(speedA));
                    bodyB.setDeltaMovement(dirB.scale(speedB));
                    if (!bodyA.isRemoved()) bodyA.setPos(pa.x, pa.y, pa.z);
                    if (!bodyB.isRemoved()) bodyB.setPos(pb.x, pb.y, pb.z);
                }
                if (!contact && (a.getHealth() < a0 || b.getHealth() < b0 || bodyA.isRemoved() || bodyB.isRemoved())) {
                    contact = true;
                    contactTick = partTicks;
                    driving = false;
                    if (!bodyA.isRemoved()) bodyA.setNoGravity(false);
                    if (!bodyB.isRemoved()) bodyB.setNoGravity(false);
                }
                if ((contact && partTicks - contactTick >= 80) || partTicks >= 140) { record(part == 0 ? "crossing_f16_f16" : "headon_su25_j10"); cleanup(); nextPart(); }
            } else {
                // 0-40 settle, 40-120 parked touching, 120-180 B taxies 0.3 b/t into A.
                if (partTicks == 40) {
                    AABB boxA = bounds(a), boxB = bounds(b);
                    result.addProperty("parkedOverlapX", round(boxA.maxX - boxB.minX));
                    a0 = a.getHealth();
                    b0 = b.getHealth();
                    result.addProperty("parkedOnGround", bodyA.onGround() && bodyB.onGround());
                }
                if (partTicks == 120) {
                    var parked = new JsonObject();
                    parked.addProperty("healthA", a.getHealth() + "/" + a0);
                    parked.addProperty("healthB", b.getHealth() + "/" + b0);
                    parked.addProperty("strikes", MidAirCollisions.strikes - strikesBefore);
                    parked.addProperty("hurtEventsA", hurtsOf(VEHICLE_HURTS, a).size());
                    parked.addProperty("hurtEventsB", hurtsOf(VEHICLE_HURTS, b).size());
                    result.add("parked_touching", parked);
                }
                if (partTicks > 120 && partTicks <= 180) {
                    bodyB.setPos(bodyB.getX() - .3, bodyB.getY(), bodyB.getZ());
                    bodyB.setDeltaMovement(-.3, bodyB.getDeltaMovement().y, 0);
                }
                if (partTicks == 200) {
                    var taxi = new JsonObject();
                    taxi.addProperty("healthA", a.getHealth() + "/" + a0);
                    taxi.addProperty("healthB", b.getHealth() + "/" + b0);
                    taxi.addProperty("strikes", MidAirCollisions.strikes - strikesBefore);
                    taxi.addProperty("hurtEventsA", hurtsOf(VEHICLE_HURTS, a).size());
                    taxi.addProperty("hurtEventsB", hurtsOf(VEHICLE_HURTS, b).size());
                    taxi.addProperty("bothOnGround", bodyA.onGround() && bodyB.onGround());
                    result.add("taxi_bump_0_3", taxi);
                    cleanup();
                    nextPart();
                }
            }
            return ticks > 2000 && finish("midair timeout");
        }

        private void record(String name) {
            var row = new JsonObject();
            row.addProperty("contact", contact);
            row.addProperty("contactTick", contactTick);
            row.addProperty("strikes", MidAirCollisions.strikes - strikesBefore);
            for (var entry : Map.of("A", a, "B", b).entrySet()) {
                var p = new JsonObject();
                VehicleEntity v = entry.getValue();
                Entity body = v;
                p.addProperty("type", String.valueOf(EntityType.getKey(body.getType())));
                p.addProperty("healthBefore", entry.getKey().equals("A") ? a0 : b0);
                p.addProperty("healthAfter", v.getHealth());
                p.addProperty("wreck", v.isWreck());
                p.addProperty("removed", body.isRemoved());
                p.addProperty("nativeVehicleExplosions", ExplosionObservations.count(body.getUUID()));
                var hurts = hurtsOf(VEHICLE_HURTS, body);
                p.addProperty("hurtEvents", hurts.size());
                var sample = new JsonArray();
                for (int i = 0; i < Math.min(5, hurts.size()); i++) sample.add(hurts.get(i));
                p.add("hurts", sample);
                row.add(entry.getKey(), p);
            }
            result.add(name, row);
        }

        private void cleanup() {
            ((Entity) a).discard();
            ((Entity) b).discard();
        }

        private boolean finish(String error) {
            recording = false;
            observer.setGameMode(GameType.CREATIVE);
            if (error != null) result.addProperty("error", error);
            boolean pass = error == null;
            for (String name : new String[]{"crossing_f16_f16", "headon_su25_j10"}) {
                var row = result.getAsJsonObject(name);
                pass &= row != null && row.get("contact").getAsBoolean();
                if (row != null) for (String k : new String[]{"A", "B"})
                    pass &= row.getAsJsonObject(k).get("healthAfter").getAsFloat() < row.getAsJsonObject(k).get("healthBefore").getAsFloat();
            }
            var parked = result.getAsJsonObject("parked_touching");
            pass &= parked != null && parked.get("strikes").getAsLong() == 0;
            var taxi = result.getAsJsonObject("taxi_bump_0_3");
            pass &= taxi != null && taxi.get("strikes").getAsLong() == 0;
            result.addProperty("pass", pass);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ================================================================ 4. FPV vs jets
    /** Armed FPV hovering in a jet's path; FPV flown into a parked jet; FPV flown into a crewed parked jet. */
    static final class FpvJets implements Scenario {
        private static final String OWNER = "frontierIndustryVehicleOwner";
        private final ServerPlayer player;
        private final ServerLevel level;
        private final JsonObject result = new JsonObject();
        private int ticks, part = -1, partTicks, gone = -1;
        private VehicleEntity jet;
        private Entity drone;
        private Vec3 hover, jetStart, aimPoint;
        private float jet0;
        private long strikesBefore, terrainStrikesBefore;
        private boolean crewed;

        FpvJets(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.SPECTATOR);
            clearObservations();
            recording = true;
            nextPart();
        }

        private Entity armedDrone(Vec3 at) {
            Entity entity = create(level, "uncomplicatedfpv:fpv_drone");
            if (!(entity instanceof com.atsuishio.superbwarfare.entity.vehicle.DroneEntity drone))
                throw new IllegalStateException("FPV is not a native drone: " + entity.getClass());
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare", "rpg_rocket_standard"));
            drone.setCurrentItem(new ItemStack(item));
            drone.setAmmo(1);
            drone.setEnergy(drone.getMaxEnergy());
            entity.getPersistentData().putUUID(OWNER, player.getUUID());
            entity.moveTo(at.x, at.y, at.z, 0, 0);
            entity.setNoGravity(true);
            if (!level.addFreshEntity(entity)) throw new IllegalStateException("FPV rejected");
            result.addProperty("fpvPayload", com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.getItemId(drone.getCurrentItem()) + " x" + drone.getAmmo());
            return entity;
        }

        private void nextPart() {
            part++;
            partTicks = 0;
            gone = -1;
            crewed = false;
            if (part > 2) return;
            int cx = -400, cz = 800 + part * 200;
            int g = ground(level, cx, cz);
            strikesBefore = MidAirCollisions.strikes;
            terrainStrikesBefore = dev.frontier.aviation.impact.AircraftCollisions.strikes;
            if (part == 0) {
                hover = new Vec3(cx + .5, g + 60, cz + .5);
                player.connection.teleport(cx + .5, g + 66, cz - 30.5, 0, 12);
                jetStart = hover.add(-100, 0, 0);
                jet = AircraftProfile.F16.entity.get().create(level);
                Entity body = jet;
                body.moveTo(jetStart.x, jetStart.y, jetStart.z, -90, 0);
                body.setNoGravity(true);
                jet.setEnergy(jet.getMaxEnergy());
                level.addFreshEntity(body);
            } else {
                player.connection.teleport(cx + .5, g + 8, cz - 25.5, 0, 12);
                jet = AircraftProfile.F16.entity.get().create(level);
                Entity body = jet;
                body.moveTo(cx + .5, g, cz + .5, 0, 0);
                jet.setEnergy(jet.getMaxEnergy());
                level.addFreshEntity(body);
            }
        }

        @Override public boolean tick() {
            ticks++;
            if (part > 2) return finish(null);
            partTicks++;
            Entity body = jet;
            if (part == 0) {
                if (partTicks == 2) {
                    // Align the jet's real hit volume with the hover point, then place the drone.
                    double dy = hover.y - bounds(jet).getCenter().y;
                    jetStart = jetStart.add(0, dy, 0);
                    drone = armedDrone(hover);
                    jet0 = jet.getHealth();
                }
                if (partTicks > 2) {
                    if (drone.isAlive()) { drone.setPos(hover.x, hover.y, hover.z); drone.setDeltaMovement(0, 0, 0); }
                    else if (gone < 0) gone = partTicks;
                    if (!body.isRemoved() && partTicks < 80) {
                        Vec3 p = jetStart.add(4.0 * (partTicks - 2), 0, 0);
                        body.setDeltaMovement(4, 0, 0);
                        body.setPos(p.x, p.y, p.z);
                    }
                }
                if (partTicks >= 100) { record("hovering_fpv_vs_jet_4bpt"); cleanup(); nextPart(); }
            } else {
                if (partTicks == 40) {
                    if (part == 2 && !body.isRemoved()) {
                        player.setGameMode(GameType.CREATIVE);
                        crewed = player.startRiding(body, true);
                        result.addProperty("crewSeatEnclosed", crewed && jet.isEnclosed(player));
                    }
                    jet0 = jet.getHealth();
                    Vec3 centre = bounds(jet).getCenter();
                    aimPoint = part == 2 && crewed ? player.getEyePosition() : centre;
                    drone = armedDrone(aimPoint.add(-14, 0, 0));
                }
                if (partTicks > 40) {
                    if (drone.isAlive() && partTicks < 120) {
                        Vec3 dir = aimPoint.subtract(drone.position()).normalize();
                        drone.setDeltaMovement(dir.scale(1.0));
                    } else if (!drone.isAlive() && gone < 0) gone = partTicks;
                }
                if (partTicks >= 150) {
                    record(part == 1 ? "fpv_into_parked_jet" : "fpv_into_crewed_parked_jet");
                    if (crewed) { player.stopRiding(); player.setGameMode(GameType.SPECTATOR); }
                    cleanup();
                    nextPart();
                }
            }
            return ticks > 1200 && finish("fpv timeout");
        }

        private void record(String name) {
            var row = new JsonObject();
            Entity body = jet;
            row.addProperty("droneDetonatedOrRemoved", drone == null || !drone.isAlive());
            row.addProperty("droneGoneAtPartTick", gone);
            row.addProperty("jetHealthBefore", jet0);
            row.addProperty("jetHealthAfter", jet.getHealth());
            row.addProperty("jetRemoved", body.isRemoved());
            row.addProperty("midAirStrikes", MidAirCollisions.strikes - strikesBefore);
            row.addProperty("aircraftCollisionsStrikes", dev.frontier.aviation.impact.AircraftCollisions.strikes - terrainStrikesBefore);
            var hurts = hurtsOf(VEHICLE_HURTS, body);
            int damaging = 0;
            for (var e : hurts) {
                var h = e.getAsJsonObject();
                if (h.get("healthAfter").getAsFloat() < h.get("healthBefore").getAsFloat()) damaging++;
            }
            row.addProperty("jetHurtCalls", hurts.size());
            row.addProperty("jetDamagingHurtCalls", damaging);
            row.add("jetHurts", hurts);
            if (drone != null) row.add("droneHurts", hurtsOf(VEHICLE_HURTS, drone));
            if (crewed) row.add("crewHurts", hurtsOf(LIVING_HURTS, player));
            result.add(name, row);
        }

        private void cleanup() {
            if (drone != null && drone.isAlive()) drone.discard();
            ((Entity) jet).discard();
            drone = null;
        }

        private boolean finish(String error) {
            recording = false;
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            if (error != null) result.addProperty("error", error);
            boolean pass = error == null;
            for (String name : new String[]{"hovering_fpv_vs_jet_4bpt", "fpv_into_parked_jet"}) {
                var row = result.getAsJsonObject(name);
                pass &= row != null && row.get("jetHealthAfter").getAsFloat() < row.get("jetHealthBefore").getAsFloat()
                    && row.get("jetDamagingHurtCalls").getAsInt() >= 1;
            }
            result.addProperty("pass", pass);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ================================================================ 6. seeker self-lock
    /** Real client seeker state (SBW ClientEventHandler.nearestEntityVehicle) in first and third person. */
    static final class SeekSelf implements Scenario {
        private final ServerPlayer pilot;
        private final ServerLevel level;
        private final VehicleEntity plane;
        private final ControlLease lease;
        private final JsonObject result = new JsonObject();
        private Entity target;
        private int ticks, step, stepTicks, seq;
        private final String[] stages = {"first_no_target", "third_no_target", "third_target", "first_target"};

        SeekSelf(ServerPlayer pilot) {
            this.pilot = pilot;
            this.level = pilot.serverLevel();
            if (pilot.isPassenger()) pilot.stopRiding();
            pilot.setGameMode(GameType.CREATIVE);
            int g = ground(level, 0, -400);
            pilot.teleportTo(level, .5, g + 60, -399.5, 0, 0);
            plane = AircraftProfile.F16.entity.get().create(level);
            Entity body = plane;
            body.moveTo(.5, g + 60, -399.5, 0, 0);
            body.setNoGravity(true);
            body.setInvulnerable(true);
            plane.setEnergy(plane.getMaxEnergy());
            if (!level.addFreshEntity(body) || !pilot.startRiding(body, true)) throw new IllegalStateException("seek mount failed");
            lease = AircraftController.acquire(pilot, plane).orElseThrow();
            result.addProperty("pass", false);
        }

        private void request(String stage) {
            var request = new JsonObject();
            request.addProperty("runId", runId());
            request.addProperty("stage", stage);
            request.addProperty("self", ((Entity) plane).getId());
            request.addProperty("target", target == null ? -1 : target.getId());
            try { Files.writeString(game().resolve("aircraft-seek-request.json"), request.toString()); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }

        @Override public boolean tick() {
            ticks++;
            stepTicks++;
            Entity body = plane;
            AircraftController.submit(pilot, lease, PilotCommand.neutral(4_000_000L + ticks));
            body.setDeltaMovement(0, 0, 0);
            if (ticks == 5) {
                var seat = plane.getSeat(0);
                result.addProperty("seatWeapons", seat == null ? "none" : String.valueOf(seat.weapons()));
                for (String gun : new String[]{"AAMissile", "LightAAMissile"}) {
                    var data = plane.getGunData(gun);
                    if (data != null) data.ammo.set(2);
                }
                boolean selected = AircraftArmament.select(pilot, plane, lease, "aamissile");
                if (!selected) selected = AircraftArmament.select(pilot, plane, lease, "light_aamissile");
                result.addProperty("aamInspect", String.valueOf(AircraftArmament.inspect(pilot, plane, lease, "aamissile")));
                if (!selected) return finish("AAM selection failed");
                result.addProperty("selectedGun", plane.getGunName(0));
                request(stages[0]);
                stepTicks = 0;
            }
            if (ticks < 5) return false;
            if (target != null) target.setDeltaMovement(0, 0, 0);
            if (step < stages.length && stepTicks == 60) {
                step++;
                if (step == 1) seq = requestActions("key F5", "sleep 0.5");
                if (step == 2) {
                    target = AircraftProfile.F16.entity.get().create(level);
                    target.moveTo(body.getX(), body.getY() + 6, body.getZ() + 120, 0, 0);
                    target.setNoGravity(true);
                    target.setInvulnerable(true);
                    level.addFreshEntity(target);
                }
                if (step == 3) seq = requestActions("shot seek-third-target", "key F5", "key F5", "sleep 0.5");
                if (step < stages.length) request(stages[step]);
                stepTicks = 0;
            }
            if (step >= stages.length) {
                try {
                    var file = game().resolve("aircraft-seek-client.json");
                    if (Files.isRegularFile(file)) {
                        var data = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                        result.add("client", data);
                        boolean pass = data.has("stages");
                        if (pass) {
                            var st = data.getAsJsonObject("stages");
                            for (String s : stages) pass &= st.has(s) && st.getAsJsonObject(s).get("self").getAsInt() == 0
                                && st.getAsJsonObject(s).get("samples").getAsInt() > 10;
                            pass &= st.has("third_target") && st.getAsJsonObject("third_target").get("target").getAsInt() > 0;
                        }
                        result.addProperty("pass", pass);
                    }
                } catch (Exception failure) { result.addProperty("readError", failure.toString()); }
                return finish(null);
            }
            return ticks > 600 && finish("seek timeout");
        }

        private boolean finish(String error) {
            if (error != null) result.addProperty("error", error);
            try { Files.deleteIfExists(game().resolve("aircraft-seek-request.json")); } catch (java.io.IOException ignored) { }
            AircraftController.release(pilot, lease);
            land(pilot);
            ((Entity) plane).discard();
            if (target != null) target.discard();
            return true;
        }

        @Override public JsonObject result() { return result; }
    }

    // ================================================================ 5. worker look
    /** Hires a builder through Industry's own path and asks the runner for client screenshots. */
    static final class WorkerLook implements Scenario {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final JsonObject result = new JsonObject();
        private Entity worker;
        private int ticks, seq;
        private final int x, z, g;

        WorkerLook(ServerPlayer player) {
            this.player = player;
            this.level = player.serverLevel();
            if (player.isPassenger()) player.stopRiding();
            player.setGameMode(GameType.CREATIVE);
            player.fallDistance = 0;
            x = -150; z = -150;
            g = ground(level, x, z);
            player.connection.teleport(x + .5, g, z + .5, 0, 0);
            result.addProperty("pass", false);
        }

        @Override public boolean tick() {
            ticks++;
            if (ticks == 10) {
                player.getInventory().add(new ItemStack(Items.EMERALD, 4));
                try {
                    Class.forName("dev.frontier.industry.construction.Construction")
                        .getMethod("hireBuilder", ServerPlayer.class).invoke(null, player);
                } catch (ReflectiveOperationException failure) { return finish("hireBuilder: " + failure); }
            }
            if (ticks == 15) {
                for (Entity e : level.getEntities((Entity) null, new AABB(x - 6, g - 2, z - 6, x + 7, g + 4, z + 7),
                        e -> "frontier_industry:worker".equals(String.valueOf(EntityType.getKey(e.getType()))))) worker = e;
                if (worker == null) return finish("no hired worker spawned");
                result.addProperty("workerClass", worker.getClass().getName());
                result.addProperty("name", worker.getName().getString());
                result.addProperty("mainHand", String.valueOf(ForgeRegistries.ITEMS.getKey(((LivingEntity) worker).getMainHandItem().getItem())));
                result.addProperty("emeraldsLeft", player.getInventory().countItem(Items.EMERALD));
            }
            if (worker != null && worker.isAlive()) {
                worker.setPos(x + .5, g, z + 3.5);
                worker.setYRot(180); worker.setYHeadRot(180);
                if (worker instanceof LivingEntity living) living.yBodyRot = 180;
            }
            if (ticks == 20) {
                player.connection.teleport(x + .5, g, z + .5, 0, 12);
                seq = requestActions("sleep 1.5", "key F1", "shot worker-front", "tp -146.5 " + g + " -146.5 90 15", "sleep 1.5",
                    "shot worker-side", "tp -150.5 " + (g + 1) + " -143.5 180 18", "sleep 1.5", "shot worker-back", "key F1");
            }
            if (ticks > 20 && actionsDone(seq)) {
                result.addProperty("pass", worker.isAlive() && result.get("mainHand").getAsString().endsWith("pickaxe"));
                return finish(null);
            }
            return ticks > 900 && finish("worker screenshot timeout");
        }

        private boolean finish(String error) {
            if (error != null) result.addProperty("error", error);
            player.setGameMode(GameType.CREATIVE);
            return true;
        }

        @Override public JsonObject result() { return result; }
    }
}
