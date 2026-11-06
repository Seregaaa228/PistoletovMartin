package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.client.LookAtPickCache;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SBW's vehicle crosshair asks which entity the pilot looks at on every rendered frame,
 * a ray and entity search that took ~7% of the render thread in a flight profile. The
 * answer is shared per client tick with the aircraft HUD, which asks the same question
 * (see {@link LookAtPickCache}).
 */
@Mixin(targets = "com.atsuishio.superbwarfare.client.overlay.VehicleCrosshairOverlay", remap = false)
public abstract class VehicleCrosshairPickCache {
    @Redirect(method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V", remap = false, require = 1,
            at = @At(value = "INVOKE", remap = false,
                target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getPlayerLookAtEntityOnVehicle(Lnet/minecraft/world/entity/Entity;DF)Lnet/minecraft/world/entity/Entity;"))
    private Entity aviation$lookAtOncePerTick(VehicleEntity vehicle, Entity viewer, double range, float partialTick) {
        return LookAtPickCache.lookAt(vehicle, viewer, range, partialTick);
    }
}
