package dev.frontier.aviation.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.aviation.control.client.FlightClient;
import dev.frontier.aviation.control.client.FlightKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyMappingLookup;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consume only the Curios click that Forge dispatched alongside a pilot's gear key. */
@Pseudo
@Mixin(targets = "top.theillusivec4.curios.client.ClientEventHandler", remap = false)
public abstract class FlightCuriosKeyConflict {
    @Unique private static final KeyMappingLookup aviation$bindings = new KeyMappingLookup();

    @Inject(method = "onKeyInput(Lnet/minecraftforge/client/event/InputEvent$Key;)V", at = @At("HEAD"), require = 1)
    private void aviation$consumeConflictingCurios(InputEvent.Key event, CallbackInfo ci) {
        if ((event.getAction() == GLFW.GLFW_PRESS || event.getAction() == GLFW.GLFW_REPEAT)
            && Minecraft.getInstance().screen == null
            && FlightClient.suppressNativeInput()) {
            InputConstants.Key pressed = InputConstants.getKey(event.getKey(), event.getScanCode());
            var selected = aviation$bindings.getAll(pressed);
            if (selected.contains(FlightKeys.GEAR)) {
                for (KeyMapping binding : selected) {
                    if (binding.getName().equals("key.curios.open.desc")) binding.consumeClick();
                }
            }
        }
    }
}
