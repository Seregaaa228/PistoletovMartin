package dev.frontier.aviation.mixin;

import com.mojang.blaze3d.Blaze3D;
import dev.frontier.aviation.control.client.FlightClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture before vanilla sensitivity, smoothing and SBW's bank-dependent look transform. */
@Mixin(value = MouseHandler.class, remap = false)
public abstract class FlightClientMouseCapture {
    @Shadow(aliases = "f_91507_") private double xpos;
    @Shadow(aliases = "f_91508_") private double ypos;
    @Shadow(aliases = "f_91511_") private boolean ignoreFirstMove;
    @Shadow(aliases = "f_91516_") private double accumulatedDX;
    @Shadow(aliases = "f_91517_") private double accumulatedDY;
    @Shadow(aliases = "f_91519_") private double lastMouseEventTime;
    @Inject(method = {"onMove(JDD)V", "m_91561_(JDD)V"}, at = @At("HEAD"), require = 1)
    private void aviation$rawMouse(long window, double x, double y, CallbackInfo ci) {
        if (window != Minecraft.getInstance().getWindow().getWindow()) return;
        if (ignoreFirstMove || !FlightClient.capturesMouse()) FlightClient.clearMouse();
        else FlightClient.rawMouse(x - xpos, y - ypos);
    }
    @Inject(method = {"turnPlayer()V", "m_91523_()V"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void aviation$pilotLook(CallbackInfo ci) {
        if (!FlightClient.capturesMouse()) return;
        accumulatedDX = accumulatedDY = 0;
        lastMouseEventTime = Blaze3D.getTime();
        ci.cancel();
    }
}
