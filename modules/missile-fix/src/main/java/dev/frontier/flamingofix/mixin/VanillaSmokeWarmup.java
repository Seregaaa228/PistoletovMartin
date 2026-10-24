package dev.frontier.flamingofix.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.sbw.deepstrike.client.LaunchEffectWarmup",remap=false)
public abstract class VanillaSmokeWarmup {
    @Inject(method="accept",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$noCustomSmokeWarmup(CallbackInfo c){c.cancel();}
}
