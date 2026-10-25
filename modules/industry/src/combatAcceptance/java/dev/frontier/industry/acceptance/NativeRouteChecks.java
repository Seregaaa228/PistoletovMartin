package dev.frontier.industry.acceptance;

import dev.sbw.deepstrike.game.ActiveFlights;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.LauncherBlock;
import dev.sbw.deepstrike.game.LauncherBlockEntity;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.PeacekeeperDynamics;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.network.DeepStrikeNetwork;
import dev.sbw.deepstrike.registry.BFBlocks;
import dev.sbw.deepstrike.registry.BFItems;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

/** Actual native ticking routes in a disposable world; kinetic rounds isolate navigation from HE balance. */
public final class NativeRouteChecks {
    public static final int MAX_TICKS=6000;
    private static final Map<String,Boolean> RESULTS=new LinkedHashMap<>();
    private static final Map<String,Object> DETAILS=new LinkedHashMap<>();
    private static final List<Trial> TRIALS=new ArrayList<>();
    private static final Map<UUID,Integer> JOINS=new HashMap<>(),BLASTS=new HashMap<>();
    private static final Set<ChunkPos> EXTRA_CHUNKS=new HashSet<>();
    private static ServerLevel world;
    private static int ticks;
    private static boolean complete,laptopComplete;
    private static UUID laptopMission;
    private static LauncherBlockEntity laptopLauncher;
    private static Vec3 laptopOrigin;

    private static final class Trial {
        final String label;
        final GuidedMissileEntity missile;
        final Vec3 origin,target;
        final double assignedSpeed;
        Vec3 previous;
        double distance,maximumDisplacement,maximumSpeed;
        int movingTicks;
        boolean finished;
        Trial(String label,GuidedMissileEntity missile,Vec3 origin,Vec3 target,double speed) {
            this.label=label;this.missile=missile;this.origin=origin;this.target=target;assignedSpeed=speed;previous=origin;
        }
    }
    private static void check(String label,boolean value) {
        RESULTS.put(label,value);com.mojang.logging.LogUtils.getLogger().info("COMBAT_NATIVE_ROUTE {} = {}",label,value);
    }
    private static void forceStart(ServerLevel level,BlockPos origin) {
        for(int x=(origin.getX()>>4)-1;x<=(origin.getX()>>4)+1;x++)
            for(int z=(origin.getZ()>>4)-1;z<=(origin.getZ()>>4)+1;z++)
                if(!level.getForcedChunks().contains(ChunkPos.asLong(x,z))) {
                    level.setChunkForced(x,z,true);EXTRA_CHUNKS.add(new ChunkPos(x,z));
                }
    }
    public static void begin(ServerLevel level,ServerPlayer owner) throws ReflectiveOperationException {
        if(world!=null)return;
        world=level;MinecraftForge.EVENT_BUS.register(NativeRouteChecks.class);
        int lane=0;
        for(MissileKind kind:MissileKind.values())for(int band=0;band<3;band++) {
            String range=new String[]{"near","mid","max"}[band];
            double distance=band==0?kind.minRange+20:band==1?(kind.minRange+kind.maxRange)*.5:kind.maxRange;
            double speed=band==2?kind.maxSpeed:kind.defaultSpeed;
            Vec3 from=new Vec3(-512-lane++*64,220,-512),target=from.add(0,0,distance);
            forceStart(level,BlockPos.containing(from));
            var missile=new GuidedMissileEntity(level,kind);Entity entity=missile;
            entity.moveTo(from.x,from.y,from.z,0,-12);
            int fuel=MissileData.calculateDefaultMaxFuel(kind,List.of());
            var data=new MissileData(kind,"none","rocket",List.of(),MissileData.defaultCamo(kind),fuel,fuel);
            missile.applyMissileData(data);missile.setOwnerId(owner.getUUID());missile.setMissionId(UUID.randomUUID());
            missile.setLaunchVector(new Vec3(0,Math.sin(Math.toRadians(12)),Math.cos(Math.toRadians(12))),12);
            missile.setMission(List.of(new RoutePoint(target.x,target.y,target.z,speed)),kind==MissileKind.PEACEKEEPER);
            if(kind==MissileKind.SPIKE_NLOS)missile.configureSpike(232,speed);
            if(kind==MissileKind.STORM_SHADOW)missile.releaseFromAircraft(Vec3.ZERO);
            if(kind==MissileKind.PEACEKEEPER) {
                var selection=PeacekeeperDynamics.select(from,target,speed);
                check(kind+" "+range+" native ballistic selector accepts assigned profile",selection.valid());
                DETAILS.put(kind+" "+range+" ballisticSelection",selection.reason()==null?"valid":selection.reason());
                if(selection.valid())missile.setPeacekeeperPlan(selection.plan());
            }
            var trial=new Trial(kind+" "+range,missile,from,target,speed);TRIALS.add(trial);
            JOINS.put(missile.missionId(),0);BLASTS.put(entity.getUUID(),0);
            check(trial.label+" actual native ticking missile joined",level.addFreshEntity(entity));
            DETAILS.put(trial.label+" configuration",Map.of("origin",List.of(from.x,from.y,from.z),
                    "target",List.of(target.x,target.y,target.z),"requestedHorizontalRange",distance,
                    "assignedNativeKmh",speed,"fuel",fuel,"engine","rocket","warhead","none",
                    "aircraftRelease",kind==MissileKind.STORM_SHADOW));
        }
        laptopBackend(level,owner);
    }

    /** Native item bind + native packet codec/backend; this does not claim a client UI click. */
    private static void laptopBackend(ServerLevel level,ServerPlayer owner) throws ReflectiveOperationException {
        BlockPos pad=new BlockPos(256,120,-192);laptopOrigin=Vec3.atCenterOf(pad);forceStart(level,pad);
        for(BlockPos floor:BlockPos.betweenClosed(pad.offset(-4,-1,-4),pad.offset(4,-1,4)))level.setBlock(floor,Blocks.STONE.defaultBlockState(),2);
        level.setBlockAndUpdate(pad,BFBlocks.FP5_LAUNCHER.get().defaultBlockState().setValue(LauncherBlock.FACING,Direction.NORTH));
        laptopLauncher=(LauncherBlockEntity)level.getBlockEntity(pad);
        laptopLauncher.placed(owner,new ItemStack(BFItems.FP5_LAUNCHER.get()));
        var ammunition=new ItemStack(BFItems.FP5.get());var nativeData=MissileData.fromItemStack(ammunition);
        new MissileData(MissileKind.FP5,"none",nativeData.engine(),nativeData.modules(),nativeData.camo(),nativeData.fuel(),nativeData.maxFuel()).writeToItemStack(ammunition);
        check("laptop backend native FP5 ammunition loaded",laptopLauncher.load(ammunition,owner));
        ItemStack laptop=new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("deepstrike","deepstrike_laptop")));
        ItemStack previous=owner.getItemInHand(InteractionHand.MAIN_HAND);
        try {
            owner.setItemInHand(InteractionHand.MAIN_HAND,laptop);
            ((Item)laptop.getItem()).useOn(new UseOnContext(owner,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pad),Direction.UP,pad,false)));
            check("actual native laptop item binds launcher NBT",laptop.hasTag() && laptop.getTag().getLong("BoundLauncher")==pad.asLong());
        } finally {owner.setItemInHand(InteractionHand.MAIN_HAND,previous);}
        laptopMission=UUID.randomUUID();JOINS.put(laptopMission,0);
        var message=new DeepStrikeNetwork.LaunchMission(pad,false,List.of(new RoutePoint(pad.getX()+.5,152,pad.getZ()-220,MissileKind.FP5.minSpeed)),"100",laptopMission);
        var encode=DeepStrikeNetwork.LaunchMission.class.getDeclaredMethod("encode",DeepStrikeNetwork.LaunchMission.class,FriendlyByteBuf.class);
        var decode=DeepStrikeNetwork.LaunchMission.class.getDeclaredMethod("decode",FriendlyByteBuf.class);
        var handle=DeepStrikeNetwork.LaunchMission.class.getDeclaredMethod("handle",DeepStrikeNetwork.LaunchMission.class,NetworkEvent.Context.class);
        encode.setAccessible(true);decode.setAccessible(true);handle.setAccessible(true);
        var constructor=NetworkEvent.Context.class.getDeclaredConstructor(net.minecraft.network.Connection.class,NetworkDirection.class,int.class);constructor.setAccessible(true);
        var context=(NetworkEvent.Context)constructor.newInstance(owner.connection.connection,NetworkDirection.PLAY_TO_SERVER,0);
        check("laptop backend receives actual connection sender",context.getSender()==owner);
        var bytes=new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode.invoke(null,message,bytes);var decoded=(DeepStrikeNetwork.LaunchMission)decode.invoke(null,bytes);
            check("native laptop launch codec retains route frequency and command UUID",decoded.equals(message));
            handle.invoke(null,decoded,context);handle.invoke(null,decoded,context);
            check("native laptop packet handler accepts countdown once",ActiveFlights.get(level).knowsMission(laptopMission)
                    && laptopLauncher.loadCount()==1 && laptopMission.equals(laptopLauncher.commandMission()));
        } finally {bytes.release();}
    }

    public static void tick(ServerLevel level) {
        if(complete || level!=world)return;
        ticks++;
        if(ticks==650)CombatChecks.write("combat-profile-stage.json",Map.of("routeTick",ticks));
        for(Trial trial:TRIALS) {
            if(trial.finished)continue;
            Vec3 position=trial.missile.position();double step=position.distanceTo(trial.previous);
            if(step>1e-7)trial.movingTicks++;
            trial.distance+=step;trial.maximumDisplacement=Math.max(trial.maximumDisplacement,position.distanceTo(trial.origin));
            trial.maximumSpeed=Math.max(trial.maximumSpeed,trial.missile.getDeltaMovement().length());trial.previous=position;
            var report=ActiveFlights.get(level).report(trial.missile.missionId());
            if(report!=null && report.contains("Outcome") || trial.missile.isRemoved() || ticks>=MAX_TICKS) {
                String outcome=report==null?"NO_REPORT":report.getString("Outcome");
                boolean resolved=report!=null && report.contains("ResultX") && report.contains("ResultY") && report.contains("ResultZ");
                Vec3 result=resolved?new Vec3(report.getDouble("ResultX"),report.getDouble("ResultY"),report.getDouble("ResultZ")):position;
                double error=result.distanceTo(trial.target);
                check(trial.label+" actual flight moved before completion",trial.movingTicks>=10 && trial.maximumDisplacement>10);
                check(trial.label+" native route resolved IMPACT with recorded result",resolved && "IMPACT".equals(outcome));
                check(trial.label+" native result reaches requested target within two blocks",resolved && "IMPACT".equals(outcome) && error<=2);
                check(trial.label+" one native missile and one kinetic explosion",JOINS.getOrDefault(trial.missile.missionId(),0)==1
                        && BLASTS.getOrDefault(trial.missile.getUUID(),0)==1);
                Map<String,Object> detail=new LinkedHashMap<>();
                detail.put("ticks",ticks);detail.put("movingTicks",trial.movingTicks);detail.put("outcome",outcome);
                detail.put("actualPathLength",trial.distance);detail.put("maximumDisplacement",trial.maximumDisplacement);
                detail.put("maximumBlocksPerTick",trial.maximumSpeed);detail.put("assignedNativeKmh",trial.assignedSpeed);
                detail.put("result",List.of(result.x,result.y,result.z));detail.put("targetError",error);
                detail.put("nativeReport",report==null?"none":report.toString());DETAILS.put(trial.label+" result",detail);
                com.mojang.logging.LogUtils.getLogger().info("COMBAT_NATIVE_ROUTE_DETAIL {} {}",trial.label,detail);
                if(trial.missile.isAlive())trial.missile.discard();trial.finished=true;
            }
        }
        if(!laptopComplete) {
            Entity missile=level.getEntity(laptopMission);
            boolean cleared=missile instanceof GuidedMissileEntity && missile.position().distanceTo(laptopOrigin)>24;
            if(cleared || ticks>=800) {
                check("native laptop backend countdown spawns exactly one missile",JOINS.getOrDefault(laptopMission,0)==1);
                check("native laptop backend spends one native ammunition slot",laptopLauncher.loadCount()==0);
                check("native laptop backend launch leaves physical pad alive",cleared);
                if(missile!=null)missile.discard();laptopComplete=true;
            }
        }
        if(laptopComplete && TRIALS.stream().allMatch(trial->trial.finished)) {
            EXTRA_CHUNKS.forEach(chunk->level.setChunkForced(chunk.x,chunk.z,false));
            MinecraftForge.EVENT_BUS.unregister(NativeRouteChecks.class);complete=true;
            CombatChecks.write("combat-native-routes.json",DETAILS);
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void joined(EntityJoinLevelEvent event) {
        if(!event.isCanceled() && event.getLevel()==world && event.getEntity() instanceof GuidedMissileEntity missile && JOINS.containsKey(missile.missionId()))
            JOINS.merge(missile.missionId(),1,Integer::sum);
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Start event) {
        Entity source=event.getExplosion().getDirectSourceEntity();
        if(event.getLevel()==world && source!=null && BLASTS.containsKey(source.getUUID()))BLASTS.merge(source.getUUID(),1,Integer::sum);
    }
    public static boolean done() {return complete;}
    public static Map<String,Boolean> results() {return Map.copyOf(RESULTS);}
    public static Map<String,Object> details() {return Map.copyOf(DETAILS);}
    private NativeRouteChecks() {}
}
