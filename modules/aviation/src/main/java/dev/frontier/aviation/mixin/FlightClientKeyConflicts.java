package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.aviation.control.client.FlightClient;
import dev.frontier.aviation.control.client.FlightKeys;
import dev.frontier.aviation.control.client.FlightKeyConflictPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyMappingLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The native edit binding yields only to an overlapping active flight control. */
@Mixin(targets = "com.atsuishio.superbwarfare.event.ClickEventHandler", remap = false)
public abstract class FlightClientKeyConflicts {
    @Unique private static final KeyMappingLookup aviation$bindings = new KeyMappingLookup();
    @Redirect(method = "onKeyPressed", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/KeyMapping;getKey()Lcom/mojang/blaze3d/platform/InputConstants$Key;"), require = 1)
    private InputConstants.Key aviation$editBinding(KeyMapping key) {
        InputConstants.Key binding = key.getKey();
        if (key == ModKeyMappings.EDIT_MODE && FlightClient.suppressNativeInput()
            && Minecraft.getInstance().screen == null && !binding.equals(InputConstants.UNKNOWN)) {
            // Forge gives active modified mappings priority over unmodified ones.
            // Query the same lookup used to dispatch this key, rather than only comparing codes.
            var active = aviation$bindings.getAll(binding);
            if (FlightKeyConflictPolicy.suppress(true, true, active.contains(FlightKeys.GEAR),
                active.contains(FlightKeys.HOVER))) return InputConstants.UNKNOWN;
        }
        return binding;
    }
}
