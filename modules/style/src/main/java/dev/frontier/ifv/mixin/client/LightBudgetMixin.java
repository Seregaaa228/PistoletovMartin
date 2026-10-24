package dev.frontier.ifv.mixin.client;

import com.atsuishio.superbwarfare.client.lighting.LightPositionRegistry;
import dev.frontier.ifv.client.StyleGraphics;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Central registry guard covers native muzzle, vehicle and projectile emitters. */
@Mixin(value = LightPositionRegistry.class, remap = false)
public abstract class LightBudgetMixin {
    @Shadow @Final private static LongOpenHashSet active;

    @Inject(method = "putSpark(JIII)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void frontierStyle$spark(long packedPos, int maxLevel, int minLevel, int ttlTicks,
                                           CallbackInfo callback) {
        if (!StyleGraphics.allowSpark(packedPos, maxLevel, ttlTicks, active)) callback.cancel();
    }

    @Inject(method = "putSpark(JIII)V", at = @At("RETURN"), remap = false)
    private static void frontierStyle$sparkAccepted(long packedPos, int maxLevel, int minLevel, int ttlTicks,
                                                   CallbackInfo callback) {
        StyleGraphics.sparkReturned(packedPos, active);
    }

    @Inject(method = "putSparkRadius(Lnet/minecraft/core/BlockPos;IIII)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void frontierStyle$radius(BlockPos center, int maxLevel, int minLevel, int ttlTicks,
                                            int radius, CallbackInfo callback) {
        if (StyleGraphics.skipSparkRadius(center, maxLevel, ttlTicks)) callback.cancel();
    }
}
