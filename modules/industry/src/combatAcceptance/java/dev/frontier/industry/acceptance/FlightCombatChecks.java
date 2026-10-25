package dev.frontier.industry.acceptance;

import com.atsuishio.superbwarfare.entity.projectile.Fim92MissileEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.game.ActiveFlights;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Real ticking flights and native impacts. No direct hit/detonate/intercept calls. */
public final class FlightCombatChecks {
    private static final Map<String,Boolean> RESULTS=new LinkedHashMap<>();
    private static final Map<UUID,Integer> BLASTS=new HashMap<>();
    private static ServerLevel world;
    private static ServerPlayer owner;
    private static ServerPlayer guest;
    private static Vec3 ownerPosition;
    private static float ownerYaw,ownerPitch;
    private static boolean ownerNoGravity;
    private static Vec3 guestPosition;
    private static float guestYaw,guestPitch;
    private static boolean guestInvulnerable;
    private static Su24mEntity plane;
    private static GuidedMissileEntity incoming,threat;
    private static Fim92MissileEntity interceptor;
    private static Vec3 incomingPrevious,threatPrevious,interceptorPrevious;
    private static float initialHealth;
    private static int ticks,stage,stageStart,incomingMoving,threatMoving,interceptorMoving;
    private static boolean complete,intercepted;
    private static int impactTick=-1,planeJoins;
    private static boolean destroyedAtImpact,wreckObserved,groundObserved,airborneCrewRetained=true;
    private static final Vec3 PLANE=new Vec3(0,124,-15);

    private static void check(String label,boolean value) {
        RESULTS.put(label,value);
        com.mojang.logging.LogUtils.getLogger().info("COMBAT_FLIGHT {} = {}",label,value);
    }

    public static void begin(ServerLevel level,ServerPlayer player) throws ReflectiveOperationException {
        if(world!=null)return;
        world=level;owner=player;
        ownerPosition=owner.position();ownerYaw=owner.getYRot();ownerPitch=owner.getXRot();
        ownerNoGravity=owner.isNoGravity();owner.setNoGravity(true);
        owner.teleportTo(level,0,124,-13,180,0);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(owner));
        level.setDayTime(6000);
        MinecraftForge.EVENT_BUS.register(FlightCombatChecks.class);
        for(var floor:net.minecraft.core.BlockPos.betweenClosed(new net.minecraft.core.BlockPos(-8,119,-26),new net.minecraft.core.BlockPos(8,119,-4)))
            level.setBlock(floor,net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState(),2);
        plane=AircraftProfile.F16.entity.get().create(level);
        plane.moveTo(PLANE.x,PLANE.y,PLANE.z,180,0);
        plane.setNoGravity(true);
        BLASTS.put(plane.getUUID(),0);
        level.addFreshEntity(plane);
        initialHealth=plane.getHealth();
        guest=level.getServer().getPlayerList().getPlayerByName("CombatGuest");
        if(guest==null)throw new IllegalStateException("CombatGuest must be connected for cockpit fixture");
        guestPosition=guest.position();guestYaw=guest.getYRot();guestPitch=guest.getXRot();
        guestInvulnerable=guest.isInvulnerable();guest.setInvulnerable(true);
        check("real guest occupies target F16 native cockpit seat",guest.startRiding(plane,true)
                && guest.getVehicle()==plane && guest!=owner);
        check("real F16 joined server",plane.isAddedToWorld());
    }

    private static GuidedMissileEntity guided(MissileKind kind,Vec3 from,Vec3 target) throws ReflectiveOperationException {
        var missile=new GuidedMissileEntity(world,kind);
        Vec3 direction=target.subtract(from).normalize();
        missile.moveTo(from.x,from.y,from.z,(float)Math.toDegrees(Math.atan2(-direction.x,direction.z)),
                (float)-Math.toDegrees(Math.asin(direction.y)));
        int fuel=MissileData.calculateDefaultMaxFuel(kind,List.of());
        missile.applyMissileData(new MissileData(kind,"he","rocket",List.of(),"default",fuel,fuel));
        missile.setLaunchVector(direction,12);
        missile.setOwnerId(owner.getUUID());
        missile.setMissionId(UUID.randomUUID());
        missile.setMission(List.of(new RoutePoint(target.x,target.y,target.z,kind.minSpeed)),false);
        seed(missile,1L); // Keep the native dud mechanic; choose a reproducible live-round fixture.
        BLASTS.put(missile.getUUID(),0);
        world.addFreshEntity(missile);
        return missile;
    }

    private static void seed(Entity entity,long seed) throws ReflectiveOperationException {
        java.lang.reflect.Field field;
        try { field=Entity.class.getDeclaredField("f_19796_"); }
        catch(NoSuchFieldException development) { field=Entity.class.getDeclaredField("random"); }
        field.setAccessible(true);
        ((net.minecraft.util.RandomSource)field.get(entity)).setSeed(seed);
    }

    public static void tick(ServerLevel level) throws ReflectiveOperationException {
        if(complete || level!=world)return;
        ticks++;
        if(stage==0) {
            if(ticks<100 && ticks%10==0) {
                owner.setDeltaMovement(Vec3.ZERO);
                owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(owner));
            }
            if(impactTick<0 && plane.isAlive()) {
                plane.setPos(PLANE);plane.setDeltaMovement(Vec3.ZERO);
            }
            if(incoming==null) {
                if(ticks<100)return;
                // The native tablet needs proximity during its capture above.
                // Observe the live approach from outside the wing afterwards.
                owner.teleportTo(level,24,129,0,135,8);
                owner.setDeltaMovement(Vec3.ZERO);
                owner.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(owner));
                Vec3 aim=plane.getBoundingBox().getCenter();
                incoming=guided(MissileKind.JAGM,new Vec3(aim.x,aim.y,-75),aim);
                incomingPrevious=incoming.position();
                check("native JAGM joined for observed cockpit approach",incoming.isAddedToWorld());
            }
            if(impactTick<0 && incoming.position().distanceToSqr(incomingPrevious)>1e-7)incomingMoving++;
            if(impactTick<0)incomingPrevious=incoming.position();
            if(impactTick<0 && (incoming.isRemoved() || ticks>=500)) {
                check("native JAGM visibly moved for at least ten server ticks before outcome",incomingMoving>=10);
                check("native JAGM flight naturally resolved exactly one explosion",incoming.isRemoved()
                        && BLASTS.getOrDefault(incoming.getUUID(),0)==1);
                check("natural JAGM impact damaged real F16",plane.getHealth()<initialHealth || !plane.isAlive());
                com.mojang.logging.LogUtils.getLogger().info("COMBAT_JAGM_OUTCOME planeHealth={}->{} missilePos={} crewPos={} report={}",
                        initialHealth,plane.getHealth(),incoming.position(),guest.position(),ActiveFlights.get(level).report(incoming.missionId()));
                if(incoming.isAlive())incoming.discard();
                impactTick=ticks;destroyedAtImpact=plane.getHealth()<=0;
                check("accepted JAGM contact depletes native F16 health",destroyedAtImpact);
                // Native aircraft retain crew while airborne and explode/eject
                // only after ground/fluid contact. Let normal world ticks own
                // that transition; the bedrock lab pad survives native craters.
                plane.setNoGravity(false);
            }
            if(impactTick>=0) {
                wreckObserved|=plane.isWreck();
                groundObserved|=plane.onGround() || plane.getSympatheticDetonated();
                if(plane.isWreck() && !plane.onGround() && !plane.getSympatheticDetonated())
                    airborneCrewRetained&=guest.getVehicle()==plane;
                int wreckBlasts=BLASTS.getOrDefault(plane.getUUID(),0);
                if(ticks-impactTick<40 || wreckBlasts==0 && ticks-impactTick<80)return;
                if(destroyedAtImpact) {
                    check("destroyed F16 naturally enters native wreck state",wreckObserved);
                    check("native aircraft crew remains seated while wreck is airborne",airborneCrewRetained);
                    check("native wreck reaches physical ground before crew release",groundObserved);
                    check("native ground wreck releases guest crew",groundObserved && guest.getVehicle()!=plane);
                    check("native wreck keeps original aircraft UUID without duplicate entity",planeJoins==1 && level.getEntity(plane.getUUID())==plane);
                    check("native aircraft wreck creates exactly one vehicle explosion",wreckBlasts==1);
                    com.mojang.logging.LogUtils.getLogger().info("COMBAT_NATIVE_WRECK ticks={} health={} wreck={} ground={} sympathetic={} planeJoins={} vehicleBlasts={} guestVehicle={} position={}",
                            ticks-impactTick,plane.getHealth(),plane.isWreck(),plane.onGround(),plane.getSympatheticDetonated(),
                            planeJoins,wreckBlasts,guest.getVehicle(),plane.position());
                }
                restoreGuest();
                plane.discard();
                level.setDayTime(18000);
                threat=guided(MissileKind.SPIKE_NLOS,new Vec3(24,134,-70),new Vec3(24,134,180));
                threatPrevious=threat.position();
                stage=1;stageStart=ticks;
            }
        } else {
            if(threat.position().distanceToSqr(threatPrevious)>1e-7)threatMoving++;
            threatPrevious=threat.position();
            if(interceptor==null && ticks-stageStart>=20) {
                interceptor=new Fim92MissileEntity(ModEntities.FIM_92_MISSILE.get(),level);
                Vec3 origin=new Vec3(24,134,5),direction=threat.position().subtract(origin).normalize();
                interceptor.setOwner(owner);
                interceptor.setTargetUUID(threat.getStringUUID());
                interceptor.setTargetPos(threat.position());
                interceptor.moveTo(origin.x,origin.y,origin.z,(float)Math.toDegrees(Math.atan2(-direction.x,direction.z)),0);
                interceptor.setDeltaMovement(direction.scale(1));
                seed(interceptor,1L);
                level.addFreshEntity(interceptor);
                interceptorPrevious=interceptor.position();
                check("native FIM92 interceptor joined and targets moving native flight",interceptor.isAddedToWorld()
                        && interceptor.getTargetUUID().equals(threat.getStringUUID()) && threatMoving>=10);
            }
            if(interceptor!=null) {
                if(interceptor.position().distanceToSqr(interceptorPrevious)>1e-7)interceptorMoving++;
                interceptorPrevious=interceptor.position();
            }
            intercepted|=threat.isIntercepted();
            var report=ActiveFlights.get(level).report(threat.missionId());
            intercepted|=report!=null && "INTERCEPTED".equals(report.getString("Outcome"));
            if(intercepted && threat.isRemoved() || ticks-stageStart>=400) {
                check("native FIM92 flew through world before interception",interceptorMoving>=3);
                check("moving guided missile naturally intercepted by native FIM92",intercepted);
                check("intercepted flight does not produce a second warhead explosion",intercepted
                        && BLASTS.getOrDefault(threat.getUUID(),0)==0);
                if(threat.isAlive())threat.discard();
                if(interceptor!=null && interceptor.isAlive())interceptor.discard();
                complete=true;
                restoreGuest();
                MinecraftForge.EVENT_BUS.unregister(FlightCombatChecks.class);
            }
        }
    }
    private static void restoreGuest() {
        if(owner!=null && ownerPosition!=null) {
            owner.teleportTo(world,ownerPosition.x,ownerPosition.y,ownerPosition.z,ownerYaw,ownerPitch);
            owner.setNoGravity(ownerNoGravity);
            ownerPosition=null;
        }
        if(guest==null || guestPosition==null)return;
        guest.stopRiding();
        guest.teleportTo(world,guestPosition.x,guestPosition.y,guestPosition.z,guestYaw,guestPitch);
        guest.setInvulnerable(guestInvulnerable);
        guestPosition=null;
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Start event) {
        Entity source=event.getExplosion().getDirectSourceEntity();
        if(event.getLevel()==world && source!=null && BLASTS.containsKey(source.getUUID())) {
            BLASTS.merge(source.getUUID(),1,Integer::sum);
            if(!(source instanceof GuidedMissileEntity))return;
            try {
                var field=source.getClass().getDeclaredField("flamingo$impact");field.setAccessible(true);
                Object token=field.get(source);var targetField=token.getClass().getDeclaredField("target");targetField.setAccessible(true);
                Entity target=(Entity)targetField.get(token);
                String health=target instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity vehicle?Float.toString(vehicle.getHealth()):
                        target instanceof net.minecraft.world.entity.LivingEntity living?Float.toString(living.getHealth()):"n/a";
                com.mojang.logging.LogUtils.getLogger().info("COMBAT_FLIGHT_CONTACT missile={} targetClass={} target={} targetPos={} health={} sourcePos={}",
                        source.getUUID(),target==null?"none":target.getClass().getName(),target==null?null:target.getUUID(),
                        target==null?null:target.position(),health,source.position());
            } catch(ReflectiveOperationException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("COMBAT_FLIGHT_CONTACT reflection unavailable",failure);
            }
        }
    }
    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        if(!event.isCanceled() && event.getLevel()==world && plane!=null && event.getEntity().getUUID().equals(plane.getUUID()))planeJoins++;
    }
    public static boolean done() { return complete; }
    public static Map<String,Boolean> results() { return Map.copyOf(RESULTS); }
    private FlightCombatChecks() {}
}
