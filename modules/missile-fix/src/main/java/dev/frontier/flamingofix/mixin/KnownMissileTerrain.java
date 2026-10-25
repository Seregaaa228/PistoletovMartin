package dev.frontier.flamingofix.mixin;

import dev.sbw.deepstrike.game.FlightCollision;
import dev.frontier.flamingofix.ImpactContact;
import dev.frontier.flamingofix.MissileContactState;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Unknown terrain must not silently become air, nor hide a nearer blocking wall. */
@Mixin(targets = "dev.sbw.deepstrike.game.GuidedMissileEntity", remap = false)
public abstract class KnownMissileTerrain implements MissileContactState {
    @Unique private FlightCollision.SweepResult flamingo$contact;
    @Unique private Vec3 flamingo$priorVelocity;
    @Unique private final ImpactContact<Entity> flamingo$impact=new ImpactContact<>();
    @Unique private Entity flamingo$candidate;
    @Shadow private void detonate() { throw new AssertionError(); }

    @Override public Entity flamingo$takeDirectContact() { return flamingo$impact.take(); }

    @Inject(method = "advanceFlight(Lnet/minecraft/world/phys/Vec3;)Z", at = @At("HEAD"), remap = false)
    private void flamingo$rememberVelocity(Vec3 movement, CallbackInfoReturnable<Boolean> callback) {
        flamingo$priorVelocity = ((Entity) (Object) this).getDeltaMovement();
        flamingo$candidate=null;
    }

    @Redirect(method = "advanceFlight(Lnet/minecraft/world/phys/Vec3;)Z",
            at = @At(value = "INVOKE", target = "Ldev/sbw/deepstrike/game/FlightCollision;"
                    + "sweepSegment(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;)Ldev/sbw/deepstrike/game/FlightCollision$SweepResult;"),
            require = 1, remap = false)
    private FlightCollision.SweepResult flamingo$rememberContact(ServerLevel level, Entity entity,
            Vec3 from, Vec3 to, AABB body, Predicate<Entity> filter) {
        flamingo$contact = FlightCollision.sweepSegment(level, entity, from, to, body, filter);
        return flamingo$contact;
    }

    @Inject(method = "advanceFlight(Lnet/minecraft/world/phys/Vec3;)Z", at = @At(value = "INVOKE",
            target = "Ldev/sbw/deepstrike/game/FlightCollision$SweepResult;hit()Lnet/minecraft/world/phys/HitResult;"),
            cancellable = true, require = 1, remap = false)
    private void flamingo$waitForTerrain(Vec3 movement, CallbackInfoReturnable<Boolean> callback) {
        if (!flamingo$contact.terrainKnownThroughContact()) {
            // FlightTerrain already owns and asynchronously loads this corridor.
            // Retry next tick without consuming propulsion state or a route point.
            ((Entity) (Object) this).setDeltaMovement(flamingo$priorVelocity);
            callback.setReturnValue(false);
        } else if(flamingo$contact.hit() instanceof EntityHitResult hit) {
            flamingo$candidate=hit.getEntity();
        }
        flamingo$contact = null;
    }

    @Redirect(method="advanceFlight(Lnet/minecraft/world/phys/Vec3;)Z",
            at=@At(value="INVOKE",target="Ldev/sbw/deepstrike/game/GuidedMissileEntity;detonate()V"),
            remap=false,require=1)
    private void flamingo$actualContact(GuidedMissileEntity missile) {
        // This branch is reached only after native own-launch grace, nearest
        // block ordering and setPos(hit) have accepted a real contact.
        flamingo$impact.record(flamingo$candidate);
        flamingo$candidate=null;
        detonate();
    }
}
