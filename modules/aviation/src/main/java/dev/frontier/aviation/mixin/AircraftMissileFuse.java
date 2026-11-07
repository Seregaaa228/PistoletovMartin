package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import dev.frontier.aviation.missile.ManualMissileShots;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Proximity fuse for air-to-air missiles launched by our aircraft. SBW's missiles only explode
 * on a physical hit, so against a small drone a missile passing 1-2 blocks away flew on for
 * kilometres. A marked missile that passes within its fuse radius of the target it is homing on
 * (after SBW's own move and hit test this tick) bursts there through SBW's own hit path.
 * Unmarked missiles (helicopters, LAV-AD, MANPADS) are untouched.
 */
@Mixin(value = MissileProjectile.class, remap = false)
public abstract class AircraftMissileFuse {
    @Inject(method = "m_8119_()V", at = @At("TAIL"), require = 1)
    private void aviation$proximityFuse(CallbackInfo ci) {
        MissileProjectile missile = (MissileProjectile) (Object) this;
        if (!missile.isAlive() || !(missile.level() instanceof ServerLevel level)) return;
        var tag = missile.getPersistentData();
        if (!tag.contains(ManualMissileShots.AAM_FUSE)) return;
        double fuse = tag.getFloat(ManualMissileShots.AAM_FUSE);
        String id = missile.getTargetUUID();
        if (id == null || id.equals("none") || !(fuse > 0)) return;
        Entity target;
        try { target = level.getEntity(UUID.fromString(id)); }
        catch (IllegalArgumentException invalid) { return; }
        if (target == null || !target.isAlive() || target == missile.getOwner()
                || missile.getOwner() != null && target.getRootVehicle() == missile.getOwner().getRootVehicle()) return;
        Vec3 from = new Vec3(missile.xo, missile.yo, missile.zo), to = missile.position();
        var zone = target.getBoundingBox().inflate(fuse);
        if (!zone.contains(to) && zone.clip(from, to).isEmpty()) return;
        Vec3 burst = zone.clip(from, to).orElse(to);
        missile.performDamage(target, missile.getDamage(), false);
        missile.afterHitEntity(new EntityHitResult(target, burst));
        if (missile.isAlive()) missile.discard();
    }
}
