package dev.frontier.arcade.combat;

import com.atsuishio.superbwarfare.data.CustomData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import com.atsuishio.superbwarfare.tools.DamageHandler;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.fpv.FpvExplosion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import ru.lavafrai.uncomplicatedfpv.entity.FpvDroneEntity;

/** Pinned UF/SBW compatibility: payload and stored owner survive monitor/signal cleanup. */
public final class FpvCombat {
    private FpvCombat() { }
    public static boolean armed(FpvDroneEntity drone) {
        return WarheadRules.explosivePayload(com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.getItemId(drone.getCurrentItem()), drone.getAmmo());
    }
    public static boolean impactTarget(FpvDroneEntity drone, Entity target) {
        return target != drone && target.isAlive() && target.isPickable() && !target.isSpectator()
            && !target.getUUID().equals(ControlSessions.ownerId(drone)) && !drone.isPassengerOfSameVehicle(target)
            && !(target instanceof ItemEntity) && !(target instanceof Projectile) && !(target instanceof AreaEffectCloud);
    }
    public static boolean sweep(FpvDroneEntity drone, Vec3 movement) {
        if (drone.level().isClientSide || drone.isRemoved() || !armed(drone)) return false;
        Vec3 from = drone.getBoundingBox().getCenter();
        if (!DroneSweep.loadedPath(drone, from, from.add(movement))) {
            drone.setDeltaMovement(Vec3.ZERO); return true; // Unknown terrain is never treated as empty space.
        }
        if (drone.onGround() && movement.y <= 0 && movement.horizontalDistanceSqr() < .0025) return false;
        // In each target's moving frame: a jet passes a slow or hovering FPV between two snapshots.
        var hit = DroneSweep.trace(drone, from, from.add(movement), target -> impactTarget(drone, target), true);
        if (hit == null) return false;
        detonate(drone, hit.getLocation(), hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null);
        return true;
    }
    /**
     * A crew member in an enclosed seat (a jet's pilot, a tank's crew) sits inside the hull: striking
     * them strikes the vehicle, which SBW also makes absorb every hit on them. Open seats stay exposed.
     */
    public static Entity struckBody(FpvDroneEntity drone, Entity struck) {
        if (struck instanceof LivingEntity crew && crew.getVehicle() instanceof VehicleEntity hull
                && hull.isEnclosed(crew) && impactTarget(drone, hull)) return hull;
        return struck;
    }
    public static void detonate(FpvDroneEntity drone, Vec3 point, Entity struck) {
        if (!(drone.level() instanceof ServerLevel level) || drone.isRemoved()
            || !((Object)drone instanceof DroneImpactState state) || !state.frontier$claimImpact()) return;
        Entity directTarget = struck == null ? null : struckBody(drone, struck);
        ControlSessions.rememberNativeOwner(drone);
        var owner = ControlSessions.ownerId(drone);
        String payloadId = com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.getItemId(drone.getCurrentItem());
        var payload = armed(drone) ? CustomData.DRONE_ATTACHMENT.get(payloadId) : null;
        // Spend before callbacks can re-enter destroy/hitEntityCrash. Never fabricate an unloaded warhead.
        drone.setAmmo(0); drone.setFire(false);
        var operator = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        ControlSessions.forgetPair(drone); drone.handleSignalLoss(operator);
        if (owner != null && payload != null) {
            Entity attacker = operator == null ? drone : operator;
            Entity warhead = EntityType.byString(payload.displayEntity()).map(type -> type.create(level)).orElse(null);
            if (warhead == null) warhead = drone;
            warhead.setPos(point); // Unspawned native source keeps SBW armor/damage type rules.
            // The living near the blast take the scaled FPV damage; vehicles and the rest stay native.
            var damaged = FpvExplosion.damageLiving(drone, point, operator);
            if (directTarget != null && !(directTarget instanceof LivingEntity) && impactTarget(drone, directTarget)) {
                DamageHandler.doDamage(directTarget, ModDamageTypes.causeCustomExplosionDamage(level.registryAccess(), warhead, attacker), payload.hitDamage);
            }
            DroneBlastLimits.explode(drone, point, warhead, attacker, payload.explosionDamage, payload.explosionRadius, directTarget, damaged);
        }
        drone.discard();
    }
}
