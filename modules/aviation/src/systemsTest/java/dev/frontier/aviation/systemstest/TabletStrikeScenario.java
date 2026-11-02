package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.weapons.GlideBombRackView;
import dev.frontier.aviation.weapons.KabEntity;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Tablet strike flow driven through the real client UI by the runner, the same on
 * every aircraft: while parked the pilot loads a КАБ-500 on pylon 1 and a real Storm
 * Shadow on pylon 2; an outside camera then looks at the loaded pylons; in flight the
 * pilot types a target and presses ПУСК for the КАБ, retypes a second target 100 m east
 * and presses ПУСК for the Storm Shadow; a chase camera follows the falling КАБ and a
 * last camera looks at the craters. Blocks destroyed around each target are counted.
 * This class only stages the aircraft/cameras and observes; every weapon action comes
 * from clicks on the tablet (runner: tools/run_aircraft_systems_checks.py).
 */
final class TabletStrikeScenario {
    static final int TIMEOUT_TICKS = 3600;
    private static final double ALTITUDE = 70, AHEAD = 100, SECOND_TARGET_EAST = 100, SPEED = 1.8;
    private static final int CRATER_RADIUS = 6;
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final JsonObject result = new JsonObject();
    private final Vec3 ground;
    private final List<Entity> storms = new ArrayList<>();
    private ControlLease lease;
    private Vec3 target, secondTarget;
    private int solidBefore, secondSolidBefore, zoneBefore;
    private final java.util.ArrayDeque<Vec3> serverTrail = new java.util.ArrayDeque<>();
    private static final int ZONE_RADIUS = 72;
    private String stage = "parked";
    private Entity kab;
    private GameType previousMode;
    private int ticks, stageTicks;

    TabletStrikeScenario(ServerPlayer pilot, AircraftProfile profile) {
        this.pilot = pilot;
        var level = pilot.serverLevel();
        if (pilot.isPassenger()) throw new IllegalStateException("pilot already mounted");
        ImpactObservations.clear();
        result.addProperty("aircraft", profile.id);
        plane = profile.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("no " + profile.id);
        result.addProperty("noWheelGuidedBomb", plane.getGunData("GuidedBomb") == null);
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pilot.getBlockX(), pilot.getBlockZ());
        ground = new Vec3(pilot.getBlockX() + .5, groundY, pilot.getBlockZ() + .5);
        plane.moveTo(ground.x, ground.y, ground.z, 0, 0);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        if (!level.addFreshEntity(plane) || !pilot.startRiding(plane, true)) throw new IllegalStateException("mount failed");
        var inventory = pilot.getInventory();
        inventory.clearContent();
        // Su-25 carries a КАБ-500 (medium bomb); F-16 a КАБ-3000 (two large bombs, one spare).
        heavy = profile == AircraftProfile.F16;
        inventory.add(new ItemStack(bombItem(), heavy ? 3 : 2));
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("deepstrike", "storm_shadow"));
        if (item == null) throw new IllegalStateException("no Storm Shadow item");
        ItemStack missile = new ItemStack(item);
        new MissileData(MissileKind.STORM_SHADOW, MissileData.WARHEAD_HE, MissileData.ENGINE_TURBOJET,
            List.of(), MissileData.CAMO_DEFAULT, 1000, 1000).writeToItemStack(missile);
        inventory.add(missile);
        result.addProperty("pass", false);
        progress("{\"stage\":\"parked\",\"vehicle\":" + plane.getId() + "}");
    }

    private boolean heavy;

    private Item bombItem() {
        return heavy ? ModItems.LARGE_AERIAL_BOMB.get() : ModItems.MEDIUM_AERIAL_BOMB.get();
    }

    boolean tick() {
        ticks++;
        stageTicks++;
        var su = (Su24mEntity) (Object) plane;
        if (ticks > TIMEOUT_TICKS - 20) return finish("timed out in stage " + stage);
        switch (stage) {
            case "parked" -> {
                plane.setDeltaMovement(Vec3.ZERO);
                if (su.loaded(0) && su.loaded(1)) {
                    result.addProperty("kabMask", ((GlideBombRackView) (Object) plane).aviation$kabMask());
                    result.addProperty("kabLeftInInventory", count(bombItem()));
                    // Outside camera behind the aircraft at pylon height, for the loaded-pylon shot.
                    pilot.stopRiding();
                    pilot.connection.teleport(ground.x, ground.y + .8, ground.z - 13, 0, -4);
                    stage = "inspect";
                    stageTicks = 0;
                    progress("{\"stage\":\"inspect\",\"vehicle\":" + plane.getId() + ",\"planeX\":" + ground.x
                        + ",\"planeY\":" + ground.y + ",\"planeZ\":" + ground.z + "}");
                }
            }
            case "inspect" -> {
                plane.setDeltaMovement(Vec3.ZERO);
                if (marker("aircraft-tablet-inspected.txt")) {
                    if (!pilot.startRiding(plane, true)) return finish("remount failed");
                    takeOff();
                }
            }
            case "airborne", "chase", "crater" -> {
                holdInFlight();
                observeLaunches();
                if (stage.equals("airborne") && kab != null && storms.size() == 1) {
                    // Camera only from here: a spectator cannot fall 70 blocks and respawn as a new player.
                    pilot.stopRiding();
                    previousMode = pilot.gameMode.getGameModeForPlayer();
                    pilot.setGameMode(GameType.SPECTATOR);
                    enter("chase");
                }
                if (stage.equals("chase")) {
                    chaseKab();
                    if (result.has("kabImpactError") && result.has("stormEndError") && stageTicks > 20) {
                        // Look down on both craters from the south-east.
                        Vec3 eye = target.add(SECOND_TARGET_EAST / 2, 70, -90);
                        Vec3 look = target.add(SECOND_TARGET_EAST / 2, 0, 0).subtract(eye);
                        pilot.connection.teleport(eye.x, eye.y - pilot.getEyeHeight(), eye.z,
                            (float) Math.toDegrees(Math.atan2(-look.x, look.z)),
                            (float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance())));
                        stage = "crater";
                        stageTicks = 0;
                    }
                }
                if (stage.equals("crater")) {
                    if (stageTicks == 30) progress("{\"stage\":\"crater\",\"vehicle\":" + plane.getId() + "}");
                    if (stageTicks > 30 && (marker("aircraft-tablet-crater.txt") || stageTicks > 400)) return evaluate();
                }
            }
            default -> { }
        }
        return false;
    }

    private void holdInFlight() {
        // Fixed point, nose north, at launch speed: the panel's speed gate sees a flying aircraft.
        if (lease != null && AircraftController.owns(pilot, plane, lease))
            AircraftController.submit(pilot, lease, PilotCommand.neutral(4_000_000L + ticks));
        plane.setPos(ground.x, ground.y + ALTITUDE, ground.z);
        plane.setYRot(0);
        plane.setXRot(0);
        plane.setDeltaMovement(0, 0, SPEED);
        plane.setOnGround(false);
    }

    private void observeLaunches() {
        var level = pilot.serverLevel();
        var box = new AABB(BlockPos.containing(ground)).inflate(300);
        if (kab == null) {
            var found = level.getEntitiesOfClass(KabEntity.class, box);
            if (!found.isEmpty()) {
                var bomb = found.get(0);
                kab = bomb;
                Vec3 aim = bomb.getTargetPos();
                result.addProperty("kabGuideType", bomb.getGuideType());
                result.addProperty("kabVariant", bomb.variant());
                result.addProperty("kabRadius", bomb.getExplosionRadius());
                result.addProperty("kabTargetError", aim == null ? -1 : aim.distanceTo(target));
                // Vanilla call through Projectile: the reobfuscated helper cannot resolve it on KabEntity.
                result.addProperty("kabOwnedByPilot",
                    ((net.minecraft.world.entity.projectile.Projectile) (Object) bomb).getOwner() == pilot);
            }
        }
        for (var missile : level.getEntitiesOfClass(GuidedMissileEntity.class, box)) {
            if (storms.contains(missile) || !storms.isEmpty()) continue;
            storms.add(missile);
            List<RoutePoint> route = missile.getRoute();
            RoutePoint last = route.isEmpty() ? null : route.get(route.size() - 1);
            result.addProperty("stormRoutePoints", route.size());
            // Horizontal only: DeepStrike lifts the stored waypoint by its own terrain clearance.
            result.addProperty("stormTargetError", last == null ? -1 : horizontal(new Vec3(last.x(), 0, last.z()), secondTarget));
        }
        if (kab != null && !result.has("kabImpactError")) {
            for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) {
                if (trace.entity == kab && trace.hit != null) {
                    result.addProperty("kabImpactError", horizontal(trace.hit, target));
                    result.addProperty("kabFlightTicks", kab.level().getGameTime() - trace.born);
                }
            }
        }
        // In flight only: after the hit SBW holds the bomb at the crater for a few ticks
        // while the client's copy carries on into the ground.
        if (kab != null && !kab.isRemoved() && !result.has("kabImpactError")) {
            // Integrated server: compare the bomb the pilot's client draws with the real one.
            var client = net.minecraft.client.Minecraft.getInstance().level;
            var drawn = client == null ? null : client.getEntity(kab.getId());
            serverTrail.addFirst(((Entity) kab).position());
            if (serverTrail.size() > 4) serverTrail.removeLast();
            if (drawn != null) {
                // Client and server tick out of phase by a tick or two: compare with the
                // nearest of the last server positions, so this measures drift, not lag.
                Vec3 seen = ((Entity) drawn).position();
                double gap = serverTrail.stream().mapToDouble(seen::distanceTo).min().orElse(0);
                double worst = result.has("kabClientGapMax") ? result.get("kabClientGapMax").getAsDouble() : 0;
                if (gap > worst) {
                    result.addProperty("kabClientGapMax", Math.round(gap * 10) / 10.0);
                    result.addProperty("kabClientGapAtTick", ((Entity) kab).tickCount);
                    result.addProperty("kabClientGapSpeed", Math.round(((Entity) kab).getDeltaMovement().length() * 10) / 10.0);
                }
                if (!result.has("kabClientGapMax")) result.addProperty("kabClientGapMax", 0.0);
            }
        }
        if (!storms.isEmpty() && storms.get(0).isRemoved() && !result.has("stormEndError"))
            result.addProperty("stormEndError", horizontal(storms.get(0).position(), secondTarget));
    }

    private void chaseKab() {
        if (kab == null || kab.isRemoved()) return;
        Vec3 velocity = kab.getDeltaMovement();
        Vec3 side = new Vec3(velocity.z, 0, -velocity.x);
        side = side.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 eye = kab.position().add(side.scale(9)).add(0, 2, 0);
        Vec3 look = kab.position().subtract(eye);
        pilot.connection.teleport(eye.x, eye.y - pilot.getEyeHeight(), eye.z,
            (float) Math.toDegrees(Math.atan2(-look.x, look.z)),
            (float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance())));
    }

    private void takeOff() {
        lease = AircraftController.acquire(pilot, plane).orElse(null);
        plane.setNoGravity(true);
        plane.setPos(ground.x, ground.y + ALTITUDE, ground.z);
        var level = pilot.serverLevel();
        target = surface(level, ground.x, ground.z + AHEAD);
        secondTarget = surface(level, target.x + SECOND_TARGET_EAST, target.z);
        solidBefore = solid(level, target);
        zoneBefore = zone(level, target);
        secondSolidBefore = solid(level, secondTarget);
        ImpactObservations.expect(pilot.getUUID(), plane.getUUID(), target);
        stage = "airborne";
        stageTicks = 0;
        progress("{\"stage\":\"airborne\",\"vehicle\":" + plane.getId() + ",\"targetX\":" + target.x + ",\"targetZ\":" + target.z
            + ",\"secondX\":" + secondTarget.x + ",\"secondZ\":" + secondTarget.z + "}");
    }

    private boolean evaluate() {
        var level = pilot.serverLevel();
        int kabCrater = solidBefore - solid(level, target), stormCrater = secondSolidBefore - solid(level, secondTarget);
        result.addProperty("kabBlocksDestroyed", kabCrater);
        // The whole blast zone (a КАБ-3000 crater reaches ~60 blocks out); the Storm Shadow
        // point is 100 east, outside it.
        result.addProperty("kabZoneDestroyed", zoneBefore - zone(level, target));
        result.addProperty("stormBlocksDestroyed", stormCrater);
        boolean pass = result.get("noWheelGuidedBomb").getAsBoolean()
            && result.get("kabMask").getAsInt() == 1
            && result.get("kabVariant").getAsInt() == (heavy ? 2 : 0)
            && result.get("kabLeftInInventory").getAsInt() == 1
            && result.get("kabGuideType").getAsInt() == 1
            && result.get("kabTargetError").getAsDouble() >= 0 && result.get("kabTargetError").getAsDouble() < 0.01
            && result.get("kabOwnedByPilot").getAsBoolean()
            && result.get("kabImpactError").getAsDouble() <= 8
            // A client without the lift/steering drew this flight ~35 blocks short.
            && result.has("kabClientGapMax") && result.get("kabClientGapMax").getAsDouble() <= 8
            && kabCrater > 0
            && result.get("stormRoutePoints").getAsInt() == 1
            && result.get("stormTargetError").getAsDouble() >= 0 && result.get("stormTargetError").getAsDouble() < 0.01
            && result.get("stormEndError").getAsDouble() <= 12;
        result.addProperty("pass", pass);
        return finish(null);
    }

    private static Vec3 surface(ServerLevel level, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return new Vec3(bx + .5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz + .5);
    }

    private static int zone(ServerLevel level, Vec3 centre) {
        BlockPos origin = BlockPos.containing(centre);
        int count = 0;
        for (var pos : BlockPos.betweenClosed(origin.offset(-ZONE_RADIUS, -32, -ZONE_RADIUS), origin.offset(ZONE_RADIUS, 12, ZONE_RADIUS)))
            if (!level.getBlockState(pos).isAir()) count++;
        return count;
    }

    private static int solid(ServerLevel level, Vec3 centre) {
        int count = 0;
        var origin = BlockPos.containing(centre);
        for (var pos : BlockPos.betweenClosed(origin.offset(-CRATER_RADIUS, -6, -CRATER_RADIUS), origin.offset(CRATER_RADIUS, 2, CRATER_RADIUS)))
            if (!level.getBlockState(pos).isAir()) count++;
        return count;
    }

    private void enter(String next) {
        stage = next;
        stageTicks = 0;
        progress("{\"stage\":\"" + next + "\",\"vehicle\":" + plane.getId() + "}");
    }

    private int count(net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < pilot.getInventory().getContainerSize(); i++) {
            var stack = pilot.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static boolean marker(String name) {
        var path = FMLPaths.GAMEDIR.get().resolve(name);
        try {
            return Files.isRegularFile(path)
                && Files.readString(path).trim().equals(System.getProperty("frontier.aircraftSystemsRunId"));
        } catch (java.io.IOException failure) {
            return false;
        }
    }

    private static void progress(String json) {
        try {
            String runId = System.getProperty("frontier.aircraftSystemsRunId");
            Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-tablet-progress.json"),
                json.replaceFirst("\\{", "{\"runId\":\"" + runId + "\","));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot write tablet progress", failure);
        }
    }

    private boolean finish(String error) {
        if (error != null) result.addProperty("error", error);
        result.addProperty("stage", stage);
        if (lease != null) AircraftController.release(pilot, lease);
        pilot.stopRiding();
        if (previousMode != null) pilot.setGameMode(previousMode);
        plane.discard();
        progress("{\"stage\":\"done\"}");
        return true;
    }

    JsonObject result() { return result; }
}
