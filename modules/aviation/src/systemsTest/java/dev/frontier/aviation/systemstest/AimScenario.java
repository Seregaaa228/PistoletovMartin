package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.weapons.WeaponAimResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/** Real client pilot, real native gun/missiles, native contact probes and an unmarked AGM control. */
final class AimScenario {
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final int distance;
    private final JsonObject result = new JsonObject();
    private final java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> wallChanges = new java.util.LinkedHashMap<>();
    private Vec3 target;
    private Agm65Entity legacy;
    private int ticks;
    private boolean done;

    static AimScenario start(ServerPlayer pilot, int distance, float pitch, float bank, double speed) {
        return new AimScenario(pilot, distance, pitch, bank, speed);
    }

    private AimScenario(ServerPlayer pilot, int distance, float pitch, float bank, double speed) {
        this.pilot = pilot;
        this.distance = distance;
        ImpactObservations.clear();
        var level = pilot.serverLevel();
        if (pilot.isPassenger()) throw new IllegalStateException("aim fixture needs a free test player");
        plane = AircraftProfile.F16.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("F16 creation failed");
        plane.moveTo(pilot.getX(), level.getMinBuildHeight() + 128, pilot.getZ(), 0, pitch);
        plane.setRoll(bank);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        if (!level.addFreshEntity(plane) || !pilot.startRiding(plane, true)) throw new IllegalStateException("aim fixture mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        plane.setDeltaMovement(plane.getViewVector(1).scale(speed));
        result.addProperty("pass", false);
        result.addProperty("distance", distance);
        result.addProperty("pitch", pitch);
        result.addProperty("bank", bank);
        result.addProperty("initialCarrierSpeed", speed);
    }

    boolean tick() {
        if (done) return true;
        ticks++;
        if (plane.isRemoved() || !AircraftController.owns(pilot, plane, lease)) return fail("real client pilot lost the lease");
        if (ticks == 5 || ticks == 30) {
            if (!AircraftArmament.select(pilot, plane, lease, "cannon")) return fail("cannon selection failed");
            Vec3 from = plane.getShootPosForHud(pilot, 1), ray = plane.getShootDirectionForHud(pilot, 1);
            Vec3 center = from.add(ray.normalize().scale(distance));
            BlockPos wall = BlockPos.containing(center);
            for (int x = -12; x <= 12; x++) for (int y = -12; y <= 12; y++) {
                BlockPos pos = wall.offset(x, y, 0);
                if (!pilot.serverLevel().hasChunkAt(pos)) return fail("target wall is outside loaded test chunks");
                wallChanges.putIfAbsent(pos, pilot.serverLevel().getBlockState(pos));
                pilot.serverLevel().setBlockAndUpdate(pos, Blocks.BEDROCK.defaultBlockState());
            }
            var aim = WeaponAimResolver.resolve(pilot.level(), pilot, from, ray, 256);
            if (aim.isEmpty()) return fail("canonical cannon ray unresolved");
            target = aim.get().point();
            ImpactObservations.expect(pilot.getUUID(), plane.getUUID(), target);
            if (WeaponAimResolver.validateProposal(pilot.level(), pilot, from, ray, target.add(ray.scale(4)), 256, 20).isPresent())
                return fail("accepted a point behind the wall");
            result.addProperty("obstructionRejected", true);
            plane.getGunData("Cannon").ammo.set(50);
            if (AircraftArmament.fire(pilot, plane, lease, "cannon", 1, null) != AircraftArmament.Result.QUEUED)
                return fail("native cannon intent rejected");
            Vec3 cannonOrigin = plane.getShootPosForHud(pilot, 1), cannonRay = plane.getShootDirectionForHud(pilot, 1);
            // At the first transition the real client receives the same common
            // reticle/view position for the missile, rather than a nacelle camera.
            if (!AircraftArmament.select(pilot, plane, lease, "missile")) return fail("missile selection failed");
            if (cannonOrigin.distanceToSqr(plane.getShootPosForHud(pilot, 1)) > 1e-10
                    || cannonRay.distanceToSqr(plane.getShootDirectionForHud(pilot, 1)) > 1e-10)
                return fail("cannon and missile HUD rays differ");
            plane.getGunData("Missile").ammo.set(2);
            int count = ticks == 5 ? 1 : 2;
            var fire = AircraftArmament.fire(pilot, plane, lease, "missile", count, target);
            if (fire != AircraftArmament.Result.QUEUED) {
                var gun = plane.getGunData("Missile");
                result.addProperty("nativeCanShoot", plane.canShoot(pilot));
                result.addProperty("gunCanShoot", gun.canShoot(plane.getAmmoSupplier()));
                result.addProperty("weaponState", AircraftArmament.inspect(pilot, plane, lease, "missile").toString());
                result.addProperty("planePosition", plane.position().toString());
                result.addProperty("currentHudOrigin", from.toString());
                result.addProperty("currentHudDirection", ray.toString());
                result.addProperty("target", target.toString());
                var mount = dev.frontier.aviation.aircraft.AircraftCatalog.find("frontier_aviation:f16")
                    .orElseThrow().weapons().find("missile").orElseThrow();
                result.addProperty("nativeRange", WeaponAimResolver.range(mount, gun));
                result.addProperty("nativeSector", WeaponAimResolver.sector(gun));
                result.addProperty("seatIndex", plane.getSeatIndex(pilot));
                result.addProperty("pilotDirectPassenger", pilot.getVehicle() == plane);
                result.addProperty("planeRemoved", plane.isRemoved());
                result.addProperty("planeWreck", plane.isWreck());
                result.addProperty("trustedOperator", ((dev.sbw.deepstrike.vehicle.Su24mEntity)plane).canOperate(pilot, true));
                result.addProperty("targetValidation", WeaponAimResolver.validateProposal(pilot.level(), pilot,
                    from, ray, target, WeaponAimResolver.range(mount, gun), WeaponAimResolver.sector(gun)).toString());
                return fail("native missile intent rejected: " + fire);
            }
            result.addProperty(ticks == 5 ? "singleQueued" : "pairQueued", true);
        }
        if (ticks == 40) {
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare", "agm_65"));
            var entity = type == null ? null : type.create(pilot.level());
            if (!(entity instanceof Agm65Entity missile)) return fail("legacy AGM control creation failed");
            legacy = missile;
            Vec3 muzzle = plane.getShootPos("Missile", 1);
            legacy.setPos(muzzle.x, muzzle.y + 4, muzzle.z);
            legacy.setOwner(pilot);
            legacy.setTargetVec(target);
            Vec3 ray = target.subtract(legacy.position()).normalize();
            legacy.shoot(ray.x, ray.y, ray.z, 1.05f, 0);
            if (!pilot.serverLevel().addFreshEntity(legacy)) return fail("legacy AGM spawn failed");
        }
        if (ticks < 140) return false;

        var traces = ImpactObservations.owned(pilot.getUUID(), plane.getUUID());
        var contacts = new JsonArray();
        int cannon = 0, missiles = 0;
        boolean pass = true;
        for (var trace : traces) {
            if (legacy != null && trace.entity.getUUID().equals(legacy.getUUID())) {
                result.addProperty("legacyMarkedDirect", trace.direct);
                result.addProperty("legacyHeightGain", trace.maximumY - trace.spawn.y);
                pass &= !trace.direct;
                continue;
            }
            boolean guided = trace.type.equals("superbwarfare:agm_65");
            if (guided) missiles++; else cannon++;
            var contact = new JsonObject();
            contact.addProperty("type", trace.type);
            contact.addProperty("spawn", trace.spawn.toString());
            contact.addProperty("initialVelocity", trace.initialVelocity.toString());
            contact.addProperty("born", trace.born);
            contact.addProperty("direct", trace.direct);
            contact.addProperty("heightGain", trace.maximumY - trace.spawn.y);
            if (trace.hit != null && trace.expected != null) {
                double error = trace.hit.distanceTo(trace.expected);
                contact.addProperty("errorFromReticle", error);
                contact.addProperty("actualContact", trace.hit.toString());
                contact.addProperty("reticleAtFire", trace.expected.toString());
                pass &= error <= 5;
            } else { contact.addProperty("missingContact", true); pass = false; }
            if (guided) pass &= trace.direct;
            contacts.add(contact);
        }
        result.add("contacts", contacts);
        result.addProperty("cannonProjectiles", cannon);
        result.addProperty("manualMissiles", missiles);
        result.addProperty("targetX", target.x);
        result.addProperty("targetY", target.y);
        result.addProperty("targetZ", target.z);
        result.addProperty("pass", pass && cannon > 0 && missiles == 3);
        cleanup();
        return true;
    }

    JsonObject result() { return result; }
    private boolean fail(String message) {
        result.addProperty("error", message); result.addProperty("pass", false); cleanup(); return true;
    }
    private void cleanup() {
        done = true;
        if (legacy != null) legacy.discard();
        for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) trace.entity.discard();
        AircraftController.release(pilot, lease);
        pilot.stopRiding(); plane.discard();
        wallChanges.forEach((pos, state) -> pilot.serverLevel().setBlockAndUpdate(pos, state));
        wallChanges.clear();
    }
}
