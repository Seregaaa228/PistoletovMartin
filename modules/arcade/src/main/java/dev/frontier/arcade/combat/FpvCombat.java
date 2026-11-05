package dev.frontier.arcade.combat;

import com.atsuishio.superbwarfare.data.CustomData;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.fpv.FpvExplosion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
        var hit = DroneSweep.trace(drone, from, from.add(movement), target -> impactTarget(drone, target));
        if (hit == null) return false;
        detonate(drone, hit.getLocation(), hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null);
        return true;
    }
    public static void detonate(FpvDroneEntity drone, Vec3 point, Entity directTarget) {
        if (!(drone.level() instanceof ServerLevel level) || drone.isRemoved()
            || !((Object)drone instanceof DroneImpactState state) || !state.frontier$claimImpact()) return;
        ControlSessions.rememberNativeOwner(drone);
        var owner = ControlSessions.ownerId(drone);
        String payloadId = com.atsuishio.superbwarfare.entity.vehicle.DroneEntity.getItemId(drone.getCurrentItem());
        var payload = armed(drone) ? CustomData.DRONE_ATTACHMENT.get(payloadId) : null;
        // Spend before callbacks can re-enter destroy/hitEntityCrash. Never fabricate an unloaded warhead.
        drone.setAmmo(0); drone.setFire(false);
        var operator = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        ControlSessions.forgetPair(drone); drone.handleSignalLoss(operator);
        if (owner != null && payload != null) {
            FpvExplosion.detonate(drone, point, operator);
        }
        drone.discard();
    }
}
