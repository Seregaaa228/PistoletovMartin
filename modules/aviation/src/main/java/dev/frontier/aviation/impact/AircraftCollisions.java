package dev.frontier.aviation.impact;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;

/** Supplement the native single-body collision check with each existing aircraft OBB. */
public final class AircraftCollisions {
    private static final ResourceLocation NATIVE_SU24 = new ResourceLocation("deepstrike", "su24m");
    public static final int MAX_CANDIDATE_BOXES = 4096;
    public static final double MAX_SWEEP_DISTANCE = 32;
    public static volatile long geometryChecks;
    public static volatile long strikes;
    public static volatile long overflowStrikes;
    public static volatile int lastCandidateBoxes;

    private AircraftCollisions() { }

    public static Vec3 movement(VehicleEntity plane, MoverType mover, Vec3 requested) {
        if (plane.level().isClientSide || mover != MoverType.SELF || !(plane instanceof Su24mEntity)
                || (AircraftProfile.of(plane) == null && !NATIVE_SU24.equals(EntityType.getKey(plane.getType())))
                || plane.isWreck() || (plane.getHealth() <= 0 && plane.getCollisionCoolDown() <= 0)
                || (requested.lengthSqr() < AircraftImpactRules.MIN_FLIGHT_SPEED * AircraftImpactRules.MIN_FLIGHT_SPEED
                    && plane.getCollisionCoolDown() <= 0)
                || requested.lengthSqr() < 1e-12
                || !Double.isFinite(requested.lengthSqr())) return requested;
        geometryChecks++;
        // Native baseTick refreshes these at its end, after movement. Refresh
        // before this probe so a current nose/wing attitude is tested, not the
        // preceding tick's position (also important immediately after placement).
        plane.updateOBB();
        var result = geometry(plane, requested);
        if (result.contacts().isEmpty()) return requested;
        // Each subsequent tangent is swept again, so one wing cannot slide
        // through a second surface outside the native central bounding box.
        Vec3 velocity = plane.getDeltaMovement();
        double[] clippedVelocity = result.clipVelocity(velocity.x, velocity.y, velocity.z);
        plane.setDeltaMovement(new Vec3(clippedVelocity[0], clippedVelocity[1], clippedVelocity[2]));
        Vec3 allowed = new Vec3(result.x(), result.y(), result.z());
        float damage = 0;
        for (var contact : result.contacts()) {
            damage = Math.max(damage, AircraftImpactRules.damage(requested.length(),
                    contact.nx() * contact.normalSpeed(), contact.ny() * contact.normalSpeed(),
                    contact.nz() * contact.normalSpeed()));
        }
        if (damage <= 0 || plane.getCollisionCoolDown() > 0) return allowed;
        var driver = plane.getLastDriver();
        float health = plane.getHealth();
        plane.hurt(ModDamageTypes.causeVehicleStrikeDamage(plane.level().registryAccess(), plane,
                driver == null ? plane : driver), damage);
        // Native hurt applies health then returns Entity.hurt's boolean, so
        // compare health to observe actual accepted damage.
        if (plane.getHealth() < health) {
            strikes++;
            // Native move skips its second strike during this cooldown. Native
            // baseTick owns the wreck transition, crew handling, explosion and loot.
            plane.setCollisionCoolDown(4);
            plane.setCrash(true);
        }
        return allowed;
    }

    private record Geometry(SweptAircraftBox.Box box, AABB bounds) { }

    private static SweptAircraftMotion.Result geometry(VehicleEntity plane, Vec3 requested) {
        var geometry = new ArrayList<Geometry>(plane.getOBBs().size());
        for (var nativeBox : plane.getOBBs()) {
            var center = nativeBox.center;
            var extent = nativeBox.extents();
            var axes = nativeBox.getAxes();
            geometry.add(new Geometry(new SweptAircraftBox.Box(center.x, center.y, center.z, extent.x, extent.y, extent.z,
                    new double[] {axes[0].x, axes[0].y, axes[0].z, axes[1].x, axes[1].y, axes[1].z,
                            axes[2].x, axes[2].y, axes[2].z}), OBB.getWorldAABB(nativeBox)));
        }
        lastCandidateBoxes = 0;
        return SweptAircraftMotion.resolve(requested.x, requested.y, requested.z,
                (ox, oy, oz, mx, my, mz) -> firstContact(plane, geometry, ox, oy, oz, new Vec3(mx, my, mz)));
    }

    private static SweptAircraftBox.Hit firstContact(VehicleEntity plane, ArrayList<Geometry> geometry,
            double ox, double oy, double oz, Vec3 requested) {
        double speed = requested.length();
        Vec3 probe = speed > MAX_SWEEP_DISTANCE ? requested.scale(MAX_SWEEP_DISTANCE / speed) : requested;
        AABB search = null;
        for (var part : geometry) {
            AABB swept = part.bounds().move(ox, oy, oz).expandTowards(probe);
            search = search == null ? swept : search.minmax(swept);
        }
        if (search == null) return null;
        SweptAircraftBox.Hit earliest = null;
        // Vanilla's collision iterator reads loaded collision chunks; this path
        // never requests or forces chunks. Actual voxel boxes retain slabs,
        // fences and gaps, rather than treating every non-air block as a cube.
        for (var shape : plane.level().getBlockCollisions(plane, search.inflate(1e-6))) {
            for (var block : shape.toAabbs()) {
                if (++lastCandidateBoxes > MAX_CANDIDATE_BOXES) return overflow(requested);
                for (var part : geometry) {
                    var box = part.box();
                    // Shift the voxel instead of rebuilding the native SAT axes
                    // and projected radii for every sliding re-sweep.
                    var hit = SweptAircraftBox.contact(box, block.minX - ox, block.minY - oy, block.minZ - oz,
                            block.maxX - ox, block.maxY - oy, block.maxZ - oz, probe.x, probe.y, probe.z);
                    if (hit == null) continue;
                    double normalSpeed = -(requested.x * hit.nx() + requested.y * hit.ny() + requested.z * hit.nz());
                    double time = hit.time() * probe.length() / speed;
                    if (normalSpeed > 1e-6
                            && (earliest == null || time < earliest.time())) {
                        earliest = new SweptAircraftBox.Hit(time, hit.nx(), hit.ny(), hit.nz());
                    }
                }
            }
        }
        // Pathological motion must not evade collision by exceeding a bounded
        // probe. Ordinary native flight and landing never enter this fallback.
        return earliest != null ? earliest : speed > MAX_SWEEP_DISTANCE ? overflow(requested) : null;
    }

    private static SweptAircraftBox.Hit overflow(Vec3 movement) {
        overflowStrikes++;
        Vec3 normal = movement.normalize().scale(-1);
        return new SweptAircraftBox.Hit(0, normal.x, normal.y, normal.z);
    }
}
