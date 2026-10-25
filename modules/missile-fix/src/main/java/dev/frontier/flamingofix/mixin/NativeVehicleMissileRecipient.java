package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.DeferredContactIterator;
import dev.frontier.flamingofix.MissileDirectBlast;
import java.util.Iterator;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.level.ExplosionEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SBW consumes vehicle recipients to apply its own splash; that is not a protection veto. */
@Mixin(targets="com.atsuishio.superbwarfare.event.LivingEventHandler",remap=false)
public abstract class NativeVehicleMissileRecipient {
    @Inject(method="onExplosionDetonate(Lnet/minecraftforge/event/level/ExplosionEvent$Detonate;)V",
            at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void flamingo$noFabricatedKineticSplash(ExplosionEvent.Detonate event,CallbackInfo callback) {
        // The native kinetic branch has no radial Detonate stage. This new
        // contact-only event exists for protection vetoes, not SBW splash.
        if(event.getExplosion() instanceof MissileDirectBlast direct && direct.flamingo$contactOnlyEvent())callback.cancel();
    }
    @Redirect(method="onExplosionDetonate(Lnet/minecraftforge/event/level/ExplosionEvent$Detonate;)V",
            at=@At(value="INVOKE",target="Ljava/util/List;iterator()Ljava/util/Iterator;"),require=1,remap=false)
    private Iterator<Entity> flamingo$deferNativeRemoval(List<Entity> recipients,ExplosionEvent.Detonate event) {
        Iterator<Entity> original=recipients.iterator();
        return event.getExplosion() instanceof MissileDirectBlast direct
                ?new DeferredContactIterator<>(original,direct::flamingo$retainNativeVehicle):original;
    }
}
