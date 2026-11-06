package dev.frontier.nations.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;

/**
 * Pinned Xaero 1.47.0: its per-frame processor writes the map around the player
 * (sampling blocks and biomes) and uploads the changed region textures, ~14% of
 * render-thread time in a 32-chunk flight and a quarter of the stalls. Skipping frames
 * does not help, the writer's budget grows with the time since its last write. So the
 * whole frame step pauses while the player rides a vehicle with no screen open; on foot,
 * or with the map or a DeepStrike tablet open, it runs as usual.
 */
@Mixin(value=MapProcessor.class,remap=false)
public abstract class XaeroVehiclePauseMixin {
    @Inject(method="onRenderProcess(Lnet/minecraft/client/Minecraft;)V",at=@At("HEAD"),cancellable=true)
    private void frontier$pauseWhileRiding(Minecraft minecraft,CallbackInfo ci){
        if(minecraft.screen==null&&minecraft.player!=null&&minecraft.player.getVehicle()!=null)ci.cancel();
    }
}
