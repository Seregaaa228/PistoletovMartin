package dev.frontier.industry.combat;

import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import com.diego.recruitsvillages.data.FactionData;
import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.diego.recruitsvillages.data.RecruitUnit;
import net.minecraft.ChatFormatting;
import com.mojang.logging.LogUtils;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Opt-in isolated-world integration fixture; not included in a normal production JAR. */
public final class CombatSmoke {
    private static final Logger LOG = LogUtils.getLogger();
    private static final List<String> RESULTS = new ArrayList<>();
    private static final List<Entity> FIXTURES = new ArrayList<>();
    private static final RuntimeDiagnostics DIAGNOSTICS = new RuntimeDiagnostics();
    private static ServerLevel world;
    private static ServerPlayer player;
    private static ServerPlayer defender;
    private static LauncherEntity patriot;
    private static LauncherEntity s300;
    private static LauncherEntity s400;
    private static Phantom airTarget;
    private static Entity alliedAir;
    private static String countryA, countryB;
    private static String priorPlayerTeam;
    private static String priorDefenderTeam;
    private static UUID offlineLeaderA, offlineMemberA, offlineMemberB;
    private static final List<UUID> NATIVE_VILLAGES = new ArrayList<>();
    private static final List<LauncherEntity> launches = new ArrayList<>();
    private static BlockPos base;
    private static long started;
    private static boolean completed;
    private static int ammoAfterOperatorLoss;
    private static int shotsAfterOperatorLoss;
    private CombatSmoke() { }
    public static void run(ServerLevel level, ServerPlayer owner) {
        if (System.getProperty("frontier.industrySmokeTest") == null) throw new IllegalStateException("CombatSmoke is isolated-test-only");
        RESULTS.clear(); FIXTURES.clear(); launches.clear(); completed = false; world = level; player = owner;
        MinecraftForge.EVENT_BUS.unregister(DIAGNOSTICS);
        MinecraftForge.EVENT_BUS.register(DIAGNOSTICS);
        base = new BlockPos(owner.blockPosition().getX(), Math.min(level.getMaxBuildHeight() - 96, owner.blockPosition().getY() + 12), owner.blockPosition().getZ());
        // A reused disposable save can retain a previous training phantom/aircraft inside PVO range.
        // Remove only tagged aerial entities inside this bounded arena, before creating any current fixture.
        List<Entity> oldAirTargets = level.getEntities((Entity) null, new AABB(base).inflate(256), entity -> entity.getType().is(CombatContent.AIR_TARGETS));
        diagnostic("arena pre-existing tagged air targets=" + oldAirTargets.size());
        for (Entity old : oldAirTargets) { diagnostic("arena removing pre-existing air target " + entityState(old)); old.discard(); }
        // The caller selects an isolated copied test world. This small elevated arena never touches a user's normal save.
        for (int x = -2; x <= 76; x++) for (int z = -3; z <= 3; z++)
            level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
        owner.teleportTo(level, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
        defender = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("c332bc3f-13ee-4b47-bca5-3badc8f0760a"), "CombatDefenseTest"));
        defender.setPos(base.getX() + 20.5, base.getY(), base.getZ() + 0.5);
        setupNativeDiplomacy();
        for (int i = 0; i < 3; i++) launches.add(station(owner, LauncherEntity.Kind.BALLISTIC, base.offset(4 + i * 4, 0, 0), i < 2 ? 1 : 0));
        patriot = station(defender, LauncherEntity.Kind.PATRIOT, base.offset(20, 0, 0), 4);
        s300 = station(defender, LauncherEntity.Kind.S300, base.offset(24, 0, 0), 4);
        s400 = station(defender, LauncherEntity.Kind.S400, base.offset(28, 0, 0), 4);
        verifyInstalledAircraftOwnership();
        Vec3 target = base.offset(68, 0, 0).getCenter();
        CombatOperations.LaunchResult shortage = CombatOperations.launch(owner, target, 3);
        check(!shortage.success() && launches.get(0).ammunition() == 1 && launches.get(1).ammunition() == 1,
                "shortage rejects complete salvo without consumption");
        launches.get(2).load(new ItemStack(CombatContent.BALLISTIC_MISSILE.get()));
        CompoundTag saved = new CompoundTag(); patriot.saveWithoutId(saved);
        LauncherEntity restored = CombatContent.LAUNCHER.get().create(level); restored.load(saved);
        check(defender.getUUID().equals(restored.ownerUUID()) && patriot.operatorUUID().equals(restored.operatorUUID())
                && restored.kind() == LauncherEntity.Kind.PATRIOT && restored.ammunition() == 4, "launcher owner crew kind ammunition save roundtrip");
        SpawnCancellation cancellation = new SpawnCancellation(); MinecraftForge.EVENT_BUS.register(cancellation);
        CombatOperations.LaunchResult cancelled;
        try { cancelled = CombatOperations.launch(owner, target, 3); }
        finally { MinecraftForge.EVENT_BUS.unregister(cancellation); }
        check(!cancelled.success() && launches.stream().allMatch(station -> station.ammunition() == 1 && station.shotsFired() == 0)
                && level.getEntitiesOfClass(BallisticMissileEntity.class, owner.getBoundingBox().inflate(100)).isEmpty(), "cancelled entity spawn rolls back whole salvo and ammunition");
        check(!CombatOperations.launch(defender, target, 1).success(), "foreign player cannot command other owner's launchers");
        check(!CombatOperations.launch(owner, target, 4).success() && launches.stream().allMatch(station -> station.ammunition() == 1), "count bypass rejected server-side");
        CombatOperations.LaunchResult order = CombatOperations.launch(owner, target, 3);
        check(order.success() && order.missiles().size() == 3 && launches.stream().allMatch(station -> station.ammunition() == 0 && station.shotsFired() == 1), "three physical crewed launchers fire and consume three real rounds");
        check(order.missiles().stream().allMatch(missile -> missile.launchedAt() == level.getGameTime() && missile.elapsedTicks() == 0), "all three missiles created on one native server tick");
        for (BallisticMissileEntity missile : order.missiles()) FIXTURES.add(missile);
        BallisticMissileEntity sample = order.missiles().get(0);
        check(!s300.canTarget(sample) && !s400.canTarget(sample) && patriot.canTarget(sample), "only Patriot accepts hostile ballistic missile");
        BallisticMissileEntity friendly = CombatContent.MISSILE.get().create(level);
        friendly.configure(defender.getUUID(), patriot.getUUID(), patriot.muzzle().add(0, 8, 0), target);
        check(!patriot.canTarget(friendly), "Patriot excludes same-owner ballistic missile");
        CompoundTag missileSaved = new CompoundTag(); sample.saveWithoutId(missileSaved);
        BallisticMissileEntity missileRestored = CombatContent.MISSILE.get().create(level); missileRestored.load(missileSaved);
        check(owner.getUUID().equals(missileRestored.ownerUUID()) && missileRestored.targetPosition().equals(target)
                && missileRestored.launchedAt() == sample.launchedAt(), "missile owner target launch-time save roundtrip");
        verifyNativeDiplomacy(sample);
        setRelation(DiplomacyStatus.ALLY);
        // Actual allied projectiles and an airborne craft remain in the live acquisition volume for ten native ticks.
        EntityType<?> aircraft = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("simpleplanes", "plane"));
        alliedAir = aircraft == null ? EntityType.PHANTOM.create(level) : aircraft.create(level);
        alliedAir.setPos(base.getX() + 24, base.getY() + 14, base.getZ() + 5);
        alliedAir.setNoGravity(true); alliedAir.setOnGround(false);
        if (alliedAir instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
        alliedAir.getPersistentData().putUUID("frontierIndustryVehicleOwner", player.getUUID());
        boolean alliedAdded = level.addFreshEntity(alliedAir); FIXTURES.add(alliedAir);
        diagnostic("alliedAir addFreshEntity=" + alliedAdded);
        started = level.getGameTime();
        snapshot("setup");
        LOG.info("COMBAT_SMOKE waiting for ordinary server entity ticks");
    }
    public static void tick(ServerLevel level) {
        if (world != level || completed || patriot == null) return;
        long elapsed = level.getGameTime() - started;
        if (elapsed == 1 || elapsed == 5 || elapsed == 10 || elapsed == 11 || elapsed == 15 || elapsed == 20 || elapsed == 30 || elapsed == 40
                || elapsed == 60 || elapsed == 75 || elapsed == 85 || elapsed == 105) snapshot("elapsed=" + elapsed);
        if (elapsed == 10) {
            check(patriot.shotsFired() == 0 && patriot.ammunition() == 4 && patriot.interceptions() == 0,
                    "native ALLY protects live ballistic salvo from autonomous Patriot fire across ten server ticks");
            check(s300.shotsFired() == 0 && s400.shotsFired() == 0 && s300.ammunition() == 4 && s400.ammunition() == 4
                    && alliedAir != null && alliedAir.isAlive(), "native ALLY protects a real airborne allied craft from ordinary autonomous defense");
            alliedAir.discard();
            setRelation(DiplomacyStatus.ENEMY);
            List<BallisticMissileEntity> liveMissiles = FIXTURES.stream().filter(entity -> entity instanceof BallisticMissileEntity && entity.isAlive())
                    .map(entity -> (BallisticMissileEntity) entity).toList();
            check(!liveMissiles.isEmpty() && liveMissiles.stream().allMatch(missile -> !s300.canTarget(missile) && !s400.canTarget(missile)),
                    "ordinary complexes reject every live current ballistic missile after ENEMY transition");
            check(FactionEvents.recruitsDiplomacyManager.getRelation(countryB, countryA) == DiplomacyStatus.ENEMY
                    && CombatOwnership.hostile(level, defender.getUUID(), launches.get(0))
                    && level.getEntitiesOfClass(BallisticMissileEntity.class, patriot.getBoundingBox().inflate(192)).stream().anyMatch(patriot::canTarget),
                    "live native ALLY to ENEMY transition enables existing hostile incoming salvo");
        }
        if (elapsed == 75) {
            check(patriot.shotsFired() > 0 && patriot.ammunition() < 4 && patriot.interceptions() > 0,
                    "Patriot autonomously fires visible interceptor consumes stock and destroys incoming missile");
            check(s300.ammunition() == 4 && s400.ammunition() == 4 && s300.shotsFired() == 0 && s400.shotsFired() == 0,
                    "ordinary complexes never fire at ballistic salvo");
            // A real live NPC is now removed; subsequent incoming projectiles must not spend ammunition.
            Entity operator = level.getEntity(patriot.operatorUUID()); if (operator != null) operator.discard();
            ammoAfterOperatorLoss = patriot.ammunition(); shotsAfterOperatorLoss = patriot.shotsFired();
            BallisticMissileEntity threat = CombatContent.MISSILE.get().create(level);
            threat.configure(player.getUUID(), launches.get(0).getUUID(), patriot.muzzle().add(0, 16, 0), base.offset(72, 1, 0).getCenter());
            boolean threatAdded = level.addFreshEntity(threat); FIXTURES.add(threat);
            airTarget = EntityType.PHANTOM.create(level);
            airTarget.setPos(base.getX() + 25, base.getY() + 12, base.getZ() + 5);
            airTarget.setNoAi(true); airTarget.setNoGravity(true); airTarget.setPersistenceRequired();
            boolean targetAdded = level.addFreshEntity(airTarget); FIXTURES.add(airTarget);
            diagnostic("ordinary phase threatAdded=" + threatAdded + " airTargetAdded=" + targetAdded + " " + entityState(airTarget));
            check(s300.canTarget(airTarget) && s400.canTarget(airTarget), "ordinary complexes acquire actual airborne tagged hostile target");
        }
        if (elapsed >= 105) {
            check(patriot.operator() == null && patriot.ammunition() == ammoAfterOperatorLoss && patriot.shotsFired() == shotsAfterOperatorLoss,
                    "dead or missing operator stops autonomous defense");
            check((s300.shotsFired() > 0 || s400.shotsFired() > 0) && (s300.ammunition() < 4 || s400.ammunition() < 4)
                    && airTarget != null && !airTarget.isAlive(), "ordinary complexes autonomously consume ammunition and hit a real airborne entity");
            completed = true;
            MinecraftForge.EVENT_BUS.unregister(DIAGNOSTICS);
            for (Entity fixture : FIXTURES) if (!fixture.isRemoved()) fixture.discard();
            cleanupNativeDiplomacy();
            LOG.info("COMBAT_SMOKE finished checks={} failures={}", RESULTS.size(), RESULTS.stream().filter(value -> value.startsWith("FAIL")).count());
        }
    }
    public static boolean finished() { return completed; }
    public static List<String> results() { return List.copyOf(RESULTS); }
    public static boolean passed() { return completed && RESULTS.stream().noneMatch(value -> value.startsWith("FAIL")); }
    private static void diagnostic(String detail) {
        String line = "DIAG " + detail; RESULTS.add(line); LOG.info("COMBAT_SMOKE {}", line);
    }
    private static String entityState(Entity entity) {
        if (entity == null) return "null";
        String health = "n/a";
        if (entity instanceof LivingEntity living) health = Float.toString(living.getHealth());
        else try { health = String.valueOf(entity.getClass().getMethod("getHealth").invoke(entity)); }
        catch (ReflectiveOperationException ignored) { }
        String state = "type=" + EntityType.getKey(entity.getType()) + " uuid=" + entity.getUUID() + " alive=" + entity.isAlive()
                + " removed=" + entity.getRemovalReason() + " tickCount=" + entity.tickCount + " pos=" + entity.position()
                + " health=" + health + " invulnerable=" + entity.isInvulnerable() + " airTagged=" + entity.getType().is(CombatContent.AIR_TARGETS)
                + " onGround=" + entity.onGround() + " full=" + world.hasChunkAt(entity.blockPosition())
                + " entitiesLoaded=" + world.areEntitiesLoaded(new net.minecraft.world.level.ChunkPos(entity.blockPosition()).toLong())
                + " entityTicking=" + world.isPositionEntityTicking(entity.blockPosition()) + " inUuidMap=" + (world.getEntity(entity.getUUID()) == entity)
                + " owner=" + CombatOwnership.owner(entity) + " vehicle=" + (entity.getVehicle() == null ? "none" : entity.getVehicle().getUUID());
        if (entity instanceof BallisticMissileEntity missile) {
            CompoundTag tag = missile.saveWithoutId(new CompoundTag());
            state += " flightElapsed=" + missile.elapsedTicks() + "/" + missile.flightDuration() + " lastTick=" + tag.getLong("LastTick")
                    + " now=" + world.getGameTime() + " launchedAt=" + missile.launchedAt();
        }
        return state;
    }
    private static void snapshot(String label) {
        diagnostic(label + " countries=" + CombatOwnership.country(world, player.getUUID()) + "/" + CombatOwnership.country(world, defender.getUUID())
                + " relation=" + FactionEvents.recruitsDiplomacyManager.getRelation(countryB, countryA)
                + " treaty=" + (FactionEvents.recruitsTreatyManager != null && FactionEvents.recruitsTreatyManager.hasTreaty(countryB, countryA))
                + " gameTime=" + world.getGameTime());
        for (LauncherEntity station : List.of(patriot, s300, s400)) {
            Entity crew = station.operatorUUID() == null ? null : world.getEntity(station.operatorUUID());
            diagnostic(label + " station=" + station.kind() + " " + entityState(station) + " shots=" + station.shotsFired()
                    + " ammo=" + station.ammunition() + " interceptions=" + station.interceptions() + " readyAt=" + station.readyAt()
                    + " active=" + station.active() + " ready=" + station.ready() + " operatorValid=" + (station.operator() != null)
                    + " seats=" + station.getPassengers().stream().map(Entity::getUUID).toList() + " crew=" + entityState(crew)
                    + " canTargetAlliedAir=" + (alliedAir != null && station.canTarget(alliedAir))
                    + " canTargetAir=" + (airTarget != null && station.canTarget(airTarget)));
        }
        diagnostic(label + " alliedAir=" + entityState(alliedAir));
        diagnostic(label + " airTarget=" + entityState(airTarget));
        for (Entity fixture : FIXTURES) if (fixture instanceof BallisticMissileEntity) diagnostic(label + " missile=" + entityState(fixture)
                + " country=" + CombatOwnership.country(world, CombatOwnership.owner(fixture)) + " patriotCanTarget=" + patriot.canTarget(fixture));
        for (InterceptorEntity shot : world.getEntitiesOfClass(InterceptorEntity.class, patriot.getBoundingBox().inflate(192)))
            diagnostic(label + " interceptor=" + entityState(shot) + " target=" + shot.targetUUID()
                    + " victim=" + entityState(shot.targetUUID() == null ? null : world.getEntity(shot.targetUUID())));
    }
    private static boolean watched(Entity entity) {
        return entity == alliedAir || entity == airTarget || entity instanceof LauncherEntity || entity instanceof BallisticMissileEntity
                || entity instanceof InterceptorEntity || entity instanceof WorkerEntity;
    }
    private static String eventSource() {
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String type = frame.getClassName();
            if (type.startsWith(CombatSmoke.class.getName())) continue;
            if (!(type.startsWith("dev.frontier.") || type.startsWith("xyz.przemyk.") || type.startsWith("com.logic.superbrecruits.")
                    || type.startsWith("com.atsuishio."))) continue;
            if (count++ > 0) result.append(" <- "); result.append(frame);
            if (count == 3) break;
        }
        return result.toString();
    }
    private static final class RuntimeDiagnostics {
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void joined(EntityJoinLevelEvent event) {
            if (event.getLevel() != world || completed || !watched(event.getEntity())) return;
            diagnostic("JOIN disk=" + event.loadedFromDisk() + " canceled=" + event.isCanceled() + " source=" + eventSource() + " " + entityState(event.getEntity()));
        }
        @SubscribeEvent public void left(EntityLeaveLevelEvent event) {
            if (event.getLevel() != world || completed || !watched(event.getEntity())) return;
            diagnostic("LEAVE source=" + eventSource() + " " + entityState(event.getEntity()));
        }
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.getEntity().level() != world || completed || !watched(event.getEntity())) return;
            diagnostic("HURT canceled=" + event.isCanceled() + " damage=" + event.getAmount() + " source=" + event.getSource().getMsgId()
                    + " attacker=" + entityState(event.getSource().getEntity()) + " victim=" + entityState(event.getEntity()));
        }
    }
    private static LauncherEntity station(ServerPlayer owner, LauncherEntity.Kind kind, BlockPos pos, int ammunition) {
        LauncherEntity station = CombatContent.LAUNCHER.get().create(world); station.configure(kind, owner.getUUID());
        station.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5); world.addFreshEntity(station); FIXTURES.add(station);
        WorkerEntity crew = IndustryContent.WORKER.get().create(world); crew.setPos(station.position().add(1, 0, 1));
        crew.getPersistentData().putString("frontierIndustryOwner", owner.getUUID().toString());
        crew.getPersistentData().putString("frontierIndustryRole", "production");
        world.addFreshEntity(crew); FIXTURES.add(crew);
        // Setup authorizes the same server owner/range validation as ordinary gameplay.
        Vec3 prior = owner.position(); owner.setPos(station.position().add(0, 0, 3));
        check(station.assign(owner, crew), "actual NPC mounted in " + kind + " launcher"); owner.setPos(prior);
        if (ammunition > 0) {
            ItemStack rounds = new ItemStack(station.ammunitionItem(), ammunition);
            check(station.load(rounds) == ammunition && rounds.isEmpty(), "actual stack loaded into " + kind + " launcher");
        }
        return station;
    }
    private static void setupNativeDiplomacy() {
        var manager = FactionEvents.recruitsFactionManager;
        if (manager == null || FactionEvents.recruitsDiplomacyManager == null) throw new IllegalStateException("Native Recruits diplomacy managers not initialized");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        countryA = "cba_" + suffix; countryB = "cbb_" + suffix;
        offlineLeaderA = UUID.randomUUID(); offlineMemberA = UUID.randomUUID(); offlineMemberB = UUID.randomUUID();
        manager.addTeam(countryA, "Combat test A", offlineLeaderA, "OfflineLeaderA", new CompoundTag(), (byte) 0, ChatFormatting.BLUE);
        manager.addTeam(countryB, "Combat test B", UUID.randomUUID(), "OfflineLeaderB", new CompoundTag(), (byte) 1, ChatFormatting.RED);
        manager.getFactionByStringID(countryA).addMember(player.getUUID(), player.getGameProfile().getName());
        manager.getFactionByStringID(countryA).addMember(offlineMemberA, "OfflineMemberA");
        manager.getFactionByStringID(countryB).addMember(defender.getUUID(), defender.getGameProfile().getName());
        manager.getFactionByStringID(countryB).addMember(offlineMemberB, "OfflineMemberB");
        var scores = world.getScoreboard();
        var oldPlayer = scores.getPlayersTeam(player.getGameProfile().getName());
        var oldDefender = scores.getPlayersTeam(defender.getGameProfile().getName());
        priorPlayerTeam = oldPlayer == null ? null : oldPlayer.getName();
        priorDefenderTeam = oldDefender == null ? null : oldDefender.getName();
        scores.addPlayerToTeam(player.getGameProfile().getName(), scores.addPlayerTeam(countryA));
        scores.addPlayerToTeam(defender.getGameProfile().getName(), scores.addPlayerTeam(countryB));
        setRelation(DiplomacyStatus.ENEMY);
        check(countryA.equals(CombatOwnership.country(world, player.getUUID())), "native country resolves online scoreboard owner without profile-cache dependence");
        check(world.getServer().getPlayerList().getPlayer(offlineLeaderA) == null && countryA.equals(CombatOwnership.country(world, offlineLeaderA)),
                "native UUID membership resolves offline faction leader");
        check(world.getServer().getPlayerList().getPlayer(offlineMemberA) == null && countryA.equals(CombatOwnership.country(world, offlineMemberA)),
                "native UUID membership resolves offline faction member");
        check(CombatOwnership.sameTeam(world, offlineLeaderA, offlineMemberA), "distinct offline owners in the same native country are friendly");
        if (ModList.get().isLoaded("recruitsvillages")) {
            var data = LivingVillagesSavedData.get(world.getServer());
            var capital = new FactionData(UUID.randomUUID(), "Combat AI capital");
            capital.setStringID(countryB); capital.setCenterPos(base.offset(20000, 0, 20000));
            var unit = RecruitUnit.create("recruits:recruit", 1, 0, 0); UUID body = UUID.randomUUID();
            unit.setBody(body, capital.getCenterPos()); capital.getRoster().add(unit); capital.setLeaderRef(unit.getId());
            var colony = new FactionData(UUID.randomUUID(), "Combat AI colony");
            colony.setCapitalId(capital.getId()); colony.setStringID(countryB); colony.setCenterPos(capital.getCenterPos().offset(100, 0, 0));
            UUID savedOwner = UUID.randomUUID(); colony.setOwnerUuid(savedOwner);
            data.putFaction(capital); data.putFaction(colony); NATIVE_VILLAGES.add(capital.getId()); NATIVE_VILLAGES.add(colony.getId());
            check(capital.getOwnerUuid() == null && countryB.equals(CombatOwnership.country(world, capital.getId())), "native AI village UUID resolves its country without a fake player");
            check(countryB.equals(CombatOwnership.country(world, colony.getId())), "native LivingVillages colony UUID resolves the capital country");
            check(countryB.equals(CombatOwnership.country(world, savedOwner)), "native LivingVillages persisted owner UUID resolves the capital country");
            check(countryB.equals(CombatOwnership.country(world, unit.getId())) && countryB.equals(CombatOwnership.country(world, body)),
                    "native AI leader reference and unloaded body UUID resolve saved capital country");
        }
    }
    private static void setRelation(DiplomacyStatus status) {
        var diplomacy = FactionEvents.recruitsDiplomacyManager;
        diplomacy.setRelation(countryA, countryB, status, world, false);
        diplomacy.setRelation(countryB, countryA, status, world, false);
    }
    private static void verifyNativeDiplomacy(BallisticMissileEntity enemy) {
        BallisticMissileEntity sameCountry = CombatContent.MISSILE.get().create(world);
        sameCountry.configure(offlineMemberB, patriot.getUUID(), patriot.muzzle().add(0, 8, 0), enemy.targetPosition());
        check(!patriot.canTarget(sameCountry), "same-country different offline missile owner is protected");
        BallisticMissileEntity unknown = CombatContent.MISSILE.get().create(world);
        unknown.configure(UUID.randomUUID(), patriot.getUUID(), patriot.muzzle().add(0, 8, 0), enemy.targetPosition());
        check(!patriot.canTarget(unknown), "unresolved country ownership cannot become an autonomous enemy");
        setRelation(DiplomacyStatus.NEUTRAL);
        check(!patriot.canTarget(enemy) && !CombatOwnership.hostile(world, defender.getUUID(), launches.get(0)), "native NEUTRAL countries are protected");
        setRelation(DiplomacyStatus.ALLY);
        check(CombatOwnership.sameTeam(world, defender.getUUID(), player.getUUID()) && !patriot.canTarget(enemy), "native ALLY between different countries overrides separate scoreboard teams");
        FactionEvents.recruitsDiplomacyManager.setRelation(countryB, countryA, DiplomacyStatus.ENEMY, world, false);
        check(!patriot.canTarget(enemy), "one-sided retained native ALLY fails safe during diplomacy transition");
        setRelation(DiplomacyStatus.ENEMY);
        check(patriot.canTarget(enemy), "native explicit ENEMY restores hostile ballistic acquisition");
        var treaties = FactionEvents.recruitsTreatyManager;
        check(treaties != null, "native treaty manager is available for ceasefire protection");
        if (treaties != null) {
            try {
                treaties.addTreatyRaw(countryA, countryB, System.currentTimeMillis() + 60000L, world);
                check(treaties.hasTreaty(countryB, countryA) && CombatOwnership.sameTeam(world, player.getUUID(), defender.getUUID())
                        && !patriot.canTarget(enemy) && !CombatOwnership.hostile(world, defender.getUUID(), launches.get(0)),
                        "active native treaty protects ENEMY countries despite retained war relation");
                treaties.removeTreaty(countryA, countryB, world);
                check(patriot.canTarget(enemy) && !CombatOwnership.sameTeam(world, player.getUUID(), defender.getUUID()),
                        "removing native treaty restores explicit ENEMY acquisition");
                // Native hasTreaty compares its saved expiry to wall time without requiring its cleanup tick.
                treaties.addTreatyRaw(countryA, countryB, System.currentTimeMillis() - 1000L, world);
                check(!treaties.hasTreaty(countryA, countryB) && patriot.canTarget(enemy)
                        && !CombatOwnership.sameTeam(world, player.getUUID(), defender.getUUID()),
                        "expired native treaty no longer suppresses explicit ENEMY acquisition");
            } finally { treaties.removeTreaty(countryA, countryB, world); }
        }
    }
    private static void cleanupNativeDiplomacy() {
        if (countryA == null || countryB == null) return;
        var scores = world.getScoreboard();
        for (var entry : List.of(new String[]{player.getGameProfile().getName(), priorPlayerTeam}, new String[]{defender.getGameProfile().getName(), priorDefenderTeam})) {
            var current = scores.getPlayersTeam(entry[0]);
            if (current != null) scores.removePlayerFromTeam(entry[0], current);
            if (entry[1] != null && scores.getPlayerTeam(entry[1]) != null) scores.addPlayerToTeam(entry[0], scores.getPlayerTeam(entry[1]));
        }
        for (String country : List.of(countryA, countryB)) {
            var team = scores.getPlayerTeam(country); if (team != null) scores.removePlayerTeam(team);
            FactionEvents.recruitsFactionManager.removeTeam(country);
            var diplomacy = FactionEvents.recruitsDiplomacyManager;
            diplomacy.diplomacyMap.remove(country);
            for (var relations : diplomacy.diplomacyMap.values()) relations.remove(country);
        }
        if (ModList.get().isLoaded("recruitsvillages")) {
            var data = LivingVillagesSavedData.get(world.getServer());
            for (UUID id : NATIVE_VILLAGES) data.removeFaction(id);
        }
        NATIVE_VILLAGES.clear();
        FactionEvents.recruitsFactionManager.save(world);
        FactionEvents.recruitsDiplomacyManager.save(world);
    }
    private static void verifyInstalledAircraftOwnership() {
        if (ModList.get().isLoaded("simpleplanes")) {
            for (String name : List.of("plane", "large_plane", "cargo_plane", "helicopter")) {
                ResourceLocation id = new ResourceLocation("simpleplanes", name);
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
                check(type != null && type.is(CombatContent.AIR_TARGETS), "installed SimplePlanes aerial type is an ordinary air-defense target: " + id);
                if (type == null) continue;
                Entity craft = type.create(world);
                check(craft != null, "native aircraft entity can be instantiated: " + id);
                if (craft == null) continue;
                craft.setPos(base.getX() + 26, base.getY() + 10, base.getZ() + 4);
                craft.setOnGround(false);
                craft.getPersistentData().putUUID("frontierIndustryVehicleOwner", player.getUUID());
                check(player.getUUID().equals(CombatOwnership.owner(craft)) && s300.canTarget(craft) && s400.canTarget(craft),
                        "unattended hostile claimed aircraft is acquired from UUID ownership: " + id);
                craft.getPersistentData().putUUID("frontierIndustryVehicleOwner", defender.getUUID());
                check(!s300.canTarget(craft) && !s400.canTarget(craft), "same-owner claimed aircraft is excluded: " + id);
                if (name.equals("plane")) {
                    // The current mounted crew is authoritative over an earlier parked-vehicle claim.
                    craft.getPersistentData().putUUID("frontierIndustryVehicleOwner", player.getUUID());
                    WorkerEntity rider = IndustryContent.WORKER.get().create(world);
                    rider.getPersistentData().putString("frontierIndustryOwner", defender.getUUID().toString());
                    boolean mounted = rider.startRiding(craft, true);
                    check(mounted && defender.getUUID().equals(CombatOwnership.owner(craft)) && !s300.canTarget(craft),
                            "actual friendly mounted crew takes precedence over stale hostile vehicle claim");
                    rider.stopRiding(); rider.discard();
                    craft.getPersistentData().putString("frontierIndustryVehicleOwner", player.getUUID().toString());
                    check(player.getUUID().equals(CombatOwnership.owner(craft)), "legacy string vehicle claim remains recognized");
                }
                craft.discard();
            }
        } else LOG.info("COMBAT_SMOKE SKIP optional SimplePlanes integration (mod absent)");
        for (String value : List.of("superbwarfare:drone", "uncomplicatedfpv:fpv_drone")) {
            ResourceLocation id = new ResourceLocation(value);
            if (!ModList.get().isLoaded(id.getNamespace())) continue;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            Entity drone = type == null ? null : type.create(world);
            check(drone != null, "installed native drone exists for ownership precedence: " + id);
            if (drone == null) continue;
            drone.getPersistentData().putUUID("frontierIndustryVehicleOwner", player.getUUID());
            try {
                drone.getClass().getMethod("setLastDriverUUID", String.class).invoke(drone, defender.getUUID().toString());
                check(defender.getUUID().equals(CombatOwnership.owner(drone)), "native drone driver takes precedence over vehicle claim: " + id);
            } catch (ReflectiveOperationException failure) {
                check(false, "native drone ownership API available: " + id + " (" + failure.getClass().getSimpleName() + ")");
            }
            drone.discard();
        }
    }
    private static void check(boolean passed, String label) { String line = (passed ? "PASS " : "FAIL ") + label; RESULTS.add(line); LOG.info("COMBAT_SMOKE {}", line); }
    public static final class SpawnCancellation {
        private int seen;
        @SubscribeEvent public void join(EntityJoinLevelEvent event) {
            if (event.getLevel() == world && event.getEntity() instanceof BallisticMissileEntity && ++seen == 2) event.setCanceled(true);
        }
    }
}
