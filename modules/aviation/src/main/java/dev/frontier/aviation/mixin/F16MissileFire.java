package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.missile.MissileTargetRules;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

/** Only the F-16's selected missile may bypass DSM's cannon-only override. */
@Mixin(value = Su24mEntity.class, remap = false)
public abstract class F16MissileFire extends VehicleEntity {
    @Unique private long aviation$lastMissileTick = Long.MIN_VALUE;
    @Unique private final MissileTargetRules.AttemptLimiter aviation$attempts = new MissileTargetRules.AttemptLimiter();

    protected F16MissileFire(EntityType<?> type, Level level) { super(type, level); }

    @Unique private boolean aviation$selectedMissile(LivingEntity pilot) {
        return AircraftProfile.of(this) == AircraftProfile.F16 && pilot != null
                && pilot.isAlive() && isAlive() && pilot.getVehicle() == this
                && getSeatIndex(pilot) == 0
                && "Missile".equals(getGunName(0));
    }

    @Inject(method = "canShoot(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void aviation$missileCanShoot(LivingEntity pilot, CallbackInfoReturnable<Boolean> result) {
        if (aviation$selectedMissile(pilot)) result.setReturnValue(super.canShoot(pilot));
    }

    @Inject(method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void aviation$missileShoot(LivingEntity pilot, String weapon, Vec3 ignored, CallbackInfo result) {
        if (AircraftProfile.of(this) == AircraftProfile.F16 && "Missile".equals(weapon)) {
            result.cancel();
            aviation$launch(pilot, ignored);
        }
    }

    @Inject(method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void aviation$missileShootTarget(LivingEntity pilot, String weapon, UUID ignoredId, Vec3 ignored, CallbackInfo result) {
        if (AircraftProfile.of(this) == AircraftProfile.F16 && "Missile".equals(weapon)) {
            result.cancel();
            aviation$launch(pilot, ignored);
        }
    }

    @Inject(method = "vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void aviation$selectedMissileShoot(LivingEntity pilot, UUID ignoredId, Vec3 ignored, CallbackInfo result) {
        if (AircraftProfile.of(this) == AircraftProfile.F16 && "Missile".equals(getGunName(0))) {
            result.cancel();
            aviation$launch(pilot, ignored);
        }
    }

    @Unique private void aviation$launch(LivingEntity pilot, Vec3 claimed) {
        if (!(pilot instanceof ServerPlayer serverPilot) || level().isClientSide || !aviation$selectedMissile(pilot)
                || !((Su24mEntity) (Object) this).canOperate(serverPilot, true)
                || !super.canShoot(pilot)) return;
        long now = level().getGameTime();
        if (aviation$lastMissileTick != Long.MIN_VALUE && now - aviation$lastMissileTick < 20) return;
        if (!aviation$attempts.allow(now)) return;
        Vec3 target = aviation$groundTarget(pilot, claimed);
        if (target == null) return;
        aviation$lastMissileTick = now;
        super.vehicleShoot(pilot, "Missile", null, target);
    }

    /** The packet point is a proposal: verify range, sector and the first server-side solid hit. */
    @Unique private Vec3 aviation$groundTarget(LivingEntity pilot, Vec3 claimed) {
        if (claimed == null || !Double.isFinite(claimed.x) || !Double.isFinite(claimed.y)
                || !Double.isFinite(claimed.z)) return null;
        Vec3 from = getShootPosForHud(pilot, 1.0F);
        Vec3 forward = getViewVector(1.0F);
        if (!Double.isFinite(from.x) || !Double.isFinite(from.y) || !Double.isFinite(from.z)
                || !Double.isFinite(forward.x) || !Double.isFinite(forward.y) || !Double.isFinite(forward.z)) return null;
        if (!level().getWorldBorder().isWithinBounds(BlockPos.containing(claimed))
                || level().isOutsideBuildHeight(BlockPos.containing(claimed))) return null;
        Vec3 offset = claimed.subtract(from);
        double length = offset.length();
        if (!MissileTargetRules.eligible(offset.x, offset.y, offset.z, forward.x, forward.y, forward.z)) return null;
        Vec3 direction = offset.scale(1.0 / length);
        double remaining = Math.min(length + 0.75, 256.75);
        while (remaining > 0) {
            double step = Math.min(1.0, remaining);
            Vec3 next = from.add(direction.scale(step));
            if (!level().hasChunkAt(BlockPos.containing(from)) || !level().hasChunkAt(BlockPos.containing(next))
                    || !level().hasChunkAt(BlockPos.containing(from.x, from.y, next.z))
                    || !level().hasChunkAt(BlockPos.containing(next.x, next.y, from.z))) return null;
            var hit = level().clip(new ClipContext(from, next, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, pilot));
            var obstruction = level().clip(new ClipContext(from, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pilot));
            if (obstruction.getType() == HitResult.Type.BLOCK && (hit.getType() != HitResult.Type.BLOCK
                    || from.distanceToSqr(obstruction.getLocation()) + 1.0e-8 < from.distanceToSqr(hit.getLocation()))) return null;
            if (hit.getType() == HitResult.Type.BLOCK)
                return MissileTargetRules.closeToHit(hit.getLocation().x - claimed.x, hit.getLocation().y - claimed.y, hit.getLocation().z - claimed.z) ? hit.getLocation() : null;
            from = next;
            remaining -= step;
        }
        return null;
    }
}
