package dev.frontier.nations.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapWriter;
import xaero.map.biome.BiomeColorCalculator;
import xaero.map.region.OverlayManager;

/**
 * Pinned Xaero 1.47.0: its map writer samples blocks and biomes around the player on
 * every frame, ~14% of render-thread time in a 32-chunk flight and a quarter of the
 * stalls. Skipping frames does not help, the writer's budget grows with the time since
 * its last write (up to ~4 ms). So it pauses while the player rides a vehicle with no
 * screen open; on foot, or with the map or a DeepStrike tablet open, it writes as usual.
 */
@Mixin(value=MapWriter.class,remap=false)
public abstract class XaeroWriterVehiclePauseMixin {
    @Inject(method="onRender(Lxaero/map/biome/BiomeColorCalculator;Lxaero/map/region/OverlayManager;)V",at=@At("HEAD"),cancellable=true)
    private void frontier$pauseWhileRiding(BiomeColorCalculator biomes,OverlayManager overlays,CallbackInfo ci){
        var minecraft=Minecraft.getInstance();
        if(minecraft.screen==null&&minecraft.player!=null&&minecraft.player.getVehicle()!=null)ci.cancel();
    }
}
