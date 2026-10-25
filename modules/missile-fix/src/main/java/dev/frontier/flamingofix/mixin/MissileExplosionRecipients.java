package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileDirectBlast;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reuse the native vanilla-stage recipient event, including its protection veto. */
@Mixin(value=Explosion.class,remap=false)
public abstract class MissileExplosionRecipients {
    @Redirect(method="m_46061_()V",at=@At(value="INVOKE",target="Lnet/minecraftforge/event/ForgeEventFactory;"
            +"onExplosionDetonate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;Ljava/util/List;D)V"),
            remap=false,require=1)
    private void flamingo$acceptedRecipients(Level level,Explosion explosion,List<Entity> recipients,double diameter) {
        MissileDirectBlast direct=explosion instanceof MissileDirectBlast missile?missile:null;
        Entity contact=direct==null?null:direct.flamingo$takeRecipient();
        if(contact!=null && !recipients.contains(contact))recipients.add(contact);
        ForgeEventFactory.onExplosionDetonate(level,explosion,recipients,diameter);
        if(contact!=null) {
            boolean retained=recipients.contains(contact);
            // SBW has already applied this vehicle's native splash in its event
            // adapter. Defer its normal removal only until protection handlers
            // can veto contact, then prevent a second vanilla splash pass.
            if(direct.flamingo$finishRecipient())recipients.remove(contact);
            if(retained)direct.flamingo$hurtRecipient(contact);
        }
    }
}
