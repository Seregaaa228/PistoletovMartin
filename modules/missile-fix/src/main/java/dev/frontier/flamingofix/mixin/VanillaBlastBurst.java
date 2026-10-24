package dev.frontier.flamingofix.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.sbw.deepstrike.client.MissileExplosionEffects$Burst",remap=false)
public abstract class VanillaBlastBurst {
    @Shadow private int age;
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,remap=false)
    private void flamingo$noCustomBurst(CallbackInfo c){age=96;c.cancel();}
}
