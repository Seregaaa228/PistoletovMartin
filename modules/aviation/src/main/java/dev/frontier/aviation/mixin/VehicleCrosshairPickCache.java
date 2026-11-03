package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SBW's vehicle crosshair asks which entity the pilot looks at on every rendered frame,
 * a ray and entity search that took ~7% of the render thread in a flight profile. The
 * answer is kept for the client tick it was computed in (20 per second), like the HUD's
 * own tick-time lookup.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.client.overlay.VehicleCrosshairOverlay", remap = false)
public abstract class VehicleCrosshairPickCache {
    @Unique private static VehicleEntity aviation$vehicle;
    @Unique private static Entity aviation$viewer, aviation$target;
    @Unique private static double aviation$range;
    @Unique private static long aviation$tick = Long.MIN_VALUE;

    @Redirect(method = "render(Lcom/atsuishio/superbwarfare/client/overlay/RenderContext;)V", remap = false, require = 1,
            at = @At(value = "INVOKE", remap = false,
                target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getPlayerLookAtEntityOnVehicle(Lnet/minecraft/world/entity/Entity;DF)Lnet/minecraft/world/entity/Entity;"))
    private Entity aviation$lookAtOncePerTick(VehicleEntity vehicle, Entity viewer, double range, float partialTick) {
        long tick = ((Entity) vehicle).level().getGameTime();
        if (tick != aviation$tick || vehicle != aviation$vehicle || viewer != aviation$viewer || range != aviation$range) {
            aviation$target = vehicle.getPlayerLookAtEntityOnVehicle(viewer, range, partialTick);
            aviation$tick = tick;
            aviation$vehicle = vehicle;
            aviation$viewer = viewer;
            aviation$range = range;
        }
        return aviation$target;
    }
}
