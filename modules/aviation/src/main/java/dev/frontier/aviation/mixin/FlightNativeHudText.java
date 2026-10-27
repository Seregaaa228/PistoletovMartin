package dev.frontier.aviation.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Translate the native aircraft HUD labels without changing its rendering. */
@Mixin(targets = "com.atsuishio.superbwarfare.client.overlay.weapon.AircraftHud", remap = false)
public abstract class FlightNativeHudText {
    @ModifyConstant(method = "render(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/entity/player/Player;Lnet/minecraftforge/client/gui/overlay/ForgeGui;Lnet/minecraft/client/gui/GuiGraphics;FII)V",
        constant = @Constant(stringValue = "NO POWER!"), require = 1, allow = 1, remap = false)
    private String aviation$noPower(String original) {
        return Component.translatable("hud.frontier_aviation.no_power").getString();
    }

    @ModifyConstant(method = "render(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/entity/player/Player;Lnet/minecraftforge/client/gui/overlay/ForgeGui;Lnet/minecraft/client/gui/GuiGraphics;FII)V",
        constant = @Constant(stringValue = "LOW POWER"), require = 1, allow = 1, remap = false)
    private String aviation$lowPower(String original) {
        return Component.translatable("hud.frontier_aviation.low_power").getString();
    }

    @ModifyConstant(method = "render(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;Lnet/minecraft/world/entity/player/Player;Lnet/minecraftforge/client/gui/overlay/ForgeGui;Lnet/minecraft/client/gui/GuiGraphics;FII)V",
        constant = @Constant(stringValue = "TGT"), require = 1, allow = 1, remap = false)
    private String aviation$target(String original) {
        return Component.translatable("hud.frontier_aviation.target").getString();
    }
}
