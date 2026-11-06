package dev.frontier.aviation.mixin;

import dev.frontier.aviation.client.F16MissileTriggerClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels the missile gesture at the native Minecraft focus transition. */
@Mixin(value = Minecraft.class, remap = false)
public abstract class F16MissileWindowActive {
    @Inject(method = {"setWindowActive(Z)V", "m_7440_(Z)V"}, at = @At("HEAD"), require = 1)
    private void aviation$cancelAtFocusChange(boolean active, CallbackInfo ci) {
        if (!active) F16MissileTriggerClient.cancelGesture();
    }
}
