package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.aviation.control.client.FlightClient;
import dev.frontier.aviation.control.client.FlightKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep native gear and hover hints in sync with this mod's pilot bindings. */
@Mixin(targets = "com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay", remap = false)
public abstract class FlightNativeHudKeys {
    @Redirect(method = "renderGearInfo(Lnet/minecraft/client/gui/GuiGraphics;Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;IIFI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;getKey()Lcom/mojang/blaze3d/platform/InputConstants$Key;"), require = 2, allow = 2)
    private InputConstants.Key aviation$gearHint(KeyMapping original, GuiGraphics graphics, VehicleEntity vehicle,
                                                 int width, int height, float partialTick, int armorOffset) {
        return FlightClient.controlledEntity() == vehicle ? FlightKeys.GEAR.getKey() : original.getKey();
    }

    @Redirect(method = "renderHoverInfo(Lnet/minecraft/client/gui/GuiGraphics;Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;IIFI)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;getKey()Lcom/mojang/blaze3d/platform/InputConstants$Key;"), require = 2, allow = 2)
    private InputConstants.Key aviation$hoverHint(KeyMapping original, GuiGraphics graphics, VehicleEntity vehicle,
                                                  int width, int height, float partialTick, int armorOffset) {
        return FlightClient.controlledEntity() == vehicle ? FlightKeys.HOVER.getKey() : original.getKey();
    }
}
