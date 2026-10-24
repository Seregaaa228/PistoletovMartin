package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.client.VanillaMissileEffects;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.PatriotInterceptorEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="dev.sbw.deepstrike.client.ContrailRenderer",remap=false)
public abstract class VanillaContrails {
    @Inject(method="prepare",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$noCustomTrails(CallbackInfoReturnable<?> c){c.setReturnValue(null);}

    @Inject(method={"onRenderLevelStage","recordDamaged"},at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$noCustomSimulation(CallbackInfo c){c.cancel();}

    @Inject(method="clientTick",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$resetWorld(CallbackInfo c){VanillaMissileEffects.resetWorld();c.cancel();}

    @Inject(method="record(Ldev/sbw/deepstrike/game/GuidedMissileEntity;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$missile(GuidedMissileEntity missile,CallbackInfo c){
        if(missile!=null)VanillaMissileEffects.missile(missile);c.cancel();
    }

    @Inject(method="recordInterceptor",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$interceptor(PatriotInterceptorEntity entity,CallbackInfo c){
        if(entity!=null && entity.isAlive())VanillaMissileEffects.trail(entity.getId(),entity.position(),entity.getDeltaMovement(),true);c.cancel();
    }

    @Inject(method="recordFrame",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$remote(int id,Vec3 position,float width,float lifetime,boolean rocket,
        float expansion,float red,float green,float blue,float partial,CallbackInfo c){
        VanillaMissileEffects.trail(id,position,Vec3.ZERO,rocket);c.cancel();
    }

    @Inject(method="recordInterceptorFrame(ILnet/minecraft/world/phys/Vec3;FF)V",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$remoteInterceptor(int id,Vec3 position,float width,float partial,CallbackInfo c){
        VanillaMissileEffects.trail(id,position,Vec3.ZERO,true);c.cancel();
    }

    @Inject(method="recordInterceptorFrame(ILnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;IFFF)V",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$remoteInterceptorDirection(int id,Vec3 position,Vec3 direction,int warhead,
        float age,float width,float partial,CallbackInfo c){
        VanillaMissileEffects.trail(id,position,direction,true);c.cancel();
    }

    @Inject(method="emitDetachedSmoke(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;FIIFFF)V",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$puff(Vec3 position,Vec3 velocity,float scale,int seed,int lifetime,
        float red,float green,float blue,CallbackInfo c){VanillaMissileEffects.puff(position,velocity,true);c.cancel();}

    @Inject(method="emitLaunchBackblast",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$backblast(Vec3 position,Vec3 direction,float scale,int seed,CallbackInfo c){
        VanillaMissileEffects.backblast(position,direction);c.cancel();
    }

    @Inject(method="smokeColumn",at=@At("HEAD"),cancellable=true,remap=false)
    private static void flamingo$column(Vec3 position,float scale,int seed,boolean main,CallbackInfo c){c.cancel();}
}
