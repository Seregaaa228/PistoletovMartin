package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.client.FlightUiLines;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Map lines and range rings as quads in one batch instead of one flushed fill per pixel; same pixels, same order. */
@Mixin(targets = "dev.sbw.deepstrike.client.FlightUi", remap = false)
public abstract class FastFlightUiLines {
    @Inject(method = "circle(Lnet/minecraft/client/gui/GuiGraphics;IIFIZ)V", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$managedCircle(GuiGraphics g, int x, int y, float radius, int color, boolean dashed, CallbackInfo c) {
        if (FlightUiLines.circle(g, x, y, radius, color, dashed)) c.cancel();
    }

    @Inject(method = "line(Lnet/minecraft/client/gui/GuiGraphics;IIIII)V", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void flamingo$quadLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color, CallbackInfo c) {
        FlightUiLines.line(g, x0, y0, x1, y1, color);
        c.cancel();
    }
}
