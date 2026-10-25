package dev.frontier.industry.acceptance;

import dev.sbw.deepstrike.game.ActiveFlights;
import dev.sbw.deepstrike.game.FlightCollision;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.LauncherBlock;
import dev.sbw.deepstrike.game.LauncherBlockEntity;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.model.MissileGeometry;
import dev.sbw.deepstrike.registry.BFBlocks;
import dev.sbw.deepstrike.registry.BFItems;
import dev.sbw.deepstrike.registry.BFEntities;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Explicit fresh-world fixtures; this class is excluded from all production JARs. */
public final class MissileChecks {
    private record Launch(BlockPos pad, UUID mission, LauncherBlockEntity launcher, Vec3 origin) {}
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();
    private static final Map<UUID, Integer> JOINS = new HashMap<>(), BLASTS = new HashMap<>();
    private static final List<Launch> LAUNCHES = new ArrayList<>();
    private static final java.util.Set<UUID> CLEARED = new java.util.HashSet<>();
    private static final java.util.Set<net.minecraft.world.level.ChunkPos> EXTRA_CHUNKS=new java.util.HashSet<>();
    private static ServerLevel world;
    private static int ticks;
    private static boolean complete;
    private static Method advance, detonate;
    private static UUID protectedDirect, protectedRecipient;
    private static boolean cancelDirectBlast;
    private static UUID protectedSplash;
    private static boolean splashRecipientSeen;
    private static final Map<UUID,net.minecraft.world.level.Explosion> SPLASH_EVENTS=new HashMap<>();
    private static void check(String label, boolean value) {
        RESULTS.put(label, value);
        com.mojang.logging.LogUtils.getLogger().info("COMBAT_MISSILE {} = {}", label, value);
    }

    public static void begin(ServerLevel level, ServerPlayer player) throws ReflectiveOperationException {
        world = level;
        MinecraftForge.EVENT_BUS.register(MissileChecks.class);
        advance = GuidedMissileEntity.class.getDeclaredMethod("advanceFlight", Vec3.class);
        advance.setAccessible(true);
        detonate = GuidedMissileEntity.class.getDeclaredMethod("detonate");
        detonate.setAccessible(true);
        collisionFixtures(level, player);
        directImpactFixtures(level,player);
        vehicleDirectFixtures(level,player);
        nativeSplashFixtures(level,player);
        individualWarheads();
        Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 20; i++) {
            BlockPos pad = i == 0 ? new BlockPos(8, 120, -20)
                    : new BlockPos(-128 + i % 5 * 64, 120, 60 + i / 5 * 64);
            for(int cx=(pad.getX()>>4)-1;cx<=(pad.getX()>>4)+1;cx++)
                for(int cz=(pad.getZ()>>4)-1;cz<=(pad.getZ()>>4)+1;cz++)
                    if(!level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(cx,cz))) {
                        level.setChunkForced(cx,cz,true);EXTRA_CHUNKS.add(new net.minecraft.world.level.ChunkPos(cx,cz));
                    }
            Direction facing = facings[i % 4];
            var state = BFBlocks.FP5_LAUNCHER.get().defaultBlockState().setValue(LauncherBlock.FACING, facing);
            // A small foundation only; every launcher keeps its native multiblock collision.
            for (BlockPos floor : BlockPos.betweenClosed(pad.offset(-4,-1,-4),pad.offset(4,-1,4)))
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
            level.setBlockAndUpdate(pad, state);
            LauncherBlockEntity launcher = (LauncherBlockEntity) level.getBlockEntity(pad);
            launcher.placed(player, new ItemStack(BFItems.FP5_LAUNCHER.get()));
            ItemStack ammo = new ItemStack(BFItems.FP5.get());
            check("FP5 native ammo loaded " + i, launcher.load(ammo, player) && launcher.loadCount() == 1);
            UUID mission = UUID.randomUUID();
            List<RoutePoint> route = List.of(new RoutePoint(pad.getX()+.5+facing.getStepX()*220,
                    152, pad.getZ()+.5+facing.getStepZ()*220, MissileKind.FP5.minSpeed));
            LAUNCHES.add(new Launch(pad, mission, launcher, Vec3.atCenterOf(pad)));
            JOINS.put(mission, 0);
            check("FP5 native countdown accepted " + i, launcher.fire(player, route, false, "100", mission));
            check("FP5 repeat same command idempotent " + i, launcher.fire(player, route, false, "100", mission));
        }
    }

    private static void directImpactFixtures(ServerLevel level,ServerPlayer owner) throws ReflectiveOperationException {
        for(int shot=0;shot<25;shot++) {
            var npc=net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            npc.setNoAi(true);npc.setNoGravity(true);
            npc.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(2000);
            npc.setHealth(2000);npc.setPos(40.5,220,80.5);level.addFreshEntity(npc);
            var missile=new GuidedMissileEntity(level,MissileKind.JAGM);
            Vec3 from=new Vec3(40.5,220.9,70.5);
            if(shot==22)from=from.add(8,0,0); // actual parallel near miss
            missile.moveTo(from.x,from.y,from.z,0,0);
            missile.setOwnerId(owner.getUUID());missile.setMissionId(UUID.randomUUID());
            missile.setMission(List.of(new RoutePoint(from.x,from.y,100.5,MissileKind.JAGM.minSpeed)),false);
            java.lang.reflect.Field random;
            try{random=Entity.class.getDeclaredField("f_19796_");}
            catch(NoSuchFieldException development){random=Entity.class.getDeclaredField("random");}
            random.setAccessible(true);
            var rng=(net.minecraft.util.RandomSource)random.get(missile);
            BLASTS.put(missile.getUUID(),0);
            level.addFreshEntity(missile);
            if(shot==20 || shot==21) {
                protectedDirect=missile.getUUID();protectedRecipient=npc.getUUID();cancelDirectBlast=shot==20;
            }
            BlockPos foreground=new BlockPos(40,220,75);
            if(shot==23)for(BlockPos wall:BlockPos.betweenClosed(foreground.offset(-3,-2,0),foreground.offset(3,4,0)))
                level.setBlockAndUpdate(wall,Blocks.BEDROCK.defaultBlockState());
            // Select on the actual pinned Entity RNG implementation, rather
            // than assuming java.util.Random matches a modded RandomSource.
            long seed=1;
            if(shot==24) {
                seed=0;
                do {rng.setSeed(seed++);} while(rng.nextDouble()>=.02);
                seed--;
            }
            rng.setSeed(seed);double expectedRoll=rng.nextDouble();rng.setSeed(seed);
            float before=npc.getHealth();
            advance.invoke(missile,new Vec3(0,0,20));
            float after=npc.getHealth();int blasts=BLASTS.getOrDefault(missile.getUUID(),0);
            if(shot<20) {
                check("JAGM actual contact damages native NPC "+shot,after<before && blasts==1);
                detonate.invoke(missile);
                check("JAGM duplicate detonate cannot repeat contact or explosion "+shot,
                        npc.getHealth()==after && BLASTS.getOrDefault(missile.getUUID(),0)==1);
            } else if(shot==20)check("Forge canceled explosion start also cancels direct contact",after==before && blasts==1);
            else if(shot==21)check("Forge recipient removal cancels direct component",after==before && blasts==1);
            else if(shot==22)check("parallel near miss cannot acquire direct damage",after==before && blasts==0);
            else if(shot==23)check("nearer solid block prevents direct damage behind it",before-after<=1 && blasts==1);
            else check("intended native dud retains coasting and causes no direct blast damage",after==before && blasts==0 && missile.isAlive());
            if(shot>=23)com.mojang.logging.LogUtils.getLogger().info(
                    "COMBAT_DIRECT_EDGE shot={} health={}->{} blasts={} warhead={} rng={} seed={} firstRoll={} alive={} phase={} report={}",
                    shot,before,after,blasts,missile.warhead(),rng.getClass().getName(),seed,expectedRoll,
                    missile.isAlive(),missile.flightPhase(),ActiveFlights.get(level).report(missile.missionId()));
            protectedDirect=null;protectedRecipient=null;cancelDirectBlast=false;
            if(shot==23)for(BlockPos wall:BlockPos.betweenClosed(foreground.offset(-3,-2,0),foreground.offset(3,4,0)))
                level.removeBlock(wall,false);
            missile.discard();npc.discard();
        }
    }

    private static void collisionFixtures(ServerLevel level, ServerPlayer owner) throws ReflectiveOperationException {
        BlockPos obstacle = new BlockPos(0, 220, 70);
        level.setBlockAndUpdate(obstacle, Blocks.STONE.defaultBlockState());
        for (MissileKind kind : MissileKind.values()) {
            int hits = 0, misses = 0;
            for (int i = 0; i < 20; i++) {
                GuidedMissileEntity missile = new GuidedMissileEntity(level, kind);
                Vec3 from = new Vec3(0.5,220.5,40), movement = new Vec3(0,0,60);
                missile.moveTo(from.x, from.y, from.z, 0, 0);
                missile.setDeltaMovement(movement);
                AABB body = MissileGeometry.worldBounds(kind, from, movement, 0, 0);
                var hit = FlightCollision.sweepSegment(level,missile,from,from.add(movement),body,e -> false);
                if (hit.hit().getType() == HitResult.Type.BLOCK && hit.hit().getLocation().z < obstacle.getZ()+1) hits++;
                Vec3 side = from.add(14,0,0);
                missile.setPos(side);
                var miss = FlightCollision.sweepSegment(level,missile,side,side.add(movement),body.move(14,0,0),e -> false);
                if (miss.hit().getType() == HitResult.Type.MISS) misses++;
            }
            check(kind + " 20 fast stone contacts", hits == 20);
            check(kind + " 20 parallel near misses", misses == 20);
        }
        level.removeBlock(obstacle,false);
        var npc = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        npc.setNoAi(true);
        npc.setNoGravity(true);
        npc.setPos(.5,220,70.5);
        level.addFreshEntity(npc);
        entityContacts(level,npc,"NPC");
        diagonalNearMiss(level,npc);
        npc.discard();
        var vehicle = BFEntities.SU24M.get().create(level);
        vehicle.moveTo(.5,220,70.5,0,0);
        level.addFreshEntity(vehicle);
        entityContacts(level,vehicle,"vehicle");
        vehicle.discard();
        vehicleObbContacts(level);
        entityContacts(level,owner,"player");

        // A genuinely absent neighbouring chunk, outside the fixture's forced area.
        int chunkX = 12000, chunkZ = 12000;
        level.getChunk(chunkX,chunkZ);
        boolean absent = FlightCollision.availableChunk(level,chunkX+1,chunkZ) == null;
        GuidedMissileEntity waiting = new GuidedMissileEntity(level,MissileKind.JAGM);
        Vec3 from = new Vec3(chunkX*16+13,220,chunkZ*16+8), movement = new Vec3(12,0,0);
        waiting.moveTo(from.x,from.y,from.z,-90,0);
        Vec3 prior = new Vec3(.25,0,0);
        waiting.setDeltaMovement(prior);
        boolean advanced = (Boolean) advance.invoke(waiting,movement);
        check("missing foreground chunk is actually absent",absent);
        check("unknown terrain pauses without position or propulsion loss",absent && !advanced
                && waiting.position().equals(from) && waiting.getDeltaMovement().equals(prior));

        GuidedMissileEntity kinetic = new GuidedMissileEntity(level,MissileKind.JAGM);
        kinetic.moveTo(72,220,80,0,0);
        kinetic.setWarhead("none");
        kinetic.setOwnerId(owner.getUUID());
        kinetic.setMission(List.of(new RoutePoint(72,220,300,MissileKind.JAGM.minSpeed)),false);
        UUID mission = UUID.randomUUID();
        kinetic.setMissionId(mission);
        level.addFreshEntity(kinetic);
        BLASTS.put(kinetic.getUUID(),0);
        detonate.invoke(kinetic);
        detonate.invoke(kinetic);
        check("native kinetic detonation resolves once after repeated callback",kinetic.isRemoved()
                && BLASTS.getOrDefault(kinetic.getUUID(),0)==1);
    }

    private record VehicleContact(float damage,boolean duplicateStable,int blasts,float expectedDirect,double impactRange) {}
    private static void vehicleDirectFixtures(ServerLevel level,ServerPlayer owner) throws ReflectiveOperationException {
        var bases=dev.frontier.flamingofix.MissileImpactConfig.class.getDeclaredField("BASE");bases.setAccessible(true);
        var value=(net.minecraftforge.common.ForgeConfigSpec.DoubleValue)((Map<?,?>)bases.get(null)).get("jagm");
        double original=value.get();float baseline=0;
        try {
            for(int shot=0;shot<20;shot++) {
                value.set(0.0);var splash=vehicleContact(level,owner,false,"he");
                value.set(150.0);var direct=vehicleContact(level,owner,false,"he");
                baseline=splash.damage;
                check("JAGM vehicle configured direct exceeds native splash baseline "+shot,
                        splash.blasts==1 && direct.blasts==1 && direct.damage>splash.damage+100);
                check("JAGM vehicle direct and splash resolve once after duplicate callback "+shot,
                        splash.duplicateStable && direct.duplicateStable);
                com.mojang.logging.LogUtils.getLogger().info("COMBAT_VEHICLE_DIRECT sample={} disabledRawDamage={} enabledRaw150Damage={}",shot,splash.damage,direct.damage);
            }
            value.set(150.0);var vetoed=vehicleContact(level,owner,true,"he");
            check("LOWEST Forge vehicle veto suppresses direct and preserves native SBW splash",
                    vetoed.blasts==1 && Math.abs(vetoed.damage-baseline)<1 && vetoed.duplicateStable);
            value.set(0.0);var kineticBaseline=vehicleContact(level,owner,false,"none");
            value.set(150.0);var kineticContact=vehicleContact(level,owner,false,"none");
            var kineticVeto=vehicleContact(level,owner,true,"none");
            check("kinetic underside fixture reaches native radial range with nonzero baseline",
                    kineticBaseline.impactRange<2 && kineticContact.impactRange<2 && kineticBaseline.damage>0);
            check("kinetic contact adds exactly configured component without fabricated SBW radial splash",
                    kineticContact.blasts==1 && kineticBaseline.blasts==1 && kineticContact.expectedDirect>0
                            && Math.abs(kineticContact.damage-kineticBaseline.damage-kineticContact.expectedDirect)<1
                            && kineticContact.duplicateStable);
            check("kinetic contact-only recipient veto preserves exactly native kinetic baseline",
                    kineticVeto.blasts==1 && Math.abs(kineticVeto.damage-kineticBaseline.damage)<1 && kineticVeto.duplicateStable);
            com.mojang.logging.LogUtils.getLogger().info("COMBAT_VEHICLE_KINETIC disabled={} enabled={} expectedDirect={} vetoed={} impactRange={}",
                    kineticBaseline.damage,kineticContact.damage,kineticContact.expectedDirect,kineticVeto.damage,kineticContact.impactRange);
        } finally {
            value.set(original);protectedDirect=null;protectedRecipient=null;cancelDirectBlast=false;
        }
    }
    private static VehicleContact vehicleContact(ServerLevel level,ServerPlayer owner,boolean veto,String warhead) throws ReflectiveOperationException {
        var plane=dev.frontier.aviation.AircraftProfile.F16.entity.get().create(level);Entity target=plane;
        if(warhead.equals("none")) {
            // Native hurt rejects attacker==lastDriver even when both are null.
            // A real connected prior driver makes the original unattributed
            // kinetic45/splash baseline measurable without bypassing that rule.
            var driver=level.getServer().getPlayerList().getPlayerByName("CombatGuest");
            if(driver==null || driver==owner)throw new IllegalStateException("Distinct connected native prior driver required");
            plane.setLastDriverUUID(driver.getStringUUID());
        }
        target.moveTo(56.5,220,70.5,0,0);target.setNoGravity(true);level.addFreshEntity(target);plane.updateOBB();
        var point=plane.getOBBs().get(0).center;
        // Approach the underside for kinetic: a lateral approach hits the outer
        // wing first and cannot exercise SBW's native four-block radial handler.
        boolean kinetic=warhead.equals("none");Vec3 movement=kinetic?new Vec3(0,40,0):new Vec3(0,0,40);
        Vec3 from=new Vec3(point.x,point.y,point.z).subtract(movement.scale(.5));
        var missile=new GuidedMissileEntity(level,MissileKind.JAGM);Entity projectile=missile;
        projectile.moveTo(from.x,from.y,from.z,0,kinetic?-90:0);missile.setOwnerId(owner.getUUID());missile.setMissionId(UUID.randomUUID());
        missile.setWarhead(warhead);Vec3 destination=from.add(movement.scale(1.5));
        missile.setMission(List.of(new RoutePoint(destination.x,destination.y,destination.z,MissileKind.JAGM.minSpeed)),false);
        BLASTS.put(projectile.getUUID(),0);level.addFreshEntity(projectile);
        if(veto) {protectedDirect=projectile.getUUID();protectedRecipient=target.getUUID();}
        java.lang.reflect.Field random;
        try{random=Entity.class.getDeclaredField("f_19796_");}catch(NoSuchFieldException development){random=Entity.class.getDeclaredField("random");}
        random.setAccessible(true);((net.minecraft.util.RandomSource)random.get(projectile)).setSeed(1);
        float expected=plane.getDamageModifier().compute(target,level.damageSources().explosion(projectile,owner),
                dev.frontier.flamingofix.MissileImpactConfig.damage(missile,warhead));
        float before=plane.getHealth();advance.invoke(missile,movement);float after=plane.getHealth();
        int blasts=BLASTS.getOrDefault(projectile.getUUID(),0);detonate.invoke(missile);
        boolean stable=plane.getHealth()==after && BLASTS.getOrDefault(projectile.getUUID(),0)==blasts;
        protectedDirect=null;protectedRecipient=null;projectile.discard();target.discard();
        return new VehicleContact(before-after,stable,blasts,expected,projectile.position().distanceTo(target.position()));
    }

    private record NativeSplash(float damage,float expected,boolean pristine,boolean noContact,
                                boolean attribution,boolean nativeType,boolean duplicateStable,int blasts,boolean vetoSeen) {}

    private static void nativeSplashFixtures(ServerLevel level,ServerPlayer owner) throws ReflectiveOperationException {
        // Deliberately do not advance the missile or assign a contact. These
        // actual native explosions isolate splash from configurable direct damage.
        for(String type:List.of("F16","BMP2","T90")) {
            for(int shot=0;shot<20;shot++) {
                NativeSplash splash=nativeSplash(level,owner,type,"pristine");
                check(type+" pristine native missile splash without contact "+shot,
                        splash.pristine && splash.noContact && splash.attribution && splash.nativeType
                                && splash.damage>0 && Math.abs(splash.damage-splash.expected)<.05);
                check(type+" native splash remains once-only "+shot,splash.duplicateStable && splash.blasts==1);
            }
            NativeSplash own=nativeSplash(level,owner,type,"same-driver");
            check(type+" native same historical driver remains protected",own.expected>0 && own.damage==0 && own.noContact
                    && own.attribution && own.nativeType && own.blasts==1 && own.duplicateStable);
            NativeSplash ownUnresolved=nativeSplash(level,owner,type,"same-unresolved-driver");
            check(type+" same unresolved recorded owner and historical driver remain protected",ownUnresolved.expected>0
                    && ownUnresolved.damage==0 && ownUnresolved.noContact && ownUnresolved.attribution
                    && ownUnresolved.nativeType && ownUnresolved.blasts==1 && ownUnresolved.duplicateStable);
            for(String mode:List.of("unowned","unresolved-owner")) {
                NativeSplash absent=nativeSplash(level,owner,type,mode);
                check(type+" native splash with "+mode+" has no fabricated attacker",absent.pristine && absent.noContact
                        && absent.attribution && absent.nativeType && absent.damage>0
                        && Math.abs(absent.damage-absent.expected)<.05 && absent.blasts==1 && absent.duplicateStable);
            }
            NativeSplash veto=nativeSplash(level,owner,type,"recipient-veto");
            check(type+" Forge recipient veto still prevents native splash",veto.vetoSeen && veto.damage==0
                    && veto.noContact && veto.blasts==1 && veto.duplicateStable);
            NativeSplash canceled=nativeSplash(level,owner,type,"start-veto");
            check(type+" Forge start veto still prevents native splash",canceled.damage==0 && canceled.noContact
                    && canceled.blasts==1 && canceled.duplicateStable);
            unrelatedNativeSplash(level,type);
        }
    }

    private static com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity splashVehicle(ServerLevel level,String type) {
        return switch(type) {
            case "F16"->dev.frontier.aviation.AircraftProfile.F16.entity.get().create(level);
            case "BMP2"->com.atsuishio.superbwarfare.init.ModEntities.BMP_2.get().create(level);
            case "T90"->com.atsuishio.superbwarfare.init.ModEntities.T_90A.get().create(level);
            default->throw new IllegalArgumentException(type);
        };
    }

    private static void unrelatedNativeSplash(ServerLevel level,String type) {
        var vehicle=splashVehicle(level,type);vehicle.moveTo(56.5,220,70.5,0,0);vehicle.setNoGravity(true);
        level.addFreshEntity(vehicle);vehicle.updateOBB();Vec3 center=vehicle.position().add(0,4.5,.25);
        var tnt=new net.minecraft.world.entity.item.PrimedTnt(level,center.x,center.y,center.z,null);
        level.addFreshEntity(tnt);BLASTS.put(tnt.getUUID(),0);
        float before=vehicle.getHealth();
        try {
            float power=type.equals("T90")?5:3;
            var explosion=level.explode(tnt,center.x,center.y,center.z,power,false,net.minecraft.world.level.Level.ExplosionInteraction.NONE);
            float expected=expectedNativeSplash(level,vehicle,explosion.getDamageSource(),center,power);
            // This is intentionally the original unrelated SBW null==null rule,
            // not an assertion that unattributed TNT should be globally repaired.
            check(type+" unrelated vanilla TNT source and native guard remain unchanged",expected>0 && vehicle.getHealth()==before
                    && explosion.getDamageSource().getEntity()==null && explosion.getDamageSource().getDirectEntity()==tnt
                    && explosion.getDamageSource().is(net.minecraft.world.damagesource.DamageTypes.EXPLOSION)
                    && BLASTS.getOrDefault(tnt.getUUID(),0)==1);
        } finally {tnt.discard();vehicle.discard();}
    }

    private static NativeSplash nativeSplash(ServerLevel level,ServerPlayer owner,String type,String mode)
            throws ReflectiveOperationException {
        var vehicle=splashVehicle(level,type);
        vehicle.moveTo(56.5,220,70.5,0,0);vehicle.setNoGravity(true);level.addFreshEntity(vehicle);vehicle.updateOBB();
        boolean pristine=vehicle.getLastDriver()==null && vehicle.getLastDriverUUID().equals("undefined") && vehicle.getPassengers().isEmpty();
        if(mode.equals("same-driver"))vehicle.setLastDriverUUID(owner.getStringUUID());
        // Native T90 subtracts20 raw before explosion/angle multipliers. JAGM
        // splash at4.5m has raw7.526 and is correctly absorbed; Storm's genuine
        // native power5 exceeds that threshold without changing vehicle armor.
        MissileKind kind=type.equals("T90")?MissileKind.STORM_SHADOW:MissileKind.JAGM;
        var missile=new GuidedMissileEntity(level,kind);Entity projectile=missile;
        Vec3 center=vehicle.position().add(0,4.5,.25);
        projectile.moveTo(center.x,center.y,center.z,0,0);missile.setMissionId(UUID.randomUUID());missile.setWarhead("he");
        UUID recordedOwner=mode.equals("unowned")?null:
                mode.equals("unresolved-owner") || mode.equals("same-unresolved-driver")?UUID.randomUUID():owner.getUUID();
        missile.setOwnerId(recordedOwner);
        if(mode.equals("same-unresolved-driver"))vehicle.setLastDriverUUID(recordedOwner.toString());
        missile.setMission(List.of(new RoutePoint(center.x,center.y,center.z+80,kind.minSpeed)),false);
        BLASTS.put(projectile.getUUID(),0);SPLASH_EVENTS.put(projectile.getUUID(),null);level.addFreshEntity(projectile);
        boolean noContact=((dev.frontier.flamingofix.MissileContactState)(Object)missile).flamingo$takeDirectContact()==null;
        Entity resolved=recordedOwner==null?null:level.getEntity(recordedOwner);
        var original=level.damageSources().explosion(projectile,null);
        var expectedSource=new net.minecraft.world.damagesource.DamageSource(original.typeHolder(),projectile,resolved,original.sourcePositionRaw());
        float expected=expectedNativeSplash(level,vehicle,expectedSource,center,kind.blastPower);
        if(mode.endsWith("veto")) {
            protectedDirect=projectile.getUUID();protectedRecipient=vehicle.getUUID();
            cancelDirectBlast=mode.equals("start-veto");protectedSplash=mode.equals("recipient-veto")?projectile.getUUID():null;
        }
        splashRecipientSeen=false;
        java.lang.reflect.Field random;
        try{random=Entity.class.getDeclaredField("f_19796_");}catch(NoSuchFieldException development){random=Entity.class.getDeclaredField("random");}
        random.setAccessible(true);((net.minecraft.util.RandomSource)random.get(projectile)).setSeed(1);
        float before=vehicle.getHealth();
        try {
            detonate.invoke(missile);
            float after=vehicle.getHealth();int blasts=BLASTS.getOrDefault(projectile.getUUID(),0);
            var observed=SPLASH_EVENTS.get(projectile.getUUID());
            boolean attribution=observed!=null && observed.getDamageSource().getEntity()==resolved
                    && observed.getDamageSource().getDirectEntity()==projectile;
            boolean nativeType=observed!=null && observed.getDamageSource().typeHolder()==original.typeHolder()
                    && observed.getDamageSource().is(net.minecraft.world.damagesource.DamageTypes.EXPLOSION);
            noContact&=((dev.frontier.flamingofix.MissileContactState)(Object)missile).flamingo$takeDirectContact()==null;
            detonate.invoke(missile);
            boolean duplicate=vehicle.getHealth()==after && BLASTS.getOrDefault(projectile.getUUID(),0)==blasts;
            com.mojang.logging.LogUtils.getLogger().info(
                    "COMBAT_NATIVE_SPLASH type={} kind={} mode={} damage={} expectedNative={} pristine={} noContact={} owner={} resolved={} source={} nativeType={} blasts={} vetoSeen={}",
                    type,kind,mode,before-after,expected,pristine,noContact,recordedOwner,resolved==null?null:resolved.getUUID(),
                    observed==null?null:observed.getDamageSource(),nativeType,blasts,splashRecipientSeen);
            return new NativeSplash(before-after,expected,pristine,noContact,attribution,nativeType,duplicate,blasts,splashRecipientSeen);
        } finally {
            protectedDirect=null;protectedRecipient=null;cancelDirectBlast=false;protectedSplash=null;
            SPLASH_EVENTS.remove(projectile.getUUID());projectile.discard();vehicle.discard();
        }
    }

    private static float expectedNativeSplash(ServerLevel level,
            com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity vehicle,
            net.minecraft.world.damagesource.DamageSource source,Vec3 center,float power) {
        double diameter=power*2;
        float exposure=vehicle.enableAABB()?net.minecraft.world.level.Explosion.getSeenPercent(center,vehicle)
                :com.atsuishio.superbwarfare.tools.CustomExplosion.Companion.getSeenPercentOptimized(level,center,vehicle);
        double strength=(1-Math.sqrt(vehicle.distanceToSqr(center))/diameter)*exposure;
        float raw=(float)((strength*strength+strength)/2*7*diameter+1);
        return vehicle.getDamageModifier().compute(vehicle,source,raw);
    }

    private static void diagonalNearMiss(ServerLevel level,Entity target) throws ReflectiveOperationException {
        Vec3 from=new Vec3(30,220.5,70),direction=new Vec3(-Math.sqrt(.5),0,Math.sqrt(.5));
        Vec3 movement=direction.scale(3),to=from.add(movement);
        var missile=new GuidedMissileEntity(level,MissileKind.FP5);
        missile.moveTo(from.x,from.y,from.z,45,0);
        AABB body=MissileGeometry.worldBounds(MissileKind.FP5,from,movement,45,0);
        Object mesh=dev.frontier.flamingofix.NativeMissileSweep.prepare(MissileKind.FP5,direction,movement,45,0,bone->true);
        var expand=FlightCollision.class.getDeclaredMethod("expandForBody",AABB.class,AABB.class,Vec3.class);
        expand.setAccessible(true);
        boolean reproduced=false,missed=false;
        search:for(double x=body.minX+.5;x<body.maxX-.5;x+=.5)for(double z=body.minZ+.5;z<body.maxZ-.5;z+=.5) {
            target.setPos(x,220,z);
            AABB box=target.getBoundingBox().inflate(target.getPickRadius());
            AABB broad=(AABB)expand.invoke(null,box,body,from);
            boolean oldHit=broad.contains(from)||broad.clip(from,to).isPresent();
            if(oldHit && dev.frontier.flamingofix.NativeMissileSweep.contact(mesh,box,from,to)==null) {
                reproduced=true;
                var contact=FlightCollision.sweepSegment(level,missile,from,to,body,e->e==target);
                missed=contact.hit().getType()==HitResult.Type.MISS;
                break search;
            }
        }
        check("diagonal FP5 broad-AABB phantom hit reproduced",reproduced);
        check("applied native mesh mixin rejects diagonal phantom hit",reproduced && missed);
    }

    private static void entityContacts(ServerLevel level,Entity target,String label) {
        Vec3 center=target.getBoundingBox().getCenter();
        if(target instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity vehicle) {
            vehicle.updateOBB();
            if(!vehicle.getOBBs().isEmpty()) {
                var nativeCenter=vehicle.getOBBs().get(0).center;
                center=new Vec3(nativeCenter.x,nativeCenter.y,nativeCenter.z);
            }
        }
        for(MissileKind kind:MissileKind.values()) {
            int hits=0,misses=0;
            for(int i=0;i<20;i++) {
                GuidedMissileEntity missile=new GuidedMissileEntity(level,kind);
                Vec3 from=center.add(0,0,-30),movement=new Vec3(0,0,60);
                missile.moveTo(from.x,from.y,from.z,0,0);
                AABB body=MissileGeometry.worldBounds(kind,from,movement,0,0);
                var contact=FlightCollision.sweepSegment(level,missile,from,from.add(movement),body,e->e==target);
                if(contact.hit() instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity()==target)hits++;
                Vec3 side=from.add(20,0,0);
                missile.setPos(side);
                var miss=FlightCollision.sweepSegment(level,missile,side,side.add(movement),body.move(20,0,0),e->e==target);
                if(miss.hit().getType()==HitResult.Type.MISS)misses++;
            }
            check(kind+" 20 fast "+label+" swept contacts",hits==20);
            check(kind+" 20 "+label+" parallel near misses",misses==20);
        }
    }

    private static void vehicleObbContacts(ServerLevel level) {
        var plane=dev.frontier.aviation.AircraftProfile.F16.entity.get().create(level);
        Entity target=plane;
        target.setNoGravity(true);level.addFreshEntity(target);
        int beyondNativeQuery=0;
        for(int shot=0;shot<20;shot++) {
            target.moveTo(96,220,70,shot%4*90,0);
            plane.setRoll((shot%5-2)*7.5f);plane.setPrevRoll(plane.getRoll());plane.updateOBB();
            var wing=plane.getOBBs().get(1);var axes=wing.getAxes();var extents=wing.extents();
            Vec3 center=new Vec3(wing.center.x,wing.center.y,wing.center.z);
            Vec3 right=new Vec3(axes[0].x,axes[0].y,axes[0].z),up=new Vec3(axes[1].x,axes[1].y,axes[1].z),forward=new Vec3(axes[2].x,axes[2].y,axes[2].z);
            Vec3 point=center.add(right.scale(extents.x-.08)),from=point.subtract(forward.scale(20)),movement=forward.scale(40);
            var missile=new GuidedMissileEntity(level,MissileKind.JAGM);
            float yaw=(float)Math.toDegrees(Math.atan2(-forward.x,forward.z)),pitch=(float)-Math.toDegrees(Math.asin(forward.y));
            missile.moveTo(from.x,from.y,from.z,yaw,pitch);
            AABB body=MissileGeometry.worldBounds(MissileKind.JAGM,from,movement,yaw,pitch);
            if(!target.getBoundingBox().intersects(body.expandTowards(movement).inflate(1)))beyondNativeQuery++;
            var hit=FlightCollision.sweepSegment(level,missile,from,from.add(movement),body,e->e==target);
            check("JAGM rotated native F16 outer-wing contact "+shot,hit.hit() instanceof net.minecraft.world.phys.EntityHitResult entity && entity.getEntity()==target);
            // Shift the entire path above the thin wing. It remains inside the
            // generic aircraft box for many attitudes but misses all real parts.
            Vec3 missFrom=from.add(up.scale(2));missile.setPos(missFrom);
            var miss=FlightCollision.sweepSegment(level,missile,missFrom,missFrom.add(movement),body.move(up.scale(2)),e->e==target);
            check("JAGM rotated F16 empty wing space stays a miss "+shot,miss.hit().getType()==HitResult.Type.MISS);
            if(shot==0) {
                Vec3 stop=from.add(forward.scale(7));BlockPos wall=BlockPos.containing(stop);
                level.setBlockAndUpdate(wall,Blocks.BEDROCK.defaultBlockState());
                missile.setPos(from);
                var blocked=FlightCollision.sweepSegment(level,missile,from,from.add(movement),body,e->e==target);
                check("nearer native solid block wins before outer-wing contact",blocked.hit().getType()==HitResult.Type.BLOCK);
                level.removeBlock(wall,false);
            }
        }
        check("outer-wing fixture includes paths omitted by original native entity query",beyondNativeQuery>0);
        target.discard();
    }

    private static void individualWarheads() {
        var kinds=MissileKind.values();
        var items=List.of(BFItems.FP5.get(),BFItems.STORM_SHADOW.get(),BFItems.JAGM.get(),
                BFItems.PEACEKEEPER.get(),BFItems.SPIKE_NLOS_MISSILE.get());
        for(int i=0;i<kinds.length;i++) {
            ItemStack first=new ItemStack(items.get(i)),second=new ItemStack(items.get(i));
            var he=new MissileData(kinds[i],"he","rocket",List.of(),"default",900,1200);
            var other=new MissileData(kinds[i],i==4?"shaped_charge":i==3?"nuclear":"penetrating",
                    "rocket",List.of("range"),"olive",500,1800);
            he.writeToItemStack(first);other.writeToItemStack(second);
            ItemStack restored=ItemStack.of(first.save(new net.minecraft.nbt.CompoundTag()));
            var a=MissileData.fromItemStack(restored);var b=MissileData.fromItemStack(second);
            check(kinds[i]+" saved individual warheads and fuel remain independent",a.kind()==kinds[i]
                    && a.warhead().equals("he") && a.fuel()==900 && !a.hasRangeModule()
                    && b.warhead().equals(other.warhead()) && b.fuel()==500 && b.hasRangeModule());
        }
    }

    public static void tick(ServerLevel level) {
        if (complete || level != world) return;
        ticks++;
        for (Launch launch : LAUNCHES) {
            if (CLEARED.contains(launch.mission)) continue;
            Entity entity = level.getEntity(launch.mission);
            if (entity instanceof GuidedMissileEntity missile && missile.isAlive()
                    && missile.position().distanceTo(launch.origin) > 24) {
                CLEARED.add(launch.mission);
                // This fixture checks the launcher zone; avoid 20 unrelated target blasts.
                if (!launch.pad.equals(LAUNCHES.get(0).pad) || ticks > 180) missile.discard();
            }
        }
        if (ticks >= 220 && CLEARED.size() == 20 || ticks >= 1600) {
            for (int i=0;i<LAUNCHES.size();i++) {
                Launch launch=LAUNCHES.get(i);
                check("FP5 launch survived pad and flew 24 blocks " + i,CLEARED.contains(launch.mission));
                check("FP5 countdown spawned exactly one missile " + i,JOINS.getOrDefault(launch.mission,0)==1);
                check("FP5 launch consumed exactly one native slot " + i,launch.launcher.loadCount()==0);
                if(!CLEARED.contains(launch.mission))com.mojang.logging.LogUtils.getLogger().warn(
                        "COMBAT_FP5_FAILED index={} pad={} report={} launcherError={}",i,launch.pad,
                        ActiveFlights.get(level).report(launch.mission),launch.launcher.getLastFireError());
                Entity entity=level.getEntity(launch.mission);
                if(entity!=null)entity.discard();
            }
            complete=true;
            EXTRA_CHUNKS.forEach(chunk->level.setChunkForced(chunk.x,chunk.z,false));
            MinecraftForge.EVENT_BUS.unregister(MissileChecks.class);
        }
    }
    public static Map<String,Boolean> results() { return Map.copyOf(RESULTS); }
    public static boolean done() { return complete; }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true)
    public static void joined(EntityJoinLevelEvent event) {
        if(event.getLevel()!=world || event.isCanceled())return;
        UUID id=event.getEntity().getUUID();
        if(JOINS.containsKey(id))JOINS.merge(id,1,Integer::sum);
    }
    @SubscribeEvent public static void blast(ExplosionEvent.Start event) {
        Entity source=event.getExplosion().getDirectSourceEntity();
        if(event.getLevel()==world && source!=null && BLASTS.containsKey(source.getUUID()))
            BLASTS.merge(source.getUUID(),1,Integer::sum);
        if(event.getLevel()==world && source!=null && SPLASH_EVENTS.containsKey(source.getUUID()))
            SPLASH_EVENTS.put(source.getUUID(),event.getExplosion());
        if(source!=null && source.getUUID().equals(protectedDirect) && cancelDirectBlast)event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void splashRecipients(ExplosionEvent.Detonate event) {
        Entity source=event.getExplosion().getDirectSourceEntity();
        if(event.getLevel()==world && source!=null && source.getUUID().equals(protectedSplash)) {
            splashRecipientSeen|=event.getAffectedEntities().stream().anyMatch(entity->entity.getUUID().equals(protectedRecipient));
            event.getAffectedEntities().removeIf(entity->entity.getUUID().equals(protectedRecipient));
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void recipients(ExplosionEvent.Detonate event) {
        Entity source=event.getExplosion().getDirectSourceEntity();
        if(event.getLevel()==world && source!=null && source.getUUID().equals(protectedDirect))
            event.getAffectedEntities().removeIf(entity->entity.getUUID().equals(protectedRecipient));
    }
    private MissileChecks() {}
}
