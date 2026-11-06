package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.client.LookAtPickCache;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The aircraft HUD's per-tick look-at search, shared with the crosshair ({@link LookAtPickCache}). */
@Mixin(targets = "com.atsuishio.superbwarfare.client.overlay.weapon.AircraftHud", remap = false)
public abstract class AircraftHudPickCache {
    @Redirect(method = "onAircraftHudClientTick(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V", remap = false, require = 1,
            at = @At(value = "INVOKE", remap = false,
                target = "Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;getPlayerLookAtEntityOnVehicle(Lnet/minecraft/world/entity/Entity;DF)Lnet/minecraft/world/entity/Entity;"))
    private Entity aviation$sharedLookAt(VehicleEntity vehicle, Entity viewer, double range, float partialTick) {
        return LookAtPickCache.lookAt(vehicle, viewer, range, partialTick);
    }
}
