package dev.frontier.flamingofix.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.sbw.deepstrike.client.Fp5LaunchEffects",remap=false)
public abstract class VanillaBoosterFlame {
    @Inject(method="render",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$vanillaFlame(CallbackInfo c){c.cancel();}
}
