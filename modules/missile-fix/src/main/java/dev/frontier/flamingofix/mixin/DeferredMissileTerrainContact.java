package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.DeferredMissileMesh;
import dev.frontier.flamingofix.DeferredMissileMotion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Delegates a deferred marker to the unchanged native mesh on first contact. */
@Mixin(targets = "dev.sbw.deepstrike.game.MissileShapeSweep$Mesh", remap = false)
public abstract class DeferredMissileTerrainContact implements DeferredMissileMesh {
    @Unique private DeferredMissileMotion frontier$deferredMotion;

    @Override
    public void frontier$defer(DeferredMissileMotion motion) {
        if (frontier$deferredMotion != null) throw new IllegalStateException("Terrain mesh already deferred");
        frontier$deferredMotion = motion;
    }

    @Inject(method = "contact(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/world/phys/Vec3;)Ldev/sbw/deepstrike/game/MissileShapeSweep$Contact;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void flamingo$exactDeferredContact(AABB obstacle, Vec3 from, Vec3 to, CallbackInfoReturnable<Object> callback) {
        if (frontier$deferredMotion != null) callback.setReturnValue(frontier$deferredMotion.contact(obstacle, from, to));
    }
}
