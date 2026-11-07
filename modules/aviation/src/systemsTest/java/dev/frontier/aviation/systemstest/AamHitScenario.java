package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An air-to-air missile against a manoeuvring, vulnerable target, fired the way a player does:
 * the real client holds the seek key (X) until SBW locks, then clicks. The target flies a level
 * circle in front of the hovering shooter. Records the lock, the missile's track and closest
 * approach, and whether it actually hurt the target and nothing of the shooter's.
 */
final class AamHitScenario implements TesterFix.Scenario {
    private static final double AHEAD = 150, RADIUS = 30, ABOVE = 12;
    private final ServerPlayer pilot;
    private final ServerLevel level;
    private final VehicleEntity plane;
    private final Entity target;
    private final ControlLease lease;
    private final String slot, missileType, gun;
    private final double speed;
    private final Vec3 centre;
    private final JsonObject result = new JsonObject();
    private final JsonArray track = new JsonArray();
    private final Set<Integer> before = new HashSet<>();
    private MissileProjectile missile;
    private int ticks, actionSeq = -1, launchTick = -1, hitTick = -1, missileGoneTick = -1;
    private double angle, closest = Double.MAX_VALUE;
    private float targetHealth, planeHealth;
    private boolean serverFallback;

    AamHitScenario(ServerPlayer pilot, AircraftProfile shooter, String slot, String targetId, double speed) {
        this.pilot = pilot;
        this.level = pilot.serverLevel();
        this.slot = slot;
        this.speed = speed;
        if (pilot.isPassenger()) pilot.stopRiding();
        pilot.setGameMode(GameType.CREATIVE);
        TesterFix.clearObservations();
        TesterFix.recording = true;
        int x = 0, z = -900 - 200 * (int) (Math.abs((long) targetId.hashCode() + slot.hashCode()) % 5);
        int g = TesterFix.ground(level, x, z);
        double y = g + 90;
        pilot.teleportTo(level, x + .5, y, z + .5, 0, 0);
        plane = shooter.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("shooter creation failed");
        Entity body = plane;
        body.moveTo(x + .5, y, z + .5, 0, 0);
        body.setNoGravity(true);
        body.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        centre = new Vec3(x + .5, y + ABOVE, z + .5 + AHEAD);
        target = TesterFix.create(level, targetId);
        target.moveTo(centre.x + RADIUS, centre.y, centre.z, 0, 0);
        target.setNoGravity(true);
        if (!level.addFreshEntity(body) || !level.addFreshEntity(target) || !pilot.startRiding(body, true))
            throw new IllegalStateException("aam setup failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        String gun = slot.equals("aamissile") ? "AAMissile" : "LightAAMissile";
        missileType = slot.equals("aamissile") ? "superbwarfare:ru_9m100_missile" : "superbwarfare:ru_9m336_missile";
        result.addProperty("shooter", String.valueOf(EntityType.getKey(body.getType())));
        result.addProperty("target", targetId);
        result.addProperty("slot", slot);
        result.addProperty("targetSpeed", speed);
        result.addProperty("targetTurnDegPerTick", Math.toDegrees(speed / RADIUS));
        result.addProperty("pass", false);
        if (plane.getGunData(gun) == null) throw new IllegalStateException("no " + gun + " on " + result.get("shooter").getAsString());
        this.gun = gun;
        // Select with the fixture's lease now: once the real client sends its first control
        // packet it holds its own epoch and this lease no longer owns the aircraft.
        plane.getGunData(gun).ammo.set(2);
        if (!AircraftArmament.select(pilot, plane, lease, slot)) throw new IllegalStateException("AAM selection failed");
        result.addProperty("selectedGun", plane.getGunName(0));
    }

    @Override public boolean tick() {
        ticks++;
        Entity body = plane;
        AircraftController.submit(pilot, lease, PilotCommand.neutral(7_000_000L + ticks));
        body.setDeltaMovement(0, 0, 0);
        if (target.isAlive() && hitTick < 0) {
            angle += speed / RADIUS;
            Vec3 next = centre.add(Math.cos(angle) * RADIUS, 0, Math.sin(angle) * RADIUS);
            target.setDeltaMovement(next.subtract(target.position()));
            target.setPos(next.x, next.y, next.z);
            target.setYRot((float) Math.toDegrees(Math.atan2(-(next.x - centre.x), next.z - centre.z)) + 90);
        }
        // Seed right before selecting and keep the magazine stocked until launch (no store items aboard).
        if (ticks >= 5 && launchTick < 0) plane.getGunData(gun).ammo.set(2);
        if (ticks == 5) {
            if (!gun.equals(plane.getGunName(0))) return finish("AAM no longer selected");
            targetHealth = TesterFix.health(target);
            planeHealth = TesterFix.health(body);
            result.addProperty("targetHealthBefore", targetHealth);
            for (Entity e : level.getAllEntities()) before.add(e.getId());
        }
        // Give the client time to see the target, then hold X (SBW seek) and click while locked.
        if (ticks == 60) actionSeq = TesterFix.requestActions("keydown X", "sleep 1.4",
            "shot aam-" + result.get("shooter").getAsString().replace(':', '_') + "-" + slot + "-lock", "click", "sleep 0.4", "keyup X");
        if (launchTick < 0) {
            missile = findMissile();
            if (missile != null) {
                launchTick = ticks;
                result.addProperty("launchTick", ticks);
                result.addProperty("clientLaunch", !serverFallback);
                result.addProperty("missileTargetsTarget", target.getStringUUID().equals(missile.getTargetUUID()));
                result.addProperty("missileTargetsShooter", body.getStringUUID().equals(missile.getTargetUUID()));
            } else if (actionSeq > 0 && TesterFix.actionsDone(actionSeq) && ticks > 140 && !serverFallback) {
                // The real-client path did not launch; still measure guidance through the server API.
                serverFallback = true;
                result.addProperty("clientLaunch", false);
                result.addProperty("serverFire", String.valueOf(AircraftArmament.fireTracked(pilot, plane, lease, slot, 1, target)));
            }
            return ticks > 400 && finish("no launch");
        }
        int flight = ticks - launchTick;
        if (missile != null && missile.isAlive()) {
            AABB box = TesterFix.bounds(target);
            double d = Math.sqrt(box.distanceToSqr(missile.position()));
            closest = Math.min(closest, d);
            if (flight % 2 == 0 && track.size() < 200) {
                var row = new JsonArray();
                row.add(flight); row.add(TesterFix.round(d)); row.add(TesterFix.round(missile.getDeltaMovement().length()));
                row.add(missile.getTargetUUID().equals(target.getStringUUID()) ? "T" : missile.getTargetUUID());
                track.add(row);
            }
        } else if (missileGoneTick < 0) missileGoneTick = flight;
        float now = TesterFix.health(target);
        if (hitTick < 0 && (!target.isAlive() || now < targetHealth || target instanceof VehicleEntity v && v.isWreck())) hitTick = flight;
        if (hitTick >= 0 && flight - hitTick > 20) return finish(null);
        if (missileGoneTick >= 0 && flight - missileGoneTick > 20) return finish(hitTick >= 0 ? null : "missile gone without hurting the target");
        return flight > 300 && finish("missile still flying after 300 ticks");
    }

    private MissileProjectile findMissile() {
        var area = ((Entity) plane).getBoundingBox().inflate(48);
        for (var e : level.getEntitiesOfClass(MissileProjectile.class, area,
                m -> !before.contains(m.getId()) && missileType.equals(String.valueOf(EntityType.getKey(m.getType())))))
            return e;
        return null;
    }

    private boolean finish(String error) {
        if (error != null) result.addProperty("error", error);
        result.addProperty("ticks", ticks);
        result.addProperty("closestApproach", closest == Double.MAX_VALUE ? -1 : TesterFix.round(closest));
        result.addProperty("hitAfterTicks", hitTick);
        result.addProperty("targetHealthAfter", TesterFix.health(target));
        result.addProperty("targetAlive", target.isAlive());
        result.addProperty("shooterHealthDelta", TesterFix.health(plane) - planeHealth);
        result.add("track", track);
        result.add("targetHurts", TesterFix.hurtsOf(TesterFix.VEHICLE_HURTS, target));
        result.add("shooterHurts", TesterFix.hurtsOf(TesterFix.VEHICLE_HURTS, plane));
        boolean shooterClean = TesterFix.hurtsOf(TesterFix.VEHICLE_HURTS, plane).isEmpty();
        result.addProperty("pass", error == null && hitTick >= 0 && shooterClean
            && result.has("missileTargetsShooter") && !result.get("missileTargetsShooter").getAsBoolean());
        TesterFix.recording = false;
        AircraftController.release(pilot, lease);
        TesterFix.land(pilot);
        ((Entity) plane).discard();
        target.discard();
        return true;
    }

    @Override public JsonObject result() { return result; }
}
