package dev.frontier.arcade.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compatibility for native non-player drivers; task assignment belongs to the NPC addon. */
@Pseudo
@Mixin(targets="com.crescentine.trajanscore.basetank.BaseTankEntity",remap=false)
public abstract class TankNpcSafety {
    @Shadow private void controlTank() { throw new AssertionError(); }

    @Inject(method="m_7334_",at=@At("HEAD"),cancellable=true,remap=false)
    private void frontier$guardCollision(Entity other,CallbackInfo callback) {
        Entity self=(Entity)(Object)this;
        if(!(other instanceof LivingEntity)
            ||(self.getControllingPassenger()!=null&&!(self.getControllingPassenger() instanceof Player))) {
            callback.cancel();
        }
    }

    @Redirect(method="m_8119_",at=@At(value="INVOKE",target="Lcom/crescentine/trajanscore/basetank/BaseTankEntity;m_6478_(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"),remap=false)
    private void frontier$moveNpc(@Coerce Object target,MoverType type,Vec3 movement) {
        Entity self=(Entity)target;
        if(!self.level().isClientSide&&self.getControllingPassenger() instanceof Mob) {
            controlTank();
            movement=self.getDeltaMovement();
        }
        self.move(type,movement);
    }

    @Unique
    public boolean frontierSupportsNpcDriver() { return true; }
}
