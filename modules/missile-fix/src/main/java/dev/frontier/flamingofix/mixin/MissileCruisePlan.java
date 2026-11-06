package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.LauncherPlanning;
import dev.frontier.flamingofix.MissileCruiseProfile;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.game.SimpleFlightPlan;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The launcher's route planner builds its path from the climb-cruise-dive mission (MissileCruiseProfile). */
@Mixin(targets = "dev.sbw.deepstrike.game.SimpleFlightPlan", remap = false)
public abstract class MissileCruisePlan {
    @Unique private static final ThreadLocal<Boolean> flamingo$SHAPING = ThreadLocal.withInitial(() -> false);

    @Inject(method = "build(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Ldev/sbw/deepstrike/game/MissileKind;Ljava/util/List;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$cruiseProfile(Vec3 start, Vec3 forward, MissileKind kind, List<RoutePoint> requested,
            CallbackInfoReturnable<List<RoutePoint>> result) {
        ServerLevel level = LauncherPlanning.current();
        if (level == null || flamingo$SHAPING.get() || !MissileCruiseProfile.applies(kind) || requested == null || requested.isEmpty()) return;
        RoutePoint target = requested.get(requested.size() - 1);
        List<RoutePoint> shaped = MissileCruiseProfile.shape(start, forward, kind, requested, (x, z) -> {
            // Loaded terrain only (DeepStrike loads the flight corridor itself); unknown counts as the target's height.
            int bx = Mth.floor(x), bz = Mth.floor(z);
            var chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            return chunk == null ? target.y() - 1 : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
        });
        if (shaped == requested) return;
        flamingo$SHAPING.set(true);
        try {
            result.setReturnValue(SimpleFlightPlan.build(start, forward, kind, shaped));
        } finally {
            flamingo$SHAPING.set(false);
        }
    }
}
