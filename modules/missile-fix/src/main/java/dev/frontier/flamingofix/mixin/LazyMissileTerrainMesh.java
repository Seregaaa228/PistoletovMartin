package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.DeferredMissileMotion;
import dev.sbw.deepstrike.game.MissileKind;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Empty-air terrain sweeps do not need hundreds of transformed model cubes. */
@Mixin(targets = "dev.sbw.deepstrike.game.FlightCollision", remap = false)
public abstract class LazyMissileTerrainMesh {
    @Coerce
    @Redirect(method = "sweepBlocks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;)"
            + "Ldev/sbw/deepstrike/game/FlightCollision$SweepResult;",
            at = @At(value = "INVOKE", target = "Ldev/sbw/deepstrike/game/MissileShapeSweep;"
                    + "motion(Ldev/sbw/deepstrike/game/MissileKind;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/phys/Vec3;FFDLjava/util/function/Predicate;)"
                    + "Ldev/sbw/deepstrike/game/MissileShapeSweep$Mesh;"), require = 1, remap = false)
    private static Object flamingo$deferTerrainGeometry(MissileKind kind, Vec3 before, Vec3 movement,
            float yaw, float pitch, double padding, Predicate<String> visible) {
        return DeferredMissileMotion.create(kind, before, movement, yaw, pitch, padding, visible);
    }
}
