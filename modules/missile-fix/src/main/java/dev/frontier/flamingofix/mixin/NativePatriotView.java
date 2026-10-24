package dev.frontier.flamingofix.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "dev.sbw.deepstrike.client.PatriotThermalCamera", remap = false)
public interface NativePatriotView {
    @Invoker("isTracked")
    static boolean flamingo$isTracked(UUID identity, int entityId) {
        throw new AssertionError("Mixin bridge not applied");
    }
}
