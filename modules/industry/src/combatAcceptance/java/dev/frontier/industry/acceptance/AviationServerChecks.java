package dev.frontier.industry.acceptance;

import com.atsuishio.superbwarfare.tools.OBB;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.impact.AircraftCollisions;
import dev.frontier.arcade.combat.DroneSweep;
import dev.sbw.deepstrike.registry.BFEntities;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.EntityHitResult;

/** Real server geometry probes in the caller's explicitly isolated acceptance world. */
public final class AviationServerChecks {
    private AviationServerChecks() { }

    public static Map<String, Boolean> run(ServerLevel level) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        Map<String, Supplier<Su24mEntity>> types = new LinkedHashMap<>();
        for (var profile : AircraftProfile.values()) types.put(profile.id, () -> profile.entity.get().create(level));
        types.put("native_su24", () -> BFEntities.SU24M.get().create(level));
        for (var type : types.entrySet()) {
            for (String mode : new String[] {"nose", "wing", "fuselage", "vertical", "landing", "glance", "corner", "drone_wing"}) {
                Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
                Su24mEntity plane = type.getValue().get();
                if (plane == null) throw new IllegalStateException("Missing aircraft fixture " + type.getKey());
                try {
                    plane.moveTo(0, 180, 20, 0, 0);
                    level.addFreshEntity(plane);
                    plane.updateOBB();
                    var geometry = plane.getOBBs();
                    if (geometry.size() < 2) throw new IllegalStateException("Missing body/wing OBB " + type.getKey());
                    var body = OBB.getWorldAABB(geometry.get(0));
                    var wing = OBB.getWorldAABB(geometry.get(1));
                    if (mode.equals("drone_wing")) {
                        Vec3 from = new Vec3(wing.maxX - .05, wing.getCenter().y, wing.minZ - 2);
                        Vec3 to = new Vec3(from.x, from.y, wing.maxZ + 2);
                        var drone = EntityType.ARROW.create(level);
                        if (drone == null) throw new IllegalStateException("Missing trace context");
                        drone.setPos(from);
                        float health = plane.getHealth();
                        var hit = DroneSweep.trace(drone, from, to, entity -> entity == plane);
                        results.put(type.getKey() + " drone wing trace outside vanilla entity query",
                                from.x - .25 > plane.getBoundingBox().maxX);
                        results.put(type.getKey() + " actual DroneSweep finds outer wing",
                                hit instanceof EntityHitResult entityHit && entityHit.getEntity() == plane);
                        results.put(type.getKey() + " drone trace leaves health unchanged", plane.getHealth() == health);
                        continue;
                    }
                    BlockPos target;
                    Vec3 movement;
                    int cornerZ = 0;
                    if (mode.equals("nose")) {
                        target = BlockPos.containing(body.getCenter().x, body.getCenter().y, Math.ceil(body.maxZ + .05));
                        movement = new Vec3(0, 0, 3);
                        results.put(type.getKey() + " nose fixture outside vanilla body", target.getZ() > plane.getBoundingBox().maxZ);
                    } else if (mode.equals("wing")) {
                        target = BlockPos.containing(Math.floor(wing.maxX - .1), wing.getCenter().y, Math.ceil(wing.maxZ + .05));
                        movement = new Vec3(0, 0, 3);
                        results.put(type.getKey() + " wing fixture outside vanilla body", target.getX() >= plane.getBoundingBox().maxX);
                    } else if (mode.equals("fuselage")) {
                        target = BlockPos.containing(Math.ceil(body.maxX + .05), body.getCenter().y, body.getCenter().z);
                        movement = new Vec3(3, 0, 0);
                    } else if (mode.equals("vertical")) {
                        target = BlockPos.containing(body.getCenter().x, Math.floor(body.minY - 1.1), body.getCenter().z);
                        movement = new Vec3(0, -3, 0);
                    } else if (mode.equals("corner")) {
                        // Both walls touch the outer wing only. The native
                        // central AABB misses even the second wall after the
                        // first contact clips its X displacement.
                        target = BlockPos.containing(Math.ceil(wing.maxX + .05), wing.getCenter().y, wing.getCenter().z);
                        cornerZ = (int) Math.ceil(wing.maxZ + 1.05);
                        movement = new Vec3(3, 0, 3);
                        for (int z = (int) Math.floor(wing.minZ) - 1; z <= cornerZ + 2; z++) {
                            place(level, changed, new BlockPos(target.getX(), target.getY(), z));
                        }
                        for (int x = target.getX() - 1; x <= target.getX() + 1; x++) {
                            place(level, changed, new BlockPos(x, target.getY(), cornerZ));
                        }
                        double firstGap = target.getX() - wing.maxX;
                        results.put(type.getKey() + " two wing walls outside clipped vanilla body",
                                target.getX() - 1 > plane.getBoundingBox().maxX + firstGap
                                        && firstGap < cornerZ - wing.maxZ && cornerZ - wing.maxZ < 3);
                    } else if (mode.equals("glance")) {
                        // Put the wing 0.1 block from a parallel wall outside
                        // even the translated vanilla AABB. Its shallow normal
                        // speed is harmless; it must still stop at the wall.
                        double shift = Math.ceil(wing.maxX) - .1 - wing.maxX;
                        plane.moveTo(shift, 180, 20, 0, 0);
                        plane.updateOBB();
                        body = OBB.getWorldAABB(geometry.get(0));
                        wing = OBB.getWorldAABB(geometry.get(1));
                        target = BlockPos.containing(Math.ceil(wing.maxX + .05), wing.getCenter().y, wing.getCenter().z);
                        movement = new Vec3(.3, 0, 3);
                        results.put(type.getKey() + " shallow wing wall outside translated vanilla body",
                                target.getX() > plane.getBoundingBox().maxX + movement.x);
                        for (int z = (int) Math.floor(wing.minZ) - 1; z <= Math.ceil(wing.maxZ + 6); z++) {
                            place(level, changed, new BlockPos(target.getX(), target.getY(), z));
                        }
                    } else {
                        // Runway under native feet, 0.1 block of clearance. This
                        // tests a real permitted native touchdown at flight speed.
                        plane.moveTo(0, 180.1, 20, 0, 0);
                        plane.updateOBB();
                        target = new BlockPos(0, 179, 20);
                        movement = new Vec3(1.1, -.2, 0);
                        for (int x = -10; x <= 10; x++) for (int z = 10; z <= 30; z++) {
                            place(level, changed, new BlockPos(x, 179, z));
                        }
                    }
                    place(level, changed, target);
                    float before = plane.getHealth();
                    Vec3 origin = plane.position();
                    long strikes = AircraftCollisions.strikes;
                    plane.setDeltaMovement(movement);
                    // Native VehicleEntity limits a sudden >sqrt(8) impulse;
                    // retain that incoming tangent, rather than assuming the
                    // requested displacement also became native velocity.
                    Vec3 incomingVelocity = plane.getDeltaMovement();
                    plane.setLastTickSpeed(movement.length());
                    plane.setLastTickVerticalSpeed(movement.y);
                    plane.move(MoverType.SELF, movement); // actual production mixin entry
                    com.mojang.logging.LogUtils.getLogger().info(
                            "AVIATION_GEOMETRY {} {} target={} body={} wing={} health={} -> {} strikes={} -> {} candidates={} travel={} velocity={} -> {}",
                            type.getKey(), mode, target, body, wing, before, plane.getHealth(), strikes,
                            AircraftCollisions.strikes, AircraftCollisions.lastCandidateBoxes,
                            plane.position().subtract(origin), incomingVelocity, plane.getDeltaMovement());
                    if (mode.equals("landing")) {
                        results.put(type.getKey() + " gentle native runway touchdown preserves health",
                                plane.getHealth() == before && AircraftCollisions.strikes == strikes);
                    } else if (mode.equals("corner")) {
                        Vec3 travel = plane.position().subtract(origin);
                        double gapX = target.getX() - wing.maxX, gapZ = cornerZ - wing.maxZ;
                        results.put(type.getKey() + " wing corner stops at both perpendicular walls",
                                travel.x > gapX - .01 && travel.x < gapX
                                        && travel.z > gapZ - .01 && travel.z < gapZ);
                        results.put(type.getKey() + " wing corner applies one native strike",
                                plane.getHealth() < before && AircraftCollisions.strikes == strikes + 1
                                        && plane.getCollisionCoolDown() == 4);
                        results.put(type.getKey() + " wing corner removes both inward velocity components",
                                Math.abs(plane.getDeltaMovement().x) < 1e-6 && Math.abs(plane.getDeltaMovement().z) < 1e-6);
                        float health = plane.getHealth();
                        Vec3 first = plane.position();
                        plane.move(MoverType.SELF, movement);
                        results.put(type.getKey() + " wing corner cooldown retains both solid contacts",
                                plane.position().distanceToSqr(first) < 1e-8 && plane.getHealth() == health
                                        && AircraftCollisions.strikes == strikes + 1);
                    } else if (mode.equals("glance")) {
                        Vec3 travel = plane.position().subtract(origin);
                        results.put(type.getKey() + " shallow wing contact stops without damage or cooldown",
                                wing.maxX + travel.x < target.getX() + 1e-5 && plane.getHealth() == before
                                        && AircraftCollisions.strikes == strikes && plane.getCollisionCoolDown() == 0);
                        results.put(type.getKey() + " shallow wing contact preserves full tangential movement",
                                Math.abs(travel.z - 3) < 1e-6 && Math.abs(plane.getDeltaMovement().x) < 1e-6
                                        && Math.abs(plane.getDeltaMovement().z - incomingVelocity.z) < 1e-6);
                        Vec3 first = plane.position();
                        plane.move(MoverType.SELF, movement);
                        results.put(type.getKey() + " repeated harmless wing contact slides without crossing wall",
                                Math.abs(plane.getX() - first.x) < 1e-4 && Math.abs(plane.getZ() - first.z - 3) < 1e-6
                                        && plane.getHealth() == before && AircraftCollisions.strikes == strikes
                                        && plane.getCollisionCoolDown() == 0);
                    } else {
                        Vec3 travel = plane.position().subtract(origin);
                        boolean stopped = mode.equals("nose") ? body.maxZ + travel.z < target.getZ() + 1e-5
                                : mode.equals("wing") ? wing.maxZ + travel.z < target.getZ() + 1e-5
                                : mode.equals("fuselage") ? body.maxX + travel.x < target.getX() + 1e-5
                                : body.minY + travel.y > target.getY() + 1 - 1e-5;
                        results.put(type.getKey() + " " + mode + " native displacement stops before obstacle", stopped);
                        results.put(type.getKey() + " " + mode + " actual geometry strike damages aircraft",
                                plane.getHealth() < before && AircraftCollisions.strikes > strikes);
                        float health = plane.getHealth();
                        Vec3 contactPosition = plane.position();
                        plane.move(MoverType.SELF, movement);
                        results.put(type.getKey() + " " + mode + " cooldown prevents duplicate strike",
                                plane.getHealth() == health && AircraftCollisions.strikes == strikes + 1);
                        results.put(type.getKey() + " " + mode + " cooldown retains solid contact",
                                plane.position().distanceToSqr(contactPosition) < 1e-8);
                    }
                } finally {
                    plane.discard();
                    changed.forEach((pos, state) -> level.setBlock(pos, state, 2));
                }
            }
        }
        return results;
    }

    private static void place(ServerLevel level, Map<BlockPos, BlockState> changed, BlockPos pos) {
        changed.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
    }
}
