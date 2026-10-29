package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import dev.frontier.aviation.missile.ManualMissileShots;
import dev.frontier.aviation.weapons.WeaponAimResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only marked F-16 shots keep their launch lock without relying on the pilot. */
@Mixin(value = WireGuideMissileEntity.class, remap = false)
public abstract class F16WireHoming {
    @Inject(method = "m_8119_()V", at = @At(value = "INVOKE",
        target = "Lcom/atsuishio/superbwarfare/entity/projectile/WireGuideMissileEntity;mediumTrail()V",
        shift = At.Shift.AFTER), cancellable = true, require = 1)
    private void aviation$homeOnLaunchTarget(CallbackInfo callback) {
        WireGuideMissileEntity missile = (WireGuideMissileEntity) (Object) this;
        if (!(missile.level() instanceof ServerLevel level)) return;
        var tag = missile.getPersistentData();
        if (!tag.getBoolean(ManualMissileShots.LOCKED_WIRE)) return;
        Vec3 target = new Vec3(tag.getDouble("frontierTargetX"), tag.getDouble("frontierTargetY"), tag.getDouble("frontierTargetZ"));
        if (tag.hasUUID("frontierTargetEntity")) {
            // Follow native target/decoy changes and never revive a lock that
            // the native missile has already lost. Missing targets fall back
            // to their last known position without loading chunks.
            net.minecraft.world.entity.Entity entity = null;
            try {
                String current = missile.getTargetUUID();
                if (current != null && !current.equals("none")) entity = level.getEntity(java.util.UUID.fromString(current));
            } catch (IllegalArgumentException ignored) { }
            if (entity != null && entity.isAlive()) {
                target = entity.getBoundingBox().getCenter();
                tag.putDouble("frontierTargetX", target.x);
                tag.putDouble("frontierTargetY", target.y);
                tag.putDouble("frontierTargetZ", target.z);
            }
        }
        Vec3 direction = target.subtract(missile.position());
        if (WeaponAimResolver.finite(direction) && direction.lengthSqr() > 1e-10) {
            missile.setDeltaMovement(missile.getDeltaMovement().scale(.5).add(missile.getLookAngle().scale(2)));
            missile.turn(direction, Mth.clamp((missile.tickCount - 1) * .4f, 0, 6));
        }
        callback.cancel();
    }
}
