package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.weapons.KabEntity;
import dev.frontier.aviation.weapons.WeaponAimResolver;
import dev.frontier.aviation.weapons.WeaponAimResult;
import java.nio.file.Files;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Real Su-25 pilot designates a ground point with the common reticle and releases one
 * native GuidedBomb store. The KAB must be point-guided by the native shooter, glide
 * (never climb), respect its fin-authority turn limit and impact near the point.
 */
final class GlideBombScenario {
    private static final float DIVE_PITCH = 35;
    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ControlLease lease;
    private final JsonObject result = new JsonObject();
    private Vec3 target;
    // Vanilla calls go through Entity: the helper JAR is reobfuscated without our classes in its hierarchy.
    private net.minecraft.world.entity.Entity bomb;
    private Vec3 previousVelocity;
    private double maximumTurn, minimumSpeed = Double.MAX_VALUE, maximumSpeed, releaseY, peakY;
    private int bombTicks;
    private boolean progressWritten;
    private int ticks;

    GlideBombScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        var level = pilot.serverLevel();
        if (pilot.isPassenger()) throw new IllegalStateException("pilot already mounted");
        ImpactObservations.clear();
        plane = AircraftProfile.SU25.entity.get().create(level);
        if (plane == null) throw new IllegalStateException("no Su-25");
        plane.moveTo(pilot.getX(), level.getMinBuildHeight() + 128, pilot.getZ(), 0, DIVE_PITCH);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        if (!level.addFreshEntity(plane) || !pilot.startRiding(plane, true)) throw new IllegalStateException("mount failed");
        lease = AircraftController.acquire(pilot, plane).orElseThrow();
        result.addProperty("pass", false);
        result.addProperty("divePitch", DIVE_PITCH);
    }

    boolean tick() {
        ticks++;
        if (ticks < 10) {
            if (!AircraftController.owns(pilot, plane, lease)) return finish("lost pilot lease");
            AircraftController.submit(pilot, lease, PilotCommand.neutral(3_000_000L + ticks));
            plane.setDeltaMovement(plane.getViewVector(1).scale(1.8));
        }
        if (ticks == 5) {
            if (!AircraftArmament.select(pilot, plane, lease, "glide_bomb")) return finish("missing glide bomb slot");
            var state = AircraftArmament.inspect(pilot, plane, lease, "glide_bomb").orElse(null);
            result.addProperty("nativeKind", state == null ? "none" : state.kind().toString());
            Vec3 from = plane.getShootPosForHud(pilot, 1);
            Vec3 ray = plane.getShootDirectionForHud(pilot, 1);
            var aim = WeaponAimResolver.resolve(pilot.level(), pilot, from, ray, 384);
            if (aim.isEmpty() || aim.get().kind() != WeaponAimResult.Kind.BLOCK) return finish("reticle did not designate ground");
            target = aim.get().point();
            result.addProperty("designatedRange", from.distanceTo(target));
            ImpactObservations.expect(pilot.getUUID(), plane.getUUID(), target);
            var gun = plane.getGunData("GuidedBomb");
            if (gun == null) return finish("native GuidedBomb gun missing");
            gun.ammo.set(2);
            var fired = AircraftArmament.fire(pilot, plane, lease, "glide_bomb", 1, target);
            result.addProperty("queued", fired.toString());
            if (fired != AircraftArmament.Result.QUEUED) return finish("release denied");
        }
        if (ticks == 10) {
            pilot.stopRiding();
            result.addProperty("pilotLeftAfterRelease", true);
        }
        if (bomb == null) {
            for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) {
                if (trace.type.equals("frontier_aviation:kab_500") && trace.entity instanceof KabEntity kab) {
                    bomb = kab;
                    releaseY = bomb.getY();
                    peakY = releaseY;
                    result.addProperty("guideType", kab.getGuideType());
                    Vec3 designated = kab.getTargetPos();
                    result.addProperty("nativeTargetError", designated == null ? -1 : designated.distanceTo(target));
                    result.addProperty("trackedUuid", kab.getTargetUUID());
                    var type = bomb.getType();
                    result.addProperty("airDefenseTagged", type.is(tag("combat_air_targets")));
                    result.addProperty("lowIrTagged", type.is(tag("combat_low_ir_targets")));
                }
            }
        }
        if (bomb != null && !bomb.isRemoved()) sample();
        for (var trace : ImpactObservations.owned(pilot.getUUID(), plane.getUUID())) {
            if (!trace.type.equals("frontier_aviation:kab_500") || trace.hit == null) continue;
            double error = target.distanceTo(trace.hit);
            long kabs = ImpactObservations.owned(pilot.getUUID(), plane.getUUID()).stream()
                .filter(t -> t.type.equals("frontier_aviation:kab_500")).count();
            result.addProperty("releasedStores", kabs);
            result.addProperty("impactError", error);
            result.addProperty("flightTicks", bombTicks);
            result.addProperty("climbAboveRelease", peakY - releaseY);
            result.addProperty("maximumTurnDegreesPerTick", maximumTurn);
            result.addProperty("minimumSpeed", minimumSpeed);
            result.addProperty("maximumSpeed", maximumSpeed);
            boolean pass = result.get("queued").getAsString().equals("QUEUED")
                && kabs == 1
                && result.get("guideType").getAsInt() == 1
                && result.get("nativeTargetError").getAsDouble() >= 0 && result.get("nativeTargetError").getAsDouble() < 0.01
                && result.get("airDefenseTagged").getAsBoolean() && result.get("lowIrTagged").getAsBoolean()
                && peakY - releaseY <= 0.5
                // Fin authority plus the gravity curvature of one tick; a missile turns up to 15.
                && maximumTurn <= KabEntity.MAX_TURN_DEGREES + 2.0
                && error <= 6;
            result.addProperty("pass", pass);
            return finish(null);
        }
        return ticks >= 380 && finish("no confirmed impact");
    }

    private void sample() {
        bombTicks++;
        Vec3 velocity = bomb.getDeltaMovement();
        peakY = Math.max(peakY, bomb.getY());
        double speed = velocity.length();
        if (bombTicks > KabEntity.SEPARATION_TICKS + 1) {
            minimumSpeed = Math.min(minimumSpeed, speed);
            maximumSpeed = Math.max(maximumSpeed, speed);
            if (previousVelocity != null && previousVelocity.lengthSqr() > 1.0E-6 && speed > 1.0E-3) {
                double cos = Math.max(-1, Math.min(1, previousVelocity.normalize().dot(velocity.normalize())));
                maximumTurn = Math.max(maximumTurn, Math.toDegrees(Math.acos(cos)));
            }
        }
        if (bombTicks == 3) result.addProperty("releaseSpeed", speed);
        previousVelocity = velocity;
        if (ticks > 10 && !pilot.isPassenger()) {
            // Chase camera for the real client: abeam the store, looking at it.
            Vec3 side = new Vec3(velocity.z, 0, -velocity.x);
            side = side.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : side.normalize();
            Vec3 eye = bomb.position().add(side.scale(7)).add(0, 1.5, 0);
            Vec3 look = bomb.position().subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance()));
            pilot.connection.teleport(eye.x, eye.y - pilot.getEyeHeight(), eye.z, yaw, pitch);
            if (!progressWritten && bombTicks >= 30) {
                progressWritten = true;
                try {
                    Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-glide-progress.json"),
                        "{\"runId\":\"" + System.getProperty("frontier.aircraftSystemsRunId") + "\",\"stage\":\"in-flight\"}\n");
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("cannot write glide progress", failure);
                }
            }
        }
    }

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("frontier_industry", path));
    }

    private boolean finish(String error) {
        if (error != null) result.addProperty("error", error);
        AircraftController.release(pilot, lease);
        pilot.stopRiding();
        plane.discard();
        return true;
    }

    JsonObject result() { return result; }
}
