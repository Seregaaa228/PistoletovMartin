package dev.frontier.industry.combat;

import com.mojang.logging.LogUtils;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager.DiplomacyStatus;
import dev.frontier.industry.IndustryContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Actual DSM ordinary-tick acceptance in an explicitly disposable world; never shipped in production. */
public final class NativeCombatSmoke {
    private static final String GAME = "dev.sbw.deepstrike.game.";
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();
    private static final List<String> TELEMETRY = new ArrayList<>();
    private static final List<Entity> FIXTURES = new ArrayList<>();
    private static final List<BlockEntity> FP5 = new ArrayList<>();
    private static final List<Long> FP5_SPAWNS = new ArrayList<>();
    private static final Observer OBSERVER = new Observer();
    private static ServerPlayer player;
    private static ServerLevel level;
    private static BlockPos base, fpTarget, pkTarget;
    private static BlockEntity peacekeeper;
    private static Entity patriot, dome, radar, friendly, enemy, offlinePatriot, offlineRadar, offlineTarget;
    private static String countryA, countryB, oldTeam;
    private static UUID enemyOwner;
    private static long started;
    private static boolean complete, peacekeeperOrdered, incomingStarted, warStarted, allyAttempted;
    private static int fpExplosions, pkExplosions, pkSpawns, interceptors, protectedAmmo, offlineInterceptors, blockedAlliedInterceptors;
    private static boolean observedIntercept;
    public static void start(ServerPlayer owner) {
        if (System.getProperty("frontier.industrySmokeTest") == null) throw new IllegalStateException("Disposable-test world only");
        player = owner; level = owner.serverLevel(); started = level.getGameTime(); complete = false;
        RESULTS.clear(); TELEMETRY.clear(); FIXTURES.clear(); FP5.clear(); FP5_SPAWNS.clear();
        peacekeeperOrdered = incomingStarted = warStarted = observedIntercept = allyAttempted = false; fpExplosions = pkExplosions = pkSpawns = interceptors = offlineInterceptors = blockedAlliedInterceptors = 0;
        MinecraftForge.EVENT_BUS.unregister(OBSERVER); MinecraftForge.EVENT_BUS.register(OBSERVER);
        try {
            check("native_deepstrike_present", ReadyWeapons.available());
            base = new BlockPos(owner.blockPosition().getX() + 160, Math.min(190, owner.blockPosition().getY() + 45), owner.blockPosition().getZ());
            for (int x = -48; x <= 48; x++) for (int z = -42; z <= 42; z++) level.setBlock(base.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 3);
            fpTarget = base.offset(500, 0, 0); pkTarget = base.offset(500, 0, 180); target(fpTarget); target(pkTarget);
            diplomacy();
            for (int z : new int[]{-20, 0, 20}) FP5.add(launcher("fp5_launcher", "fp5", base.offset(6, 0, z)));
            peacekeeper = launcher("peacekeeper_platform", "peacekeeper", base.offset(-18, 0, -8));
            // Native platform consumes three actual lava buckets through the same PDA reload action.
            for (int i = 0; i < 3; i++) give(new ItemStack(Items.LAVA_BUCKET));
            teleport(peacekeeper.getBlockPos().offset(3, 0, 0)); note(ReadyWeapons.action(player, "pvo_reload", id(peacekeeper), 0));
            check("native_peacekeeper_platform_refueled", Boolean.TRUE.equals(ReadyWeapons.call(peacekeeper, "platformFueled")));
            radar = vehicle("an_mpq_65", base.offset(-30, 0, 20));
            patriot = vehicle("mim_104_patriot_truck", base.offset(-30, 0, -18));
            dome = vehicle("iron_dome", base.offset(28, 0, 20));
            assign(radar); assign(patriot); assign(dome);
            give(item("pac_2", 4)); give(item("pac_3", 4)); give(item("tamir", 8)); give(item("air_defense_terminal", 1));
            teleport(patriot.blockPosition().offset(3, 0, 0)); note(ReadyWeapons.action(player, "pvo_reload", patriot.getUUID().toString(), 0));
            teleport(dome.blockPosition().offset(3, 0, 0)); note(ReadyWeapons.action(player, "pvo_reload", dome.getUUID().toString(), 0));
            check("native_patriot_real_cabin_crew", ReadyWeapons.crew(patriot) != null && ReadyWeapons.crew(patriot).getVehicle() == patriot);
            check("native_stationary_dome_real_nearby_crew", ReadyWeapons.crew(dome) != null && !ReadyWeapons.crew(dome).isPassenger());
            check("native_radar_real_operator_and_real_terminal", ReadyWeapons.radarReady(patriot));
            LivingEntity blockCrew = ReadyWeapons.crew(FP5.get(0));
            ReadyWeapons.release(FP5.get(0));
            check("native_release_clears_all_station_markers", !blockCrew.getPersistentData().contains(ReadyWeapons.ANCHOR)
                    && !blockCrew.getPersistentData().contains(ReadyWeapons.DIMENSION) && !blockCrew.getPersistentData().contains(CombatOwnership.STATION));
            // Recreate the exact old-anchor regression and ensure a new native vehicle assignment clears it.
            LivingEntity previousPatriot = ReadyWeapons.crew(patriot); ReadyWeapons.release(patriot);
            blockCrew.getPersistentData().putLong(ReadyWeapons.ANCHOR, FP5.get(0).getBlockPos().asLong());
            blockCrew.getPersistentData().putString(ReadyWeapons.DIMENSION, level.dimension().location().toString());
            blockCrew.setPos(patriot.position().add(3, 0, 0)); teleport(patriot.blockPosition().offset(3, 0, 0));
            note(ReadyWeapons.action(player, "pvo_operator", patriot.getUUID().toString(), 0));
            check("native_reassignment_block_to_patriot_clears_anchor", ReadyWeapons.crew(patriot) == blockCrew && !blockCrew.getPersistentData().contains(ReadyWeapons.ANCHOR) && ReadyWeapons.validStation(blockCrew));
            previousPatriot.setPos(Vec3.atBottomCenterOf(FP5.get(0).getBlockPos().offset(3, 0, 2))); teleport(FP5.get(0).getBlockPos().offset(3, 0, 0));
            note(ReadyWeapons.action(player, "pvo_operator", id(FP5.get(0)), 0));
            note(ReadyWeapons.action(player, "pvo_active", patriot.getUUID().toString(), 0));
            note(ReadyWeapons.action(player, "pvo_active", dome.getUUID().toString(), 0));
            setupOfflineDefense();
            teleport(base);
            check("native_pda_lists_real_units", ReadyWeapons.snapshot(player).getAsJsonArray("systems").size() >= 7);
            int before = loaded(FP5.get(0)); String rejected = ReadyWeapons.action(player, "pvo_launch", id(FP5.get(0)) + "|500,150,0", 4);
            check("native_pda_invalid_salvo_spends_nothing", loaded(FP5.get(0)) == before && rejected.contains("1 до 3"));
            for (BlockEntity block : FP5) check("native_launcher_live_crew_" + block.getBlockPos().getZ(), ReadyWeapons.crew(block) != null);
            String launch = ReadyWeapons.action(player, "pvo_launch", id(FP5.get(0)) + "|" + coords(fpTarget), 3); note(launch);
            check("native_three_launcher_order_accepted", player.getPersistentData().hasUUID(ReadyWeapons.PENDING));
            CompoundTag saved = new CompoundTag(); patriot.saveWithoutId(saved);
            Entity restored = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("deepstrike:mim_104_patriot_truck")).create(level); restored.load(saved);
            check("native_vehicle_owner_crew_nbt_roundtrip", restored.getPersistentData().hasUUID(ReadyWeapons.CREW)
                    && player.getUUID().equals(ReadyWeapons.nativeOwner(restored))); restored.setUUID(UUID.randomUUID()); restored.discard();
        } catch (Throwable problem) { check("native_setup_exception_" + problem.getClass().getSimpleName(), false); note(problem.toString()); finish(); }
    }
    public static void tick() {
        if (complete || level == null) return; long elapsed = level.getGameTime() - started;
        try {
            if (elapsed % 100 == 0) note("tick=" + elapsed + " FP5=" + FP5_SPAWNS.size() + " PK=" + pkSpawns + " impacts=" + fpExplosions + "/" + pkExplosions + " interceptors=" + interceptors + "/offline=" + offlineInterceptors + " ready=" + (patriot == null ? -1 : ready(patriot)) + "/" + (offlinePatriot == null ? -1 : ready(offlinePatriot)));
            if (!peacekeeperOrdered && elapsed >= 180 && !player.getPersistentData().hasUUID(ReadyWeapons.PENDING)) {
                teleport(base); note(ReadyWeapons.action(player, "pvo_launch", id(peacekeeper) + "|" + coords(pkTarget), 1));
                peacekeeperOrdered = true; check("native_ballistic_order_accepted", player.getPersistentData().hasUUID(ReadyWeapons.PENDING));
            }
            if (!incomingStarted && elapsed >= 950) {
                incomingStarted = true;
                check("native_patriot_deployed", Boolean.TRUE.equals(ReadyWeapons.call(patriot, "isDeployed")));
                check("native_patriot_radar_connected", Boolean.TRUE.equals(ReadyWeapons.call(patriot, "radarConnected")));
                check("native_real_pac_rounds_loaded", ready(patriot) > 0);
                ServerPlayer controller = NativeCombatEvents.controller(level, offlinePatriot);
                check("native_offline_controller_exact_owner", controller instanceof net.minecraftforge.common.util.FakePlayer && controller.getUUID().equals(enemyOwner));
                check("native_offline_controller_not_world_or_player_list", level.getServer().getPlayerList().getPlayer(enemyOwner) == null && level.getEntity(enemyOwner) == null);
                check("native_offline_controller_has_no_inventory", controller != null && controller.getInventory().isEmpty());
                check("native_offline_controller_rejected_by_public_pda", controller != null && ReadyWeapons.action(controller, "pvo_reload", offlinePatriot.getUUID().toString(), 0).contains("не может"));
                UUID savedOwner = offlinePatriot.getPersistentData().getUUID("DeepStrikeOwner");
                offlinePatriot.getPersistentData().putUUID("DeepStrikeOwner", UUID.randomUUID());
                check("native_unknown_owner_has_no_controller", NativeCombatEvents.controller(level, offlinePatriot) == null);
                offlinePatriot.getPersistentData().putUUID("DeepStrikeOwner", savedOwner);
                note("patriot inventory slots=" + ((net.minecraftforge.items.IItemHandler) ReadyWeapons.call(patriot, "getInventory")).getSlots() + " reserves=" + ReadyWeapons.call(patriot, "pac2Count") + "/" + ReadyWeapons.call(patriot, "pac3Count") + " reloadTicks=" + ReadyWeapons.call(patriot, "getReloadTicks"));
                protectedAmmo = ready(patriot); relation(DiplomacyStatus.ALLY); friendly = incoming("FP5", enemyOwner, base.offset(100, 60, 80));
                check("native_allied_cruise_not_enemy", !NativeCombatEvents.allowed(patriot, friendly));
                Entity ballistic = incoming("PEACEKEEPER", player.getUUID(), base.offset(110, 65, 100));
                check("native_own_ballistic_protected", !NativeCombatEvents.allowed(patriot, ballistic)); ballistic.discard();
            }
            if (!allyAttempted && incomingStarted && elapsed >= 975) {
                allyAttempted = true; int ammunition = ready(patriot);
                boolean fired = Boolean.TRUE.equals(ReadyWeapons.staticCall("dev.sbw.deepstrike.network.PatriotDefenseNetwork", "fireFromCommand",
                        new Class<?>[]{ServerPlayer.class, patriot.getClass(), UUID.class, int.class}, player, patriot, friendly.getUUID(), 1));
                check("native_terminal_allied_spawn_canceled_without_ammo_spend", !fired && ammunition == ready(patriot) && blockedAlliedInterceptors > 0);
            }
            if (!warStarted && incomingStarted && elapsed >= 990) {
                warStarted = true; check("native_ally_real_ticks_no_ammo_consumed", ready(patriot) == protectedAmmo && interceptors == 0);
                if (friendly != null) friendly.discard(); relation(DiplomacyStatus.ENEMY);
                enemy = incoming("PEACEKEEPER", enemyOwner, base.offset(100, 60, 60));
                offlineTarget = incoming("FP5", player.getUUID(), base.offset(120, 55, -100));
                check("native_offline_defense_has_real_enemy", NativeCombatEvents.allowed(offlinePatriot, offlineTarget));
                check("native_patriot_accepts_hostile_ballistic", NativeCombatEvents.allowed(patriot, enemy));
                check("native_iron_dome_rejects_ballistic", !NativeCombatEvents.allowed(dome, enemy));
                LivingEntity operator = ReadyWeapons.crew(patriot); operator.getPersistentData().remove(CombatOwnership.STATION);
                check("native_missing_operator_stops_acquisition", !NativeCombatEvents.allowed(patriot, enemy));
                operator.getPersistentData().putString(CombatOwnership.STATION, patriot.getUUID().toString());
                check("native_operator_restores_acquisition", NativeCombatEvents.allowed(patriot, enemy));
            }
            if (enemy != null && Boolean.TRUE.equals(ReadyWeapons.call(enemy, "isIntercepted"))) observedIntercept = true;
            if (elapsed >= 450 && fpExplosions > 0 && pkExplosions > 0 && interceptors > 0 && offlineInterceptors > 0 && observedIntercept || elapsed >= 2600) finish();
        } catch (Throwable problem) { check("native_tick_exception_" + problem.getClass().getSimpleName(), false); note(problem.toString()); finish(); }
    }
    public static boolean done() { return complete; }
    public static Map<String, Boolean> results() { return Map.copyOf(RESULTS); }
    public static List<String> telemetry() { return List.copyOf(TELEMETRY); }
    private static BlockEntity launcher(String blockId, String missile, BlockPos pos) throws ReflectiveOperationException {
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("deepstrike:" + blockId)); level.setBlock(pos, block.defaultBlockState(), 3);
        BlockEntity launcher = level.getBlockEntity(pos); if (!ReadyWeapons.nativeLauncher(launcher)) throw new IllegalStateException("Missing native launcher " + blockId);
        ReadyWeapons.invoke(launcher, "placed", new Class<?>[]{LivingEntity.class, ItemStack.class}, player, item(blockId, 1));
        teleport(pos.offset(3, 0, 0)); worker(pos.offset(5, 0, 1)); note(ReadyWeapons.action(player, "pvo_operator", id(launcher), 0));
        give(item(missile, 1)); note(ReadyWeapons.action(player, "pvo_reload", id(launcher), 0));
        check("native_stock_loaded_" + blockId + "_" + pos.getZ(), loaded(launcher) > 0); return launcher;
    }
    private static Entity vehicle(String path, BlockPos pos) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("deepstrike:" + path)); Entity entity = type.create(level);
        entity.setPos(Vec3.atBottomCenterOf(pos)); entity.getPersistentData().putUUID("DeepStrikeOwner", player.getUUID()); level.addFreshEntity(entity); FIXTURES.add(entity); return entity;
    }
    private static void assign(Entity station) throws ReflectiveOperationException {
        teleport(station.blockPosition().offset(3, 0, 0)); worker(station.blockPosition().offset(3, 0, 2));
        note(ReadyWeapons.action(player, "pvo_operator", station.getUUID().toString(), 0)); check("native_operator_" + station.getType().toShortString(), ReadyWeapons.crew(station) != null);
    }
    private static void worker(BlockPos pos) {
        var worker = IndustryContent.WORKER.get().create(level); worker.setPos(Vec3.atBottomCenterOf(pos));
        worker.getPersistentData().putUUID("frontierIndustryOwner", player.getUUID()); level.addFreshEntity(worker); FIXTURES.add(worker);
    }
    private static Entity incoming(String kind, UUID owner, BlockPos pos) throws ReflectiveOperationException {
        Class<?> kinds = Class.forName(GAME + "MissileKind"); Object value = kinds.getField(kind).get(null);
        Entity entity = (Entity) Class.forName(GAME + "GuidedMissileEntity").getConstructor(net.minecraft.world.level.Level.class, kinds).newInstance(level, value);
        entity.setPos(Vec3.atBottomCenterOf(pos)); ReadyWeapons.invoke(entity, "setOwnerId", new Class<?>[]{UUID.class}, owner);
        Object point = Class.forName(GAME + "RoutePoint").getConstructor(double.class, double.class, double.class, double.class)
                .newInstance((double) base.getX() + 900, (double) base.getY() + 70, (double) base.getZ() + 200, kind.equals("PEACEKEEPER") ? 2400D : 850D);
        ReadyWeapons.invoke(entity, "setMission", new Class<?>[]{List.class, boolean.class}, List.of(point), kind.equals("PEACEKEEPER"));
        ReadyWeapons.invoke(entity, "setLaunchVector", new Class<?>[]{Vec3.class, int.class}, new Vec3(1, 0.1, 0).normalize(), 0);
        ReadyWeapons.invoke(entity, "setMissionId", new Class<?>[]{UUID.class}, UUID.randomUUID());
        level.addFreshEntity(entity); FIXTURES.add(entity); return entity;
    }
    private static void setupOfflineDefense() throws ReflectiveOperationException {
        offlineRadar = vehicle("an_mpq_65", base.offset(34, 0, -38));
        offlinePatriot = vehicle("mim_104_patriot_truck", base.offset(34, 0, -20));
        for (Entity station : new Entity[]{offlineRadar, offlinePatriot}) {
            station.getPersistentData().putUUID("DeepStrikeOwner", enemyOwner);
            var operator = IndustryContent.WORKER.get().create(level); operator.setPos(station.position().add(4, 0, 0));
            operator.getPersistentData().putUUID("frontierIndustryOwner", enemyOwner); operator.getPersistentData().putString(CombatOwnership.STATION, station.getUUID().toString());
            level.addFreshEntity(operator); FIXTURES.add(operator); station.getPersistentData().putUUID(ReadyWeapons.CREW, operator.getUUID());
            if (station == offlinePatriot) check("native_offline_actual_cabin_operator", operator.startRiding(station, true));
        }
        // The fixture supplies a physical terminal and rounds; the internal controller never receives them.
        ItemStack terminal = item("air_defense_terminal", 1), saved = player.getOffhandItem(); player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, terminal);
        try { offlineRadar.interact(player, net.minecraft.world.InteractionHand.OFF_HAND); }
        finally { terminal = player.getOffhandItem(); player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, saved); }
        var inventory = (net.minecraftforge.items.IItemHandler) ReadyWeapons.call(offlinePatriot, "getInventory");
        for (int slot = 0; slot < inventory.getSlots() && !terminal.isEmpty(); slot++) terminal = inventory.insertItem(slot, terminal, false);
        ItemStack ammunition = item("pac_3", 4);
        for (int slot = 0; slot < inventory.getSlots() && !ammunition.isEmpty(); slot++) ammunition = inventory.insertItem(slot, ammunition, false);
        note("offline initial slots=" + inventory.getSlots() + " terminalLeft=" + terminal.getCount() + " roundsLeft=" + ammunition.getCount());
        check("native_offline_uses_real_rounds_and_terminal", terminal.isEmpty() && ammunition.isEmpty());
        if (!terminal.isEmpty()) give(terminal); if (!ammunition.isEmpty()) give(ammunition);
        ReadyWeapons.call(offlinePatriot, "refreshDefenseInventory"); ReadyWeapons.call(offlinePatriot, "startManualReload");
        ReadyWeapons.call(offlinePatriot, "toggleDeployment"); offlinePatriot.getPersistentData().putBoolean(ReadyWeapons.DUTY, true);
    }
    private static int ready(Entity station) throws ReflectiveOperationException { return ((Number) ReadyWeapons.call(station, "readyPac2")).intValue() + ((Number) ReadyWeapons.call(station, "readyPac3")).intValue(); }
    private static int loaded(BlockEntity launcher) throws ReflectiveOperationException { return ((Number) ReadyWeapons.call(launcher, "loadCount")).intValue(); }
    private static String id(BlockEntity block) { return "block:" + coords(block.getBlockPos()); }
    private static String coords(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    private static ItemStack item(String path, int amount) { return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("deepstrike:" + path)), amount); }
    private static void give(ItemStack stack) { if (!player.getInventory().add(stack)) player.drop(stack, false); }
    private static void teleport(BlockPos pos) { player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0); }
    private static void target(BlockPos center) {
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -4; y <= 0; y++) level.setBlock(center.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
    }
    private static void diplomacy() {
        String suffix = UUID.randomUUID().toString().substring(0, 6); countryA = "dsma_" + suffix; countryB = "dsmb_" + suffix; enemyOwner = UUID.randomUUID();
        var factions = FactionEvents.recruitsFactionManager;
        factions.addTeam(countryA, "DSM test A", UUID.randomUUID(), "DSM A", new CompoundTag(), (byte) 0, ChatFormatting.BLUE);
        factions.addTeam(countryB, "DSM test B", enemyOwner, "DSM B", new CompoundTag(), (byte) 1, ChatFormatting.RED);
        factions.getFactionByStringID(countryA).addMember(player.getUUID(), player.getGameProfile().getName());
        var score = level.getScoreboard(); var prior = score.getPlayersTeam(player.getGameProfile().getName()); oldTeam = prior == null ? null : prior.getName();
        score.addPlayerToTeam(player.getGameProfile().getName(), score.addPlayerTeam(countryA)); relation(DiplomacyStatus.ALLY);
    }
    private static void relation(DiplomacyStatus value) {
        FactionEvents.recruitsDiplomacyManager.setRelation(countryA, countryB, value, level, false);
        FactionEvents.recruitsDiplomacyManager.setRelation(countryB, countryA, value, level, false);
    }
    private static void finish() {
        if (complete) return; complete = true;
        check("native_three_physical_cruise_missiles", FP5_SPAWNS.size() == 3);
        check("native_salvo_same_server_tick", FP5_SPAWNS.size() == 3 && FP5_SPAWNS.stream().distinct().count() == 1);
        check("native_ballistic_peacekeeper_spawned", pkSpawns >= 1);
        check("native_cruise_actual_impact_explosion", fpExplosions > 0);
        check("native_ballistic_actual_impact_explosion", pkExplosions > 0);
        check("native_patriot_actual_projectile_fired", interceptors > 0);
        check("native_patriot_actual_ballistic_interception", observedIntercept);
        check("native_offline_controller_actual_native_fire", offlineInterceptors > 0);
        if (fpTarget != null) check("native_cruise_actual_terrain_damage", !level.getBlockState(fpTarget).is(Blocks.STONE));
        if (pkTarget != null) check("native_ballistic_actual_terrain_damage", !level.getBlockState(pkTarget).is(Blocks.STONE));
        for (Entity fixture : FIXTURES) fixture.discard();
        MinecraftForge.EVENT_BUS.unregister(OBSERVER);
        if (level != null && oldTeam != null && level.getScoreboard().getPlayerTeam(oldTeam) != null)
            level.getScoreboard().addPlayerToTeam(player.getGameProfile().getName(), level.getScoreboard().getPlayerTeam(oldTeam));
        note("NATIVE_COMBAT_COMPLETE " + RESULTS);
    }
    private static void check(String name, boolean value) { RESULTS.put(name, value); LogUtils.getLogger().info("NATIVE_COMBAT_SMOKE {} {}", value ? "PASS" : "FAIL", name); }
    private static void note(String value) { TELEMETRY.add(value); LogUtils.getLogger().info("NATIVE_COMBAT_SMOKE {}", value); }
    public static final class Observer {
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void join(EntityJoinLevelEvent event) {
            if (complete || event.getLevel() != level) return; Entity entity = event.getEntity();
            if (event.isCanceled()) {
                try {
                    if (friendly != null && entity.getClass().getName().equals(GAME + "PatriotInterceptorEntity")
                            && friendly.getUUID().equals(ReadyWeapons.call(entity, "targetId"))) blockedAlliedInterceptors++;
                } catch (ReflectiveOperationException incompatible) { note(incompatible.toString()); }
                return;
            }
            try {
                if (entity.getClass().getName().equals(GAME + "GuidedMissileEntity") && player.getUUID().equals(ReadyWeapons.call(entity, "ownerId"))) {
                    var field = entity.getClass().getDeclaredField("launchMaster"); field.setAccessible(true);
                    if (!(field.get(entity) instanceof BlockPos)) return;
                    String kind = ReadyWeapons.call(entity, "kind").toString();
                    if (kind.equals("FP5")) FP5_SPAWNS.add(level.getGameTime()); else if (kind.equals("PEACEKEEPER")) pkSpawns++;
                    FIXTURES.add(entity); note("native launch " + kind + " tick=" + level.getGameTime());
                } else if (entity.getClass().getName().equals(GAME + "PatriotInterceptorEntity")) {
                    Object source = ReadyWeapons.call(entity, "defenseSource");
                    if (patriot != null && patriot.getUUID().equals(source)) interceptors++;
                    if (offlinePatriot != null && offlinePatriot.getUUID().equals(source)) offlineInterceptors++;
                    FIXTURES.add(entity);
                }
            } catch (ReflectiveOperationException incompatible) { note(incompatible.toString()); }
        }
        @SubscribeEvent
        public void explosion(ExplosionEvent.Start event) {
            if (complete || event.getLevel() != level || !event.getExplosion().getClass().getName().equals(GAME + "MissileExplosion")) return;
            Entity source = event.getExplosion().getDirectSourceEntity();
            try {
                if (source != null && player.getUUID().equals(ReadyWeapons.call(source, "ownerId"))) {
                    String kind = ReadyWeapons.call(source, "kind").toString(); if (kind.equals("FP5")) fpExplosions++; else if (kind.equals("PEACEKEEPER")) pkExplosions++;
                    note("native impact " + kind + " at=" + event.getExplosion().getPosition());
                }
            } catch (ReflectiveOperationException incompatible) { note(incompatible.toString()); }
        }
    }
}
