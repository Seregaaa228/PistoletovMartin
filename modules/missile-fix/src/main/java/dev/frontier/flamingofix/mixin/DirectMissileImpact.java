package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileContactState;
import dev.frontier.flamingofix.MissileImpactConfig;
import dev.frontier.flamingofix.MissileDirectBlast;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An accepted native blast consumes its actual contact once, without another explosion. */
@Mixin(targets="dev.sbw.deepstrike.game.MissileExplosion",remap=false)
public abstract class DirectMissileImpact implements MissileDirectBlast {
    @Shadow @Final private ServerLevel world;
    @Shadow @Final private GuidedMissileEntity missile;
    @Shadow @Final private String warhead;
    @Unique private Entity flamingo$recipient;
    @Unique private boolean flamingo$vehicleHandled;
    @Unique private boolean flamingo$contactOnly;

    @Override public Entity flamingo$takeRecipient() {
        Entity target=((MissileContactState)(Object)missile).flamingo$takeDirectContact();
        flamingo$vehicleHandled=false;
        flamingo$recipient=target==null || target.level()!=world || !target.isAlive() || target.isInvulnerable() || missile.isIntercepted()
                || MissileImpactConfig.damage(missile,warhead)<=0?null:target;
        return flamingo$recipient;
    }

    @Override public boolean flamingo$retainNativeVehicle(Entity target) {
        if(target!=flamingo$recipient)return false;
        flamingo$vehicleHandled=true;return true;
    }
    @Override public boolean flamingo$finishRecipient() {
        boolean handled=flamingo$vehicleHandled;
        flamingo$recipient=null;flamingo$vehicleHandled=false;
        return handled;
    }
    @Override public boolean flamingo$contactOnlyEvent() {return flamingo$contactOnly;}

    @Override public void flamingo$hurtRecipient(Entity target) {
        if(target==null || !target.isAlive() || target.isInvulnerable() || target.level()!=world)return;
        float damage=MissileImpactConfig.damage(missile,warhead);
        Entity owner=missile.ownerId()==null?null:world.getEntity(missile.ownerId());
        if(damage>0)target.hurt(world.damageSources().explosion(missile,owner),damage);
    }

    @Inject(method="m_46061_()V",at=@At("HEAD"),remap=false,require=1)
    private void flamingo$kineticContact(CallbackInfo callback) {
        // Armed paths delegate to vanilla explode and reuse its existing event.
        // Native kinetic/EMP paths do not emit that event at all.
        if(!"none".equals(warhead) && !("graphite".equals(warhead) && !"fp5".equals(missile.kind().id)))return;
        Entity target=flamingo$takeRecipient();
        if(target==null)return;
        Explosion explosion=(Explosion)(Object)this;
        var recipients=new ArrayList<Entity>();recipients.add(target);
        boolean retained;
        flamingo$contactOnly=true;
        try {
            ForgeEventFactory.onExplosionDetonate(world,explosion,recipients,missile.kind().blastPower*2);
            retained=recipients.contains(target);
        } finally {
            flamingo$contactOnly=false;flamingo$finishRecipient();
        }
        if(retained)flamingo$hurtRecipient(target);
    }
}
