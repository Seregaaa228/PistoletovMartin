package dev.frontier.arcade.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Restores the removed SBW 0.8.9 signature used by Superb Recruits 1.115. */
@Mixin(targets="com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity", remap=false)
public abstract class SbwRecruitCompatibility {
    @Shadow public abstract void vehicleShoot(LivingEntity living, String weaponName, Vec3 targetPos);
    @Shadow public abstract int vehicleWeaponRpm(String weaponName);
    @Shadow public abstract int getSeatIndex(Entity entity);
    @Shadow public abstract String getGunName(int seatIndex);
    @Shadow public abstract String getGunName(int seatIndex,int weaponIndex);
    @Unique private final Map<String,Long> frontier$nextNpcShot=new HashMap<>();

    @Unique
    public void vehicleShoot(LivingEntity living, String weaponName) {
        // The upstream goal iterates every weapon, including unoccupied passenger stations.
        if(living instanceof Mob) {
            int seat=getSeatIndex(living);
            boolean allowed=false;
            for(int i=0;i<32;i++) {
                String name=getGunName(seat,i);
                if(name==null)break;
                if(name.equals(weaponName)){allowed=true;break;}
            }
            if(!allowed)return;
        }
        vehicleShoot(living, weaponName, null);
    }

    @Unique private boolean frontier$allowNpcShot(LivingEntity living,String weaponName) {
        if(!(living instanceof Mob))return true;
        Entity self=(Entity)(Object)this;
        long now=self.level().getGameTime();
        if(now<frontier$nextNpcShot.getOrDefault(weaponName,Long.MIN_VALUE))return false;
        int interval=Math.max(1,(int)Math.ceil(1200.0/Math.max(1,vehicleWeaponRpm(weaponName))));
        frontier$nextNpcShot.put(weaponName,now+interval);
        return true;
    }

    @Inject(method="vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void frontier$limitNpcShot(LivingEntity living,String weaponName,Vec3 targetPos,CallbackInfo ci) {
        if(!frontier$allowNpcShot(living,weaponName))ci.cancel();
    }

    @Inject(method="vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void frontier$limitNpcTargetedShot(LivingEntity living,String weaponName,UUID target,Vec3 targetPos,CallbackInfo ci) {
        if(!frontier$allowNpcShot(living,weaponName))ci.cancel();
    }

    @Inject(method="vehicleShoot(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/UUID;Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void frontier$limitNpcSelectedShot(LivingEntity living,UUID target,Vec3 targetPos,CallbackInfo ci) {
        if(living instanceof Mob) {
            String name=getGunName(getSeatIndex(living));
            if(name==null||!frontier$allowNpcShot(living,name))ci.cancel();
        }
    }
}
