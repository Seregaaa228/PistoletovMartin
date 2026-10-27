package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.ControlStatus;
import dev.frontier.aviation.control.EnginePhase;
import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/** Real seated LivingEntity drives through the public port; no direct flight-state writes after setup. */
final class NpcFlightScenario {
    private final ServerLevel level;
    private final VehicleEntity plane;
    private final ArmorStand pilot;
    private final ControlLease lease;
    private final Vec3 start;
    private final JsonObject result = new JsonObject();
    private final int initialShells, initialMissiles;
    private int ticks, engineRunning;
    private float greatestBank;
    private int cannonBefore, missileBefore;
    private boolean cannonQueued, missileQueued, done;

    static NpcFlightScenario start(ServerLevel level, BlockPos origin) {
        return new NpcFlightScenario(level, origin);
    }

    private NpcFlightScenario(ServerLevel level, BlockPos origin) {
        this.level = level;
        plane = AircraftProfile.F16.entity.get().create(level);
        pilot = EntityType.ARMOR_STAND.create(level);
        if (plane == null || pilot == null) throw new IllegalStateException("NPC fixture entity creation failed");
        plane.moveTo(origin.getX() + .5, origin.getY() + 128, origin.getZ() + .5, 0, 0);
        plane.setEnergy(plane.getMaxEnergy());
        pilot.moveTo(plane.getX(), plane.getY() + 3, plane.getZ(), 0, 0);
        pilot.setInvulnerable(true);
        pilot.setNoGravity(true);
        if (!level.addFreshEntity(plane) || !level.addFreshEntity(pilot) || !pilot.startRiding(plane, true))
            throw new IllegalStateException("NPC fixture seat setup failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow(() -> new IllegalStateException("NPC lease acquisition failed"));
        start = plane.position();
        initialShells = ProjectileObservations.count("superbwarfare:small_cannon_shell");
        initialMissiles = ProjectileObservations.count("superbwarfare:agm_65");
        result.addProperty("actor", pilot.getType().toString());
        result.addProperty("vehicle", plane.getUUID().toString());
        result.addProperty("epoch", lease.epoch());
        result.addProperty("pass", false);
    }

    boolean tick() {
        if (done) return true;
        ticks++;
        if (plane.isRemoved() || plane.getHealth() <= 0 || !pilot.isAlive()) return fail("NPC aircraft died before checks finished");
        // Move only the disposable observing players to keep ordinary chunk
        // tracking active. No player is mounted and no aircraft motion is set.
        if (ticks % 10 == 1) for (var observer : level.players())
            observer.teleportTo(plane.getX() + 12, plane.getY() + 12, plane.getZ() - 12);
        float roll = ticks >= 55 && ticks < 65 ? .5f : ticks >= 65 && ticks < 75 ? -.5f : 0;
        var command = new PilotCommand(ticks, 0, 0, roll, ticks < 80 ? 1 : 0,
            false, false, false, ticks == 1 ? ModeRequest.ENABLE : ModeRequest.KEEP,
            ticks == 45 ? ModeRequest.DISABLE : ModeRequest.KEEP, ModeRequest.KEEP);
        ControlStatus status = AircraftController.submit(pilot, lease, command);
        if (status != ControlStatus.OK) return fail("NPC command rejected: " + status);
        var snapshot = AircraftController.observe(plane).orElseThrow();
        if (!pilot.getUUID().equals(snapshot.controllerId()) || snapshot.epoch() != lease.epoch())
            return fail("NPC ownership/epoch changed");
        if (snapshot.telemetry().state().enginePhase() == EnginePhase.RUNNING) engineRunning++;
        greatestBank = Math.max(greatestBank, Math.abs(plane.getRoll()));
        Vec3 velocity = plane.getDeltaMovement();
        if (!Double.isFinite(velocity.lengthSqr()) || !Float.isFinite(plane.getRoll())) return fail("nonfinite native flight");
        if (ticks == 70) {
            if (!AircraftArmament.select(pilot, plane, lease, "cannon")) return fail("NPC cannon selection denied");
            var gun = plane.getGunData("Cannon");
            cannonBefore = ((dev.sbw.deepstrike.vehicle.Su24mEntity) plane).cannonRounds();
            cannonQueued = AircraftArmament.fire(pilot, plane, lease, "cannon", 1, null) == AircraftArmament.Result.QUEUED;
        }
        if (ticks == 90) {
            result.addProperty("cannonRoundsAfter", ((dev.sbw.deepstrike.vehicle.Su24mEntity) plane).cannonRounds());
            if (!AircraftArmament.select(pilot, plane, lease, "missile")) return fail("NPC missile selection denied");
            var gun = plane.getGunData("Missile");
            gun.ammo.set(2);
            missileBefore = gun.ammo.get();
            missileQueued = AircraftArmament.fire(pilot, plane, lease, "missile", 2, null) == AircraftArmament.Result.QUEUED;
        }
        if (ticks < 130) return false;

        result.addProperty("cannonQueued", cannonQueued);
        result.addProperty("missileQueued", missileQueued);
        result.addProperty("cannonRoundsBefore", cannonBefore);
        result.addProperty("missileRoundsBefore", missileBefore);
        result.addProperty("missileRoundsAfter", plane.getGunData("Missile").ammo.get());
        result.addProperty("nativeShellSpawns", ProjectileObservations.count("superbwarfare:small_cannon_shell") - initialShells);
        result.addProperty("nativeMissileSpawns", ProjectileObservations.count("superbwarfare:agm_65") - initialMissiles);
        result.addProperty("runningEngineTicks", engineRunning);
        result.addProperty("distanceMoved", start.distanceTo(plane.position()));
        result.addProperty("greatestBank", greatestBank);
        pilot.stopRiding();
        var afterDismount = AircraftController.submit(pilot, lease, PilotCommand.neutral(131));
        result.addProperty("afterDismount", afterDismount.name());
        if (!pilot.startRiding(plane, true)) return fail("NPC remount denied");
        var replacement = AircraftController.acquire(pilot, plane).orElseThrow();
        var stale = AircraftController.submit(pilot, lease, PilotCommand.neutral(132));
        result.addProperty("replacementEpoch", replacement.epoch());
        result.addProperty("staleLease", stale.name());
        boolean pass = cannonQueued && missileQueued && result.get("cannonRoundsAfter").getAsInt() < cannonBefore
            && plane.getGunData("Missile").ammo.get() == 0
            && result.get("nativeShellSpawns").getAsInt() > 0 && result.get("nativeMissileSpawns").getAsInt() == 2
            && engineRunning > 30 && start.distanceToSqr(plane.position()) > 4 && greatestBank > .1f
            && afterDismount != ControlStatus.OK && stale != ControlStatus.OK && replacement.epoch() != lease.epoch();
        AircraftController.release(pilot, replacement);
        result.addProperty("pass", pass);
        cleanup();
        return true;
    }

    JsonObject result() { return result; }

    private boolean fail(String message) {
        result.addProperty("error", message);
        result.addProperty("pass", false);
        cleanup();
        return true;
    }

    private void cleanup() {
        done = true;
        pilot.stopRiding();
        pilot.discard();
        plane.discard();
    }
}
