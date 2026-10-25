package dev.frontier.flamingofix.client;

import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.model.MissileGeometry;
import dev.sbw.deepstrike.network.DeepStrikeNetwork;
import dev.frontier.flamingofix.TrailCadence;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Vanilla particles only; no custom meshes, smoke simulation, or per-frame emission. */
public final class VanillaMissileEffects {
    private static ClientLevel world;
    private static long tick=Long.MIN_VALUE;
    private static int trailParticles,burstParticles,emitters;
    private static final Int2LongOpenHashMap lastTrail=new Int2LongOpenHashMap();

    private static Minecraft ready(Vec3 position) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.isPaused() || !Double.isFinite(position.lengthSqr()))return null;
        if(world!=mc.level) {
            world=mc.level;tick=Long.MIN_VALUE;lastTrail.clear();
        }
        long now=world.getGameTime();
        if(tick!=now) {
            tick=now;trailParticles=burstParticles=emitters=0;
            if(lastTrail.size()>=512 || now%100==0)
                lastTrail.int2LongEntrySet().removeIf(e->now-e.getLongValue()>40);
        }
        return mc;
    }

    private static void particle(Minecraft mc,SimpleParticleType type,Vec3 p,Vec3 velocity,boolean burst) {
        ParticleStatus setting=mc.options.particles().get();
        int limit=burst?(setting==ParticleStatus.MINIMAL?24:96):(setting==ParticleStatus.MINIMAL?8:32);
        if((burst?burstParticles:trailParticles)>=limit)return;
        if(type==ParticleTypes.EXPLOSION_EMITTER && emitters>=4)return;
        if(burst)burstParticles++;else trailParticles++;
        if(type==ParticleTypes.EXPLOSION_EMITTER)emitters++;
        mc.particleEngine.createParticle(type,p.x,p.y,p.z,velocity.x,velocity.y,velocity.z);
    }

    public static void missile(GuidedMissileEntity missile) {
        if(!missile.isAlive() || !missile.engineRunning())return;
        Vec3 direction=missile.getDeltaMovement().lengthSqr()>1e-6?
            missile.getDeltaMovement().normalize():missile.getLookAngle();
        Vec3 position=missile.position().subtract(direction.scale(MissileGeometry.tailDistance(missile.kind())));
        if(missile.kind()==MissileKind.FP5) {
            Matrix4f rotation=MissileGeometry.flight(missile.kind(),missile.getYRot(),missile.getXRot());
            direction=new Vec3(rotation.transformDirection(new Vector3f(-1,0,0))).normalize();
            Vector3f offset=rotation.transformPosition(missile.fp5BoosterBurning()?
                new Vector3f(.75f,-.478125f,0):new Vector3f(3.063125f,.75625f,0));
            position=missile.position().add(offset.x,offset.y,offset.z);
        }
        trail(missile.getId(),position,direction,missile.launchBoostBurning());
    }

    public static void resetWorld() {
        if(world!=null && world!=Minecraft.getInstance().level) {
            world=null;tick=Long.MIN_VALUE;lastTrail.clear();
        }
    }

    public static void trail(int id,Vec3 position,Vec3 direction,boolean boost) {
        Minecraft mc=ready(position);if(mc==null)return;
        double distance=position.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition());
        if(distance>192*192)return;
        ParticleStatus setting=mc.options.particles().get();
        int interval=distance>96*96?6:distance>48*48?4:2;
        if(setting==ParticleStatus.DECREASED)interval*=2;
        if(setting==ParticleStatus.MINIMAL)interval*=4;
        long previous=lastTrail.getOrDefault(id,Long.MIN_VALUE);
        int sample=TrailCadence.sample(previous,tick,interval,distance<96*96);
        if(sample==0)return;
        if(trailParticles>=(setting==ParticleStatus.MINIMAL?8:32))return;
        if(!lastTrail.containsKey(id) && lastTrail.size()>=512)return;
        lastTrail.put(id,tick);
        Vec3 back=direction.lengthSqr()>1e-6?direction.normalize().scale(-.045):Vec3.ZERO;
        // Spend the admitted nearby sample on its visible fullbright exhaust
        // first; smoke cannot consume the final slot and suppress the flame.
        if((sample&TrailCadence.FLAME)!=0)
            particle(mc,ParticleTypes.FLAME,position,back.scale(1.5),false);
        particle(mc,boost?ParticleTypes.LARGE_SMOKE:ParticleTypes.SMOKE,position,back.add(0,.015,0),false);
        if(boost && distance<64*64)
            particle(mc,ParticleTypes.SMOKE,position.add(0,.12,0),back.add(0,.025,0),false);
    }

    public static void puff(Vec3 position,Vec3 velocity,boolean burst) {
        Minecraft mc=ready(position);if(mc==null)return;
        if(position.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition())>128*128)return;
        Vec3 drift=velocity.scale(.015);
        if(drift.lengthSqr()>.09)drift=drift.normalize().scale(.3);
        particle(mc,ParticleTypes.SMOKE,position,drift,burst);
    }

    public static void backblast(Vec3 position,Vec3 direction) {
        Minecraft mc=ready(position);if(mc==null)return;
        if(position.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition())>96*96)return;
        Vec3 back=direction!=null&&direction.lengthSqr()>1e-6?direction.normalize().scale(-.15):new Vec3(0,-.15,0);
        for(int i=0;i<6;i++) {
            double angle=i*Math.PI/3;
            particle(mc,ParticleTypes.LARGE_SMOKE,position,
                back.add(Math.cos(angle)*.06,.035,Math.sin(angle)*.06),false);
        }
    }

    public static void blast(DeepStrikeNetwork.BlastEffect event) {
        Vec3 position=new Vec3(event.x(),event.y()+.8,event.z());
        Minecraft mc=ready(position);if(mc==null)return;
        double distance=position.distanceToSqr(mc.gameRenderer.getMainCamera().getPosition());
        if(distance>192*192)return;
        boolean small="AIRBURST".equals(event.warhead()) || "shaped_charge".equals(event.warhead());
        particle(mc,ParticleTypes.EXPLOSION_EMITTER,position,Vec3.ZERO,true);
        if(distance>96*96)return;
        int count=small?12:32;
        if(mc.options.particles().get()!=ParticleStatus.ALL)count/=2;
        var random=net.minecraft.util.RandomSource.create(event.seed());
        double radius=small?1.5:4.0;
        for(int i=0;i<count;i++) {
            double angle=random.nextDouble()*Math.PI*2;
            double speed=.06+random.nextDouble()*.15;
            Vec3 velocity=new Vec3(Math.cos(angle)*speed,.03+random.nextDouble()*.14,Math.sin(angle)*speed);
            Vec3 p=position.add(Math.cos(angle)*random.nextDouble()*radius,
                random.nextDouble()*radius*.7,Math.sin(angle)*random.nextDouble()*radius);
            particle(mc,ParticleTypes.LARGE_SMOKE,p,velocity,true);
            if(i%3==0)particle(mc,ParticleTypes.FLAME,p,velocity.scale(.6),true);
            if(i<4)particle(mc,ParticleTypes.EXPLOSION,p,Vec3.ZERO,true);
        }
    }

    private VanillaMissileEffects() {}
}
