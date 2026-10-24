package dev.frontier.flamingofix.mixin;
import dev.frontier.flamingofix.client.VanillaMissileEffects;
import dev.sbw.deepstrike.network.DeepStrikeNetwork;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.sbw.deepstrike.client.MissileExplosionEffects",remap=false)
public abstract class VanillaExplosionVisuals {
    @Inject(method="accept",at=@At("HEAD"),remap=false)
    private static void flamingo$vanillaBlast(DeepStrikeNetwork.BlastEffect event,CallbackInfo c){VanillaMissileEffects.blast(event);}
    @Redirect(method="accept",at=@At(value="INVOKE",
        target="Ldev/sbw/deepstrike/client/FlightLighting;flash(Lnet/minecraft/world/phys/Vec3;FI)V"),remap=false)
    private static void flamingo$noVolumetricBlastLight(Vec3 position,float radius,int duration){}
    @Inject(method={"renderSkyGlow","renderHorizonPlumes","renderFlash","onComputeFogColor"},at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$noCustomPlume(CallbackInfo c){c.cancel();}
}
