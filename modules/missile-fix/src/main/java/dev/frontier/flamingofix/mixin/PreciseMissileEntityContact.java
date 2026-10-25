package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.NativeMissileSweep;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import com.atsuishio.superbwarfare.entity.OBBEntity;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep native blocks, ordering, filters and MANPAD logic; refine guided bodies only. */
@Mixin(targets = "dev.sbw.deepstrike.game.FlightCollision", remap = false)
public abstract class PreciseMissileEntityContact {
    private static final String SWEEP = "sweepSegment(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
            + "Ljava/util/function/Predicate;DDLnet/minecraft/world/entity/Entity;)"
            + "Ldev/sbw/deepstrike/game/FlightCollision$SweepResult;";

    @Redirect(method=SWEEP,at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;"
            +"m_6249_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"),
            require=1,remap=false)
    private static List<Entity> flamingo$vehicleCandidates(ServerLevel level,Entity projectile,AABB nativeQuery,Predicate<Entity> filter) {
        if(!(projectile instanceof GuidedMissileEntity))return level.getEntities(projectile,nativeQuery,filter);
        // The pinned vehicle assets fit inside a rotation-independent 48-block
        // radius. This index query never loads terrain; only real OBB targets
        // gain wider admission, and native exclusions still run unchanged.
        return level.getEntities(projectile,nativeQuery.inflate(NativeMissileSweep.MAX_VEHICLE_REACH),
                target->(target instanceof OBBEntity || target.getBoundingBox().intersects(nativeQuery)) && filter.test(target));
    }

    @Redirect(method=SWEEP,at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;"
            +"m_20191_()Lnet/minecraft/world/phys/AABB;"),require=1,remap=false)
    private static AABB flamingo$currentTarget(Entity target,ServerLevel level,Entity projectile,Vec3 from,Vec3 to,
            AABB body,Predicate<Entity> filter,double targetStart,double targetSpan,Entity observedTarget) {
        if(projectile instanceof GuidedMissileEntity)NativeMissileSweep.target(target);
        return target.getBoundingBox();
    }

    @Redirect(method = SWEEP, at = @At(value = "INVOKE",
            target = "Ldev/sbw/deepstrike/game/FlightCollision;contact(Lnet/minecraft/world/phys/AABB;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            require = 1, remap = false)
    private static Vec3 flamingo$bodyContact(AABB expanded, Vec3 from, Vec3 to,
            ServerLevel level, Entity projectile, Vec3 sweepFrom, Vec3 sweepTo, AABB body,
            Predicate<Entity> filter, double targetStart, double targetSpan, Entity observedTarget) {
        if (projectile instanceof GuidedMissileEntity missile) {
            return NativeMissileSweep.contact(missile, expanded, body, from, to);
        }
        return expanded.contains(from) ? from : expanded.clip(from, to).orElse(null);
    }

    @Inject(method = SWEEP, at = @At("RETURN"), require = 1, remap = false)
    private static void flamingo$releaseShape(CallbackInfoReturnable<?> callback) {
        NativeMissileSweep.clear();
    }
}
