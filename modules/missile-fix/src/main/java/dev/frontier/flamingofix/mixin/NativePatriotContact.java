package dev.frontier.flamingofix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "dev.sbw.deepstrike.client.PatriotRemoteEffects", remap = false)
public interface NativePatriotContact {
    @Accessor("renderingNativeContact")
    static boolean flamingo$isRendering() { throw new AssertionError("Mixin bridge not applied"); }
    @Accessor("renderingNativeContact")
    static void flamingo$setRendering(boolean value) { throw new AssertionError("Mixin bridge not applied"); }
}
