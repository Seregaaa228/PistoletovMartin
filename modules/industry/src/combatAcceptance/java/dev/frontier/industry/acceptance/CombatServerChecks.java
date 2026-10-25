package dev.frontier.industry.acceptance;

import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.construction.ConstructionAccess;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class CombatServerChecks {
    private final Map<String, Boolean> results = new LinkedHashMap<>();
    private int ticks, cannonSample;
    private boolean started, complete;
    private BlockPos protectedBlock;
    private long runtimeStart;
    private int runtimeTicks;
    private boolean groundStarted;
    private boolean routesStarted;
    private long serverTickStarted;
    private final java.util.List<Long> tickDurations=new java.util.ArrayList<>();
    @SubscribeEvent public void tickStart(TickEvent.ServerTickEvent event) {
        if(event.phase==TickEvent.Phase.START&&CombatChecks.enabled())serverTickStarted=System.nanoTime();
    }
    @SubscribeEvent public void login(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        int delay=Integer.getInteger("frontier.combatLatencyMs",0);
        if(!CombatChecks.enabled()||delay<=0||delay>500||!(event.getEntity() instanceof ServerPlayer player))return;
        var channel=player.connection.connection.channel();
        channel.eventLoop().execute(()->channel.pipeline().addBefore("packet_handler","combat_test_delay",new io.netty.channel.ChannelDuplexHandler(){
            @Override public void channelRead(io.netty.channel.ChannelHandlerContext ctx,Object message){
                ctx.executor().schedule(()->{if(ctx.channel().isActive())ctx.fireChannelRead(message);else io.netty.util.ReferenceCountUtil.release(message);},delay,java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx,Object message,io.netty.channel.ChannelPromise promise){
                ctx.executor().schedule(()->ctx.writeAndFlush(message,promise),delay,java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }));
    }
    private void check(String label, boolean value) {
        results.put(label, value);
        com.mojang.logging.LogUtils.getLogger().info("COMBAT_ACCEPTANCE {} = {}", label, value);
    }
    @SubscribeEvent public void protection(BlockEvent.BreakEvent event) {
        if (protectedBlock != null && protectedBlock.equals(event.getPos())) event.setCanceled(true);
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !CombatChecks.enabled() || complete) return;
        var server = event.getServer();
        if (server.getPlayerCount() < 2) return;
        ServerLevel level = server.overworld();
        ServerPlayer owner = server.getPlayerList().getPlayerByName("CombatOwner");
        if (owner == null) return;
        try {
            if (!started) {
                started = true;
                runtimeStart = System.nanoTime();
                // Native Living Villages may register a claim around the lab
                // after startup. Give only this disposable fixture pilot the
                // native creative-admin exemption; the explicit cancellable
                // BreakEvent case below still checks protection enforcement.
                server.getPlayerList().op(owner.getGameProfile());
                for (var player : server.getPlayerList().getPlayers()) {
                    player.teleportTo(level, 0, 124, 30, 180, 8);
                    player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities();
                }
                for (int x=-6; x<=6; x++) for (int z=-5; z<=5; z++) level.setChunkForced(x,z,true);
                level.setDayTime(6000);
                results.putAll(DeferredTerrainChecks.run(level));
                DroneChecks.begin(level, owner);
                MissileChecks.begin(level, owner);
            }
            ++ticks; ++runtimeTicks;
            DroneChecks.tick(level); MissileChecks.tick(level);
            if (ticks >= 40 && ticks < 120) cannon(level, owner);
            if (ticks == 140) construction(level, owner);
            if (ticks == 160) { aircraft(level);results.putAll(AviationServerChecks.run(level)); }
            if (ticks == 245) {
                try { industrialNode(level,owner); }
                catch(Throwable failure){check("industrial fixture exception "+failure,false);com.mojang.logging.LogUtils.getLogger().error("Industrial fixture failed; independent combat fixtures continue",failure);}
            }
            if (ticks == 300) FlightCombatChecks.begin(level,owner);
            if (ticks >= 300) {
                FlightCombatChecks.tick(level);
                if(FlightCombatChecks.done()&&!groundStarted){groundStarted=true;GroundProjectileChecks.begin(level,owner);}
                if(groundStarted)GroundProjectileChecks.tick(level);
                if(FlightCombatChecks.done()&&GroundProjectileChecks.done()&&!routesStarted){
                    routesStarted=true;NativeRouteChecks.begin(level,owner);
                }
                if(routesStarted)NativeRouteChecks.tick(level);
            }
            if (DroneChecks.done() && MissileChecks.done() && FlightCombatChecks.done() && GroundProjectileChecks.done()
                    && NativeRouteChecks.done() && ticks > 300) {
                results.putAll(DroneChecks.results()); results.putAll(MissileChecks.results());
                check("two real players remained connected", server.getPlayerCount() == 2);
                finish(server);
            } else if (ticks > 8000) { check("all fixture stages completed within 8000 ticks", false); finish(server); }
        } catch (Throwable failure) {
            com.mojang.logging.LogUtils.getLogger().error("COMBAT_ACCEPTANCE server failed",failure);
            check("unexpected server exception " + failure, false); finish(server);
        } finally {
            if(started&&serverTickStarted>0)tickDurations.add(System.nanoTime()-serverTickStarted);
        }
    }
    private void finish(net.minecraft.server.MinecraftServer server) {
        results.putAll(DroneChecks.results());results.putAll(MissileChecks.results());
        results.putAll(FlightCombatChecks.results());results.putAll(GroundProjectileChecks.results());
        results.putAll(NativeRouteChecks.results());
        var timing=new LinkedHashMap<String,Object>();
        timing.put("ticks",runtimeTicks);timing.put("wallSeconds",(System.nanoTime()-runtimeStart)/1e9);
        timing.put("meanTickMilliseconds",server.getAverageTickTime());
        var sorted=tickDurations.stream().mapToLong(Long::longValue).sorted().toArray();
        if(sorted.length>0){
            timing.put("recordedTicks",sorted.length);
            timing.put("p50Milliseconds",sorted[(int)((sorted.length-1)*.50)]/1e6);
            timing.put("p95Milliseconds",sorted[(int)((sorted.length-1)*.95)]/1e6);
            timing.put("p99Milliseconds",sorted[(int)((sorted.length-1)*.99)]/1e6);
            timing.put("maximumMillisecondsIncludingFixtureSetup",sorted[sorted.length-1]/1e6);
            timing.put("ticksOver50MillisecondsIncludingFixtureSetup",java.util.Arrays.stream(sorted).filter(n->n>50_000_000).count());
        }
        CombatChecks.write("combat-server-timing.json",timing);
        complete=true; CombatChecks.write("combat-server-results.json",results);
    }
    private Su24mEntity plane(ServerLevel level, Vec3 pos) {
        var plane = AircraftProfile.F16.entity.get().create(level);
        plane.moveTo(pos.x,pos.y,pos.z,0,0); level.addFreshEntity(plane); return plane;
    }
    private SmallCannonShellEntity shell(ServerLevel level, ServerPlayer pilot, Su24mEntity plane) {
        boolean mounted=pilot.startRiding(plane,true);
        var shell = new SmallCannonShellEntity(com.atsuishio.superbwarfare.init.ModEntities.SMALL_CANNON_SHELL.get(),level);
        shell.setOwner(pilot);shell.setDamage(35);shell.setExplosionDamage(10);shell.setExplosionRadius(3);shell.setExplosionDestroy(true);
        shell.setPos(plane.position()); level.addFreshEntity(shell);
        if(cannonSample%20==0)com.mojang.logging.LogUtils.getLogger().info("COMBAT_CANNON_DIAG mounted={} rider={} planeHealth={} marked={} damage={}",mounted,pilot.getVehicle()==plane,plane.getHealth(),shell.getPersistentData().getBoolean("frontierAircraftCannon"),shell.getDamage());
        pilot.stopRiding();return shell;
    }
    private void cannon(ServerLevel level, ServerPlayer owner) throws Exception {
        var plane = plane(level,new Vec3(24,124,20));
        var samples = new net.minecraft.world.level.block.Block[]{Blocks.OAK_PLANKS,Blocks.STONE,Blocks.BRICKS,Blocks.BEDROCK};
        int[] required={1,2,2,Integer.MAX_VALUE};
        int material=cannonSample/20,sample=cannonSample%20;
                BlockPos pos = new BlockPos(38+material*3,124,10);
                level.setBlockAndUpdate(pos,samples[material].defaultBlockState());
                for(int shot=0;shot<Math.min(2,required[material]);shot++) {
                    var projectile=shell(level,owner,plane);
                    var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.WEST,pos,false);
                    if(sample==0){var st=level.getBlockState(pos);com.mojang.logging.LogUtils.getLogger().info("COMBAT_CANNON_GATE material={} mayBuild={} levelInteract={} entityDestroy={} hardness={} resistance={} owner={} alive={} mode={} held={}",material,owner.mayBuild(),level.mayInteract(owner,pos),st.canEntityDestroy(level,pos,projectile),st.getDestroySpeed(level,pos),st.getBlock().getExplosionResistance(),projectile.getPersistentData(),owner.isAlive(),owner.gameMode.getGameModeForPlayer(),owner.getMainHandItem());}
                    projectile.onHitBlock(hit);
                    // Re-entry must not become a second block/blast callback.
                    projectile.afterHitBlock(hit);
                    if(sample==0||material==0&&sample==12)com.mojang.logging.LogUtils.getLogger().info("COMBAT_CANNON_AFTER material={} shot={} block={} tag={} cache={}",material,shot,level.getBlockState(pos),projectile.getPersistentData(),cannonCache(level,pos));
                }
                check("cannon material " + samples[material] + " sample " + sample,
                    required[material] <=2 ? level.getBlockState(pos).isAir() : level.getBlockState(pos).is(Blocks.BEDROCK));
        cannonSample++;
        if(cannonSample<80){plane.discard();return;}
        protectedBlock=new BlockPos(38,124,15);level.setBlockAndUpdate(protectedBlock,Blocks.OAK_PLANKS.defaultBlockState());
        var projectile=shell(level,owner,plane);projectile.onHitBlock(new BlockHitResult(Vec3.atCenterOf(protectedBlock),Direction.WEST,protectedBlock,false));
        check("cannon obeys Forge block protection cancellation",level.getBlockState(protectedBlock).is(Blocks.OAK_PLANKS));
        protectedBlock=null;
        ItemStack held=owner.getMainHandItem();
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
        BlockPos swordBlock=new BlockPos(38,124,17);
        level.setBlockAndUpdate(swordBlock,Blocks.OAK_PLANKS.defaultBlockState());
        var swordShell=shell(level,owner,plane);
        swordShell.onHitBlock(new BlockHitResult(Vec3.atCenterOf(swordBlock),Direction.WEST,swordBlock,false));
        check("creative pilot held sword does not disable aircraft cannon",level.getBlockState(swordBlock).isAir());
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,held);
        plane.discard();
    }
    private static String cannonCache(ServerLevel level,BlockPos pos) throws Exception {
        var type=dev.frontier.aviation.AircraftCannon.class;
        var levels=type.getDeclaredField("LEVELS");levels.setAccessible(true);
        Object cache=((Map<?,?>)levels.get(null)).get(level);
        StringBuilder result=new StringBuilder("time=").append(level.getGameTime()).append(",nativeEnabled=")
            .append(com.atsuishio.superbwarfare.config.server.ExplosionConfig.EXPLOSION_DESTROY.get());
        if(cache!=null)for(String name:new String[]{"tick","broken","blocks"}){
            var field=cache.getClass().getDeclaredField(name);field.setAccessible(true);
            Object value=field.get(cache);if(value instanceof Map<?,?> map)value=map.get(pos);
            result.append(',').append(name).append('=').append(value);
        }
        return result.toString();
    }
    private void construction(ServerLevel level, ServerPlayer player) {
        var plane=plane(level,new Vec3(18,124,20));
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new ItemStack(IndustryContent.deployer(SiteType.SUBSTATION)));
        player.startRiding(plane,true);
        check("mounted construction access denied",!ConstructionAccess.canBuild(player));
        check("server refuses mounted building placement",IndustryEngine.place(player,SiteType.SUBSTATION,new BlockPos(15,125,15))==null);
        check("denied mounted placement preserves kit",player.getOffhandItem().getCount()==1);
        player.stopRiding();check("on-foot construction available after dismount",ConstructionAccess.canBuild(player));
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,ItemStack.EMPTY);plane.discard();
        player.teleportTo(level,0,124,30,180,8);
    }
    private void aircraft(ServerLevel level) {
        for(int x=59;x<=61;x++)for(int y=116;y<136;y++)for(int z=10;z<31;z++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState());
        for(var profile:AircraftProfile.values()) {
            var plane=profile.entity.get().create(level);plane.moveTo(48,122,20,90,0);level.addFreshEntity(plane);
            float before=plane.getHealth();plane.setDeltaMovement(10,0,0);plane.move(MoverType.SELF,new Vec3(10,0,0));
            check(profile.id+" high-speed obstacle causes damage",plane.getHealth()<before||plane.isRemoved());
            plane.discard();
            var slow=profile.entity.get().create(level);slow.moveTo(48,122,20,90,0);level.addFreshEntity(slow);
            before=slow.getHealth();slow.setDeltaMovement(.15,0,0);slow.move(MoverType.SELF,new Vec3(.15,0,0));
            check(profile.id+" safe taxi preserves health",slow.getHealth()==before);slow.discard();
        }
    }
    private void industrialNode(ServerLevel level,ServerPlayer player) {
        var data=dev.frontier.industry.engine.IndustryData.get(level.getServer());
        var site=new dev.frontier.industry.engine.IndustryData.Site(data,java.util.UUID.randomUUID(),player.getUUID(),
            SiteType.MISSILE_FACTORY,level.dimension().location(),new BlockPos(-10,100,-65));
        site.blueprintVersion=dev.frontier.industry.engine.Blueprint.CURRENT_VERSION;
        site.loaded=true;
        var plan=dev.frontier.industry.engine.Blueprint.blocks(site.type,site.origin,site.blueprintVersion);
        for(var block:plan.entrySet())level.setBlock(block.getKey(),block.getValue(),2);
        data.sites.put(site.id,site);
        var order=new dev.frontier.industry.engine.IndustryData.Order("legacy-paid-combat-fixture",1);
        order.reserved=true;order.progress=7;order.paid.add(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT,3));site.orders.add(order);
        IndustryEngine.poll(level,site);check("complete industrial fixture starts functional",site.functional());
        var critical=dev.frontier.industry.engine.Blueprint.criticalBlocks(site.type,site.origin,site.blueprintVersion);
        BlockPos pos=critical.isEmpty()?dev.frontier.industry.engine.Blueprint.controller(site.type,site.origin,site.blueprintVersion):critical.keySet().iterator().next();
        var plane=plane(level,Vec3.atCenterOf(pos).add(0,12,0));
        for(int shot=0;shot<4&&!level.getBlockState(pos).isAir();shot++) {
            var projectile=shell(level,player,plane);
            projectile.onHitBlock(new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
        }
        IndustryEngine.poll(level,site);
        check("aircraft cannon removes actual critical Industry block",level.getBlockState(pos).isAir());
        check("critical industrial node stops being functional",!site.functional());
        var restored=dev.frontier.industry.engine.IndustryData.load(((net.minecraft.world.level.saveddata.SavedData)data).save(new net.minecraft.nbt.CompoundTag())).sites.get(site.id);
        var saved=restored.orders.peek();
        check("combat damage preserves reserved paid queue in NBT",saved!=null&&saved.reserved&&saved.progress==7&&saved.paid.size()==1&&saved.paid.get(0).getCount()==3);
        plane.discard();player.teleportTo(level,0,124,30,180,8);((net.minecraft.world.level.saveddata.SavedData)data).setDirty();
    }
}
