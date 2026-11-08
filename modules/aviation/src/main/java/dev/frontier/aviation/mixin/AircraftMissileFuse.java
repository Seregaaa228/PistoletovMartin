package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.world.phys.EntityResult;
import com.atsuishio.superbwarfare.world.phys.ExtendedEntityRayTraceResult;
import dev.frontier.aviation.missile.ManualMissileShots;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Proximity fuse for air-to-air missiles launched by our aircraft. SBW's missiles only explode
 * on a physical hit, so against a small drone a missile passing 1-2 blocks away flew on for
 * kilometres. A marked missile that passes within its fuse radius of the target it is homing on
 * (after SBW's own move and hit test this tick) is handed to SBW's own entity hit: the cancellable
 * ProjectileHitEvent, part-to-parent redirect, one hit's damage and the burst. The fuse arms only
 * after SBW's no-hit window and never bursts beside the shooter's own aircraft.
 * Unmarked missiles (helicopters, LAV-AD, MANPADS) are untouched.
 */
@Mixin(value = MissileProjectile.class, remap = false)
public abstract class AircraftMissileFuse {
    @Unique private static final int AVIATION$ARM_TICKS = 5;

    @Inject(method = "m_8119_()V", at = @At("TAIL"), require = 1)
    private void aviation$proximityFuse(CallbackInfo ci) {
        MissileProjectile missile = (MissileProjectile) (Object) this;
        if (!missile.isAlive() || !(missile.level() instanceof ServerLevel level)) return;
        if (missile.tickCount <= Math.max(AVIATION$ARM_TICKS, missile.getNoHitTicks())) return;
        var tag = missile.getPersistentData();
        if (!tag.contains(ManualMissileShots.AAM_FUSE)) return;
        double fuse = tag.getFloat(ManualMissileShots.AAM_FUSE);
        String id = missile.getTargetUUID();
        if (id == null || id.equals("none") || !(fuse > 0)) return;
        Entity target;
        try { target = level.getEntity(UUID.fromString(id)); }
        catch (IllegalArgumentException invalid) { return; }
        Entity owner = missile.getOwner();
        if (target == null || !target.isAlive() || target == owner
                || owner != null && target.getRootVehicle() == owner.getRootVehicle()) return;
        Vec3 from = new Vec3(missile.xo, missile.yo, missile.zo), to = missile.position();
        var zone = target.getBoundingBox().inflate(fuse);
        Vec3 burst = zone.contains(to) ? to : zone.clip(from, to).orElse(null);
        if (burst == null) return;
        if (owner != null && owner.getRootVehicle().getBoundingBox().inflate(fuse).contains(burst)) return;
        missile.onHit(new ExtendedEntityRayTraceResult(new EntityResult(target, burst, false, false)));
    }
}
