package dev.frontier.aviation.mixin;

import dev.frontier.aviation.AircraftIdentity;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Derive DSM's own-plane marker and tablet heading from the existing registry type. */
@Mixin(targets = "dev.sbw.deepstrike.client.Su24mScreen", remap = false)
public abstract class AircraftMapIdentity {
    @Shadow @Final private int vehicle;

    @ModifyConstant(method = "drawOverlay", constant = @Constant(stringValue = "SU-24M"), remap = false)
    private String aviation$profileLabel(String original) {
        var level = Minecraft.getInstance().level;
        if (level == null) return original;
        return AircraftIdentity.mapLabel(level.getEntity(vehicle), original);
    }

    // The first FlightUi.label in drawOverlay is tr("title"), whose native
    // translation always names Su-24M. Keep every other native map label.
    @ModifyArg(method = "drawOverlay", index = 1, remap = false,
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ldev/sbw/deepstrike/client/FlightUi;label(Lnet/minecraft/client/gui/GuiGraphics;Ljava/lang/String;III)V"))
    private String aviation$profileHeading(String original) {
        var level = Minecraft.getInstance().level;
        if (level == null) return original;
        return AircraftIdentity.mapHeading(level.getEntity(vehicle), original);
    }
}
