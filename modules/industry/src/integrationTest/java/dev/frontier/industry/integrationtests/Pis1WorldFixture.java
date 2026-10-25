package dev.frontier.industry.integrationtests;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.industry.IndustryCatalog;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.IndustryMenus;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.WorkerEntity;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Order;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import dev.frontier.industry.engine.IndustryRules;
import dev.frontier.industry.engine.StockTransactions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Destructive arena fixture: installed only in a matching, explicitly fresh test profile. */
public final class Pis1WorldFixture {
    private static final int WARMUP = 1200, SAMPLES = 2400;
    private static final List<String> STAGES = stages();
    private static final Map<String, Boolean> CHECKS = new LinkedHashMap<>();
    private static final List<UUID> WORKERS = new ArrayList<>();
    private static final Set<Long> HELD_CHUNKS = new LinkedHashSet<>();
    private static final JsonObject REPORT = new JsonObject();
    private static final JsonArray BENCHMARKS = new JsonArray();
    private static final JsonArray JADE_WORLD_SCREENS = new JsonArray();
    private static final List<Long> TICK_NANOS = new ArrayList<>();
    private static boolean initialized, begun, done, sawPaid, saw1799, benchmarkOnly, repairImageRequested;
    private static int phase, phaseTick, elapsed, uiIndex, lastRepairProgress, repairAdvances;
    private static int benchmarkPreviousProgress, benchmarkMaxProgress, benchmarkWorkingTicks, benchmarkCompletedCycles;
    private static int benchmarkPauseEpoch, benchmarkPauseResets, benchmarkDiscardedTicks;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static IndustryData data;
    private static Site station, distribution, factory;
    private static BlockPos damagedGrid;
    private static BlockState gridState;
    private static List<BlockPos> factoryDamage;
    private static String recipeId;
    private static Order observedOrder;
    private static int pausedProgress;

    private Pis1WorldFixture() {}
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(Pis1WorldFixture::tick);
    }
    private static List<String> stages() {
        List<String> result = new ArrayList<>();
        for (String scale : List.of("2", "3", "auto"))
            for (String kind : List.of("factory", "factory-search", "factory-order", "factory-storage", "factory-management",
                "country", "army", "production", "repair", "guide")) result.add(kind + "@" + scale);
        return List.copyOf(result);
    }
    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done || !Boolean.getBoolean("frontier.pis1Smoke")
            || !Boolean.getBoolean("frontier.pis1ClientReady") || event.getServer().isDedicatedServer()) return;
        MinecraftServer server = event.getServer();
        try {
            if (!begun) {
                if (server.getPlayerList().getPlayers().isEmpty()) return;
                if (!IntegrationChecksMod.authorized(server)) throw new IllegalStateException("World fixture refused a non-isolated profile");
                if (Boolean.getBoolean("frontier.pis1Restart")) {
                    begun = true;
                    benchmarkOnly = Boolean.getBoolean("frontier.pis1BenchmarkOnly");
                    validateRestart(server);
                    if (benchmarkOnly) finish(server);
                    else { phase = 10; phaseTick = 0; }
                    return;
                }
                setup(server);
                begun = true;
                return;
            }
            int timeout = Boolean.getBoolean("frontier.pis1Restart") ? 6000 : 15000 + benchmarkDiscardedTicks;
            if (++elapsed > timeout) throw new IllegalStateException("World fixture exceeded " + timeout + " ticks");
            String failure = System.getProperty("frontier.pis1UiFailure", "");
            if (!failure.isBlank()) throw new IllegalStateException(failure);
            for (Order order : factory.orders) if (order.reserved && order.progress > 0) sawPaid = true;
            phaseTick++;
            switch (phase) {
                case 0 -> ui(server);
                case 1 -> waitForPaidOrder(server);
                case 2 -> verifyGridCut(server);
                case 3 -> verifyFactoryDamage(server);
                case 4 -> repair(server);
                case 5, 6 -> benchmark(server);
                case 7 -> worldVisuals(server);
                case 8 -> catalogReload(server);
                case 9 -> { if (CatalogRestartFixture.prepare(server, player, factory)) finish(server); }
                case 10 -> { if (CatalogRestartFixture.tickRestart(server, player, factory)) finish(server); }
                case 11 -> jadePowered(server);
                case 12 -> jadeGridCut(server);
                case 13 -> jadeAfterRepair(server);
                default -> throw new IllegalStateException("Unknown fixture phase " + phase);
            }
        } catch (Throwable error) {
            CHECKS.put("fixture completed without exception", false);
            REPORT.addProperty("error", error.toString());
            error.printStackTrace();
            if (!Boolean.getBoolean("frontier.pis1BenchmarkOnly")) {
                CatalogReloadFixture.cleanupOnFailure(server);
                CatalogRestartFixture.cleanupOnFailure(server);
            }
            finish(server);
        }
    }
    private static void setup(MinecraftServer server) {
        player = server.getPlayerList().getPlayers().get(0);
        level = player.serverLevel();
        data = IndustryData.get(server);
        if (!data.sites.isEmpty() || !data.workers.isEmpty()) throw new IllegalStateException("Fresh fixture requires empty Industry ledger");
        benchmarkOnly = Boolean.getBoolean("frontier.pis1BenchmarkOnly");
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(6000);
        level.setWeatherParameters(0, 0, false, false);
        REPORT.addProperty("runId", System.getProperty("frontier.pis1RunId"));
        REPORT.addProperty("startedAt", Instant.now().toString());
        REPORT.addProperty("benchmarkOnly", benchmarkOnly);
        REPORT.addProperty("worldSeed", level.getSeed());
        REPORT.addProperty("java", System.getProperty("java.version"));
        REPORT.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        REPORT.addProperty("maxJvmBytes", Runtime.getRuntime().maxMemory());
        REPORT.addProperty("viewDistance", server.getPlayerList().getViewDistance());
        REPORT.addProperty("mobSpawning", false);
        REPORT.addProperty("fixedDayTime", 6000);
        station = site(SiteType.POWER_STATION, new BlockPos(4032, 180, 4096));
        distribution = site(SiteType.SUBSTATION, new BlockPos(4072, 180, 4096));
        factory = site(SiteType.EQUIPMENT_FACTORY, new BlockPos(4112, 180, 4096));
        // Test-only apron: all approach/observation positions have a solid floor
        // outside the immutable industrial footprint as well as inside it.
        int front = Blueprint.bounds(factory.type, 3).minZ();
        for (int x = -7; x <= 7; x++) for (int z = front - 4; z < front; z++) {
            BlockPos pos = factory.origin.offset(x, 0, z);
            if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Fixture apron is occupied at " + pos);
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        distribution.upstream = station.id; factory.upstream = distribution.id;
        station.inventory.setStackInSlot(0, new ItemStack(Items.COAL, 64));
        recipeId = "minecraft:iron_sword";
        IndustryCatalog.Recipe recipe = IndustryCatalog.resolve(recipeId, SiteType.EQUIPMENT_FACTORY);
        if (recipe == null) throw new IllegalStateException("Fixture recipe is absent: " + recipeId);
        StockTransactions.refundInputs(factory.inventory, List.of(new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.REDSTONE, 64), new ItemStack(Items.GUNPOWDER, 64)));
        System.setProperty("frontier.pis1RecipeId", recipeId);
        BlockPos entrance = Blueprint.entrance(factory.type, factory.origin, factory.blueprintVersion);
        player.teleportTo(level, entrance.getX() + .5, entrance.getY(), entrance.getZ() - 1.5, 0, 0);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(IndustryContent.preferredTablet()));
        player.getInventory().setItem(1, new ItemStack(Items.EMERALD, 64));
        player.getInventory().selected = 0;
        if (!benchmarkOnly) {
            worker("production", factory.origin.offset(0, 1, -4));
            worker("factory_repair", entrance.offset(5, 0, 0));
            worker("factory_repair", entrance.offset(-5, 0, 0));
            phase = 0;
            requestStage(STAGES.get(0));
        } else beginBenchmark();
        data.setDirty();
        REPORT.addProperty("factoryId", factory.id.toString());
        REPORT.addProperty("stationId", station.id.toString());
        REPORT.addProperty("distributionId", distribution.id.toString());
        System.out.println("PIS1_WORLD_SETUP " + factory.id);
    }
    private static Site site(SiteType type, BlockPos origin) {
        var bounds = Blueprint.bounds(type, 3);
        holdSiteChunks(type, origin, 3);
        for (int x = bounds.minX(); x <= bounds.maxX(); x++)
            for (int y = bounds.minY(); y <= bounds.maxY(); y++)
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++)
                    if (!level.getBlockState(origin.offset(x, y, z)).isAir()) throw new IllegalStateException("Fixture arena is occupied at " + origin.offset(x, y, z));
        Site site = new Site(data, UUID.randomUUID(), player.getUUID(), type, level.dimension().location(), origin);
        site.blueprintVersion = 3;
        site.team = player.getTeam() == null ? "" : player.getTeam().getName();
        Blueprint.blocks(type, origin, 3).forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
        data.sites.put(site.id, site);
        site.loaded = true;
        IndustryEngine.poll(level, site);
        return site;
    }
    private static void holdSiteChunks(SiteType type, BlockPos origin, int blueprintVersion) {
        if (!IntegrationChecksMod.authorized(level.getServer()))
            throw new IllegalStateException("Fixture chunk tickets require the matching isolated profile");
        var bounds = Blueprint.bounds(type, blueprintVersion);
        for (int cx = (origin.getX() + bounds.minX()) >> 4; cx <= (origin.getX() + bounds.maxX()) >> 4; cx++)
            for (int cz = (origin.getZ() + bounds.minZ()) >> 4; cz <= (origin.getZ() + bounds.maxZ()) >> 4; cz++) {
                // Persist these fixture-only tickets through the real JVM restart.
                // A one-off getChunk during construction does not retain a site.
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
                HELD_CHUNKS.add(ChunkPos.asLong(cx, cz));
            }
        REPORT.addProperty("heldFixtureChunks", HELD_CHUNKS.size());
    }
    private static void worker(String role, BlockPos pos) {
        WorkerEntity entity = IndustryContent.WORKER.get().create(level);
        if (entity == null) throw new IllegalStateException("Cannot construct a worker");
        Worker worker = new Worker(entity.getUUID(), player.getUUID(), role, level.dimension().location());
        worker.site = factory.id;
        IndustryEngine.initialize(entity, worker);
        entity.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        data.workers.put(worker.id, worker);
        if (!level.addFreshEntity(entity)) throw new IllegalStateException("Cannot add worker " + worker.id);
        WORKERS.add(worker.id);
    }
    private static void requestStage(String stage) {
        player.closeContainer();
        System.setProperty("frontier.pis1UiStage", stage);
        if (stage.startsWith("factory")) IndustryMenus.open(player, factory.id);
        phaseTick = 0;
    }
    private static void ui(MinecraftServer server) {
        if (phaseTick > 700) throw new IllegalStateException("UI stage timeout: " + STAGES.get(uiIndex));
        if (!STAGES.get(uiIndex).equals(System.getProperty("frontier.pis1UiDone"))) return;
        if (++uiIndex < STAGES.size()) { requestStage(STAGES.get(uiIndex)); return; }
        check("all 30 UI stage screenshots acknowledged", uiIndex == 30);
        check("search keeps inventory E key inside text field", Boolean.getBoolean("frontier.pis1SearchGuard"));
        check("real client clicked submit order", Boolean.getBoolean("frontier.pis1OrderClicked"));
        check("literal Russian status and button labels preserve percent signs", Boolean.getBoolean("frontier.pis1LiteralText"));
        check("client-submitted order reached real reserved production before fixture orders", sawPaid);
        System.setProperty("frontier.pis1UiStage", "");
        player.closeContainer();
        phase = 1; phaseTick = 0;
        // Preserve the actual UI order evidence; keep a long paid queue for disruption checks.
        IndustryEngine.action(player, factory.id, "order", recipeId, 64);
    }
    private static void waitForPaidOrder(MinecraftServer server) {
        Order order = factory.orders.peek();
        if (order == null || !order.reserved || order.progress == 0) {
            if (phaseTick > 200) throw new IllegalStateException("Submitted order never became paid production: " + factory.status);
            return;
        }
        check("submitted order paid and progressed on server", sawPaid);
        check("connected real blueprint factory is functional", factory.functional() && factory.powered);
        requestJadeWorldStage("world-jade-powered@2", true);
        phase = 11; phaseTick = 0;
    }
    private static void jadePowered(MinecraftServer server) {
        if (!jadeWorldAcknowledged("world-jade-powered@2", true)) return;
        Order order = factory.orders.peek();
        if (order == null || !order.reserved || order.progress == 0)
            throw new IllegalStateException("Powered Jade screenshot lost the actual paid order");
        observedOrder = order;
        pausedProgress = order.progress;
        damagedGrid = Blueprint.criticalBlocks(distribution.type, distribution.origin, distribution.blueprintVersion).keySet().iterator().next();
        gridState = level.getBlockState(damagedGrid);
        level.setBlock(damagedGrid, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        phase = 2; phaseTick = 0;
    }
    private static void verifyGridCut(MinecraftServer server) {
        if (phaseTick < 50) return;
        check("critical substation destruction pauses same paid order", factory.orders.peek() == observedOrder
            && observedOrder.reserved && observedOrder.progress == pausedProgress && !factory.powered);
        requestJadeWorldStage("world-jade-grid-cut@2", false);
        phase = 12; phaseTick = 0;
    }
    private static void jadeGridCut(MinecraftServer server) {
        if (!jadeWorldAcknowledged("world-jade-grid-cut@2", false)) return;
        check("Jade outage observation retains the same paid progress", factory.orders.peek() == observedOrder
            && observedOrder.reserved && observedOrder.progress == pausedProgress && !factory.powered);
        level.setBlock(damagedGrid, gridState, Block.UPDATE_ALL);
        var blueprint = Blueprint.blocks(factory.type, factory.origin, factory.blueprintVersion);
        int target = (int) Math.ceil(blueprint.size() * .30);
        factoryDamage = blueprint.entrySet().stream()
            .filter(entry -> entry.getKey().getY() >= factory.origin.getY() + 2)
            .filter(entry -> !entry.getKey().equals(Blueprint.controller(factory.type, factory.origin, factory.blueprintVersion)))
            .limit(target).map(Map.Entry::getKey).toList();
        if (factoryDamage.size() < target) throw new IllegalStateException("Not enough removable fixture wall/roof blocks");
        factoryDamage.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        IndustryEngine.poll(level, factory);
        phase = 3; phaseTick = 0;
    }
    private static void verifyFactoryDamage(MinecraftServer server) {
        if (phaseTick < 50) return;
        check("network repair does not repair a damaged factory", distribution.functional() && !factory.functional()
            && factory.orders.peek() == observedOrder && observedOrder.reserved && observedOrder.progress == pausedProgress);
        factory.inventory.setStackInSlot(11, new ItemStack(IndustryContent.REPAIR_KIT.get()));
        factory.repairRequested = true;
        factory.repairProgress = 0;
        data.setDirty();
        lastRepairProgress = repairAdvances = 0;
        phase = 4; phaseTick = 0;
    }
    private static void repair(MinecraftServer server) {
        int progress = factory.repairProgress;
        if (progress >= 300 && !repairImageRequested) {
            repairImageRequested = true;
            BlockPos entrance = Blueprint.entrance(factory.type, factory.origin, factory.blueprintVersion);
            player.teleportTo(level, entrance.getX() + .5, entrance.getY(), entrance.getZ() - 2.5, 0, 5);
            System.setProperty("frontier.pis1UiStage", "world-repair@2");
        }
        if (progress > lastRepairProgress) {
            check("two workers advance one shared tick at a time", progress == lastRepairProgress + 1);
            repairAdvances++;
        }
        if (progress == 1799 && !saw1799) {
            IndustryData saved = IndustryData.load(data.save(new CompoundTag()));
            Site restored = saved.sites.get(factory.id);
            check("live tick1799 progress reservation assignment survive NBT", restored != null && restored.repairProgress == 1799 && restored.repairKit && restored.repairRequested
                && WORKERS.stream().allMatch(id -> saved.workers.containsKey(id) && factory.id.equals(saved.workers.get(id).site)));
            saw1799 = true;
        }
        if (!factory.repairRequested) {
            // Completion atomically resets progress; count its final committed tick.
            if (lastRepairProgress == 1799) repairAdvances++;
            check("actual repair takes exactly1800 shared work ticks", repairAdvances == IndustryRules.REPAIR_TICKS && saw1799);
            IndustryEngine.poll(level, factory);
            check("NPC repair restored real missing blocks", factoryDamage.stream().allMatch(pos -> !level.getBlockState(pos).isAir()) && factory.matching == factory.total);
            check("two NPCs consume exactly one kit", !factory.repairKit && factory.inventory.getStackInSlot(11).isEmpty());
            check("world contains unique original worker UUIDs", WORKERS.stream().distinct().count() == WORKERS.size()
                && WORKERS.stream().allMatch(id -> level.getEntity(id) instanceof WorkerEntity));
            REPORT.addProperty("repairElapsedTicks", phaseTick);
            REPORT.addProperty("repairWorkingTicks", repairAdvances);
            REPORT.addProperty("realRestartPerformed", false);
            check("repair animation world screenshot acknowledged", "world-repair@2".equals(System.getProperty("frontier.pis1UiDone")));
            requestJadeWorldStage("world-jade-after-repair@2", true);
            phase = 13; phaseTick = 0;
            return;
        }
        lastRepairProgress = progress;
        if (phaseTick > 2600) throw new IllegalStateException("NPC repair timeout: progress=" + progress + ", status=" + factory.status);
    }
    private static void jadeAfterRepair(MinecraftServer server) {
        if (!jadeWorldAcknowledged("world-jade-after-repair@2", true)) return;
        check("Jade post-repair observation uses the physically repaired factory", factory.functional()
            && factory.matching == factory.total && !factory.repairRequested);
        player.teleportTo(level, factory.origin.getX() + 2.5, factory.origin.getY() + 1, factory.origin.getZ() - .5, -34, 15);
        System.setProperty("frontier.pis1UiStage", "world-press@2");
        phase = 7; phaseTick = 0;
    }
    private static void requestJadeWorldStage(String stage, boolean expectedPowered) {
        if (!IntegrationChecksMod.authorized(level.getServer()) || !factory.owner.equals(player.getUUID()))
            throw new IllegalStateException("Jade visual fixture requires its own matching isolated site");
        BlockPos target = Blueprint.controller(factory.type, factory.origin, factory.blueprintVersion);
        if (!level.getBlockState(target).is(IndustryContent.controller(factory.type)))
            throw new IllegalStateException("Jade visual fixture requires the existing physical controller");
        player.closeContainer();
        Vec3 center = Vec3.atCenterOf(target);
        double feetY = factory.origin.getY() + 1d;
        double eyeDelta = feetY + player.getEyeHeight() - center.y;
        double horizontal = Math.sqrt(9d - eyeDelta * eyeDelta);
        double cameraX = center.x - horizontal, cameraZ = center.z;
        float yaw = -90f;
        float pitch = (float) Math.toDegrees(Math.atan2(eyeDelta, horizontal));
        // This west-to-east sight line lies inside the existing entrance hall;
        // no wall, machinery, controller or gameplay state is moved for Jade.
        player.teleportTo(level, cameraX, feetY, cameraZ, yaw, pitch);
        player.setDeltaMovement(Vec3.ZERO);
        BlockHitResult hit = level.clip(new ClipContext(player.getEyePosition(), center,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        check(stage + " real server ray targets the existing controller", hit.getType() == HitResult.Type.BLOCK && target.equals(hit.getBlockPos()));
        System.setProperty("frontier.pis1JadeTargetX", Integer.toString(target.getX()));
        System.setProperty("frontier.pis1JadeTargetY", Integer.toString(target.getY()));
        System.setProperty("frontier.pis1JadeTargetZ", Integer.toString(target.getZ()));
        System.setProperty("frontier.pis1JadeExpectedPowered", Boolean.toString(expectedPowered));
        System.setProperty("frontier.pis1JadeCameraX", Double.toString(cameraX));
        System.setProperty("frontier.pis1JadeCameraY", Double.toString(feetY));
        System.setProperty("frontier.pis1JadeCameraZ", Double.toString(cameraZ));
        System.setProperty("frontier.pis1JadeCameraYaw", Float.toString(yaw));
        System.setProperty("frontier.pis1JadeCameraPitch", Float.toString(pitch));
        System.setProperty("frontier.pis1UiStage", stage);
        phaseTick = 0;
    }
    private static boolean jadeWorldAcknowledged(String stage, boolean expectedPowered) {
        if (!stage.equals(System.getProperty("frontier.pis1UiDone"))) {
            if (phaseTick > 650) throw new IllegalStateException("Jade world screenshot timeout: " + stage);
            return false;
        }
        check(stage + " owner world screenshot acknowledged", true);
        check(stage + " actual server power matches the photographed scenario", factory.powered == expectedPowered);
        JsonObject screen = new JsonObject();
        screen.addProperty("stage", stage);
        screen.addProperty("screenshot", "screenshots/pis1-" + stage.replace('@', '-') + ".png");
        screen.addProperty("acknowledged", true);
        screen.addProperty("owner", factory.owner.equals(player.getUUID()));
        screen.addProperty("powered", factory.powered);
        screen.addProperty("functional", factory.functional());
        screen.addProperty("status", factory.status);
        screen.addProperty("controller", Blueprint.controller(factory.type, factory.origin, factory.blueprintVersion).asLong());
        JADE_WORLD_SCREENS.add(screen);
        return true;
    }
    private static void worldVisuals(MinecraftServer server) {
        if (!"world-press@2".equals(System.getProperty("frontier.pis1UiDone"))) {
            if (phaseTick > 650) throw new IllegalStateException("Working press world screenshot timeout");
            return;
        }
        check("press animation world screenshot acknowledged", true);
        check("production resumes after real NPC repair", factory.functional() && factory.powered
            && factory.orders.peek() != null && factory.orders.peek().reserved);
        player.closeContainer();
        System.setProperty("frontier.pis1UiStage", "");
        phase = 8; phaseTick = 0;
    }
    private static void catalogReload(MinecraftServer server) throws Exception {
        if (!CatalogReloadFixture.tick(server, player, factory)) return;
        if (Boolean.getBoolean("frontier.pis1SkipBenchmark")) { phase = 9; phaseTick = 0; }
        else beginBenchmark();
    }
    private static void removeWorkers() {
        for (UUID id : WORKERS) {
            Entity entity = level.getEntity(id);
            if (entity != null) entity.discard();
            data.workers.remove(id);
        }
        WORKERS.clear();
    }
    private static void beginBenchmark() {
        removeWorkers();
        player.closeContainer();
        System.setProperty("frontier.pis1UiStage", "");
        BlockPos entrance = Blueprint.entrance(factory.type, factory.origin, factory.blueprintVersion);
        double cameraX = entrance.getX() + .5, cameraY = entrance.getY() + 1.5, cameraZ = entrance.getZ() - 5.5;
        System.setProperty("frontier.pis1CameraX", Double.toString(cameraX));
        System.setProperty("frontier.pis1CameraY", Double.toString(cameraY));
        System.setProperty("frontier.pis1CameraZ", Double.toString(cameraZ));
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, cameraX, cameraY, cameraZ, 0, 5);
        benchmarkPauseEpoch = Integer.getInteger("frontier.pis1BenchmarkPauseEpoch", 0);
        benchmarkPauseResets = 0;
        System.setProperty("frontier.pis1BenchmarkActive", "true");
        phase = 5; phaseTick = 0; TICK_NANOS.clear();
        resetBenchmarkWork();
        damageBenchmarkTarget();
        System.out.println("PIS1_BENCHMARK_BEGIN npc=0 warmup=" + WARMUP + " samples=" + SAMPLES);
    }
    private static void damageBenchmarkTarget() {
        BlockPos pos = factory.origin.offset(0, 5, -5);
        BlockState expected = Blueprint.blocks(factory.type, factory.origin, factory.blueprintVersion).get(pos);
        if (expected == null || expected.isAir()) throw new IllegalStateException("Benchmark target is absent from blueprint");
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        IndustryEngine.poll(level, factory);
        factory.inventory.setStackInSlot(11, new ItemStack(IndustryContent.REPAIR_KIT.get(), 64));
        factory.repairRequested = true; factory.repairProgress = 0; factory.repairKit = false;
        data.setDirty();
    }
    private static void benchmark(MinecraftServer server) {
        int pauseEpoch = Integer.getInteger("frontier.pis1BenchmarkPauseEpoch", 0);
        if (pauseEpoch != benchmarkPauseEpoch) {
            benchmarkPauseEpoch = pauseEpoch;
            benchmarkPauseResets++;
            benchmarkDiscardedTicks += phaseTick;
            phaseTick = 0;
            TICK_NANOS.clear();
            resetBenchmarkWork();
            benchmarkPreviousProgress = factory.repairProgress;
            System.out.println("PIS1_BENCHMARK_PAUSE_RESET npc=" + (phase == 5 ? 0 : 20)
                + " epoch=" + pauseEpoch + " warmup=" + WARMUP + " samples=" + SAMPLES);
            return;
        }
        int progress = factory.repairProgress;
        benchmarkMaxProgress = Math.max(benchmarkMaxProgress, progress);
        if (progress > benchmarkPreviousProgress) benchmarkWorkingTicks++;
        if (!factory.repairRequested && benchmarkPreviousProgress > 0 && factory.matching >= factory.total) {
            benchmarkCompletedCycles++;
            benchmarkWorkingTicks++; // The final committed tick resets the shared progress to zero.
            benchmarkMaxProgress = Math.max(benchmarkMaxProgress, IndustryRules.REPAIR_TICKS);
        }
        if (!factory.repairRequested) damageBenchmarkTarget();
        benchmarkPreviousProgress = factory.repairProgress;
        if (phaseTick > WARMUP && phaseTick <= WARMUP + SAMPLES)
            TICK_NANOS.add(server.tickTimes[Math.floorMod(server.getTickCount() - 1, server.tickTimes.length)]);
        if (phaseTick < WARMUP + SAMPLES) return;
        BENCHMARKS.add(timing(phase == 5 ? 0 : 20));
        if (phase == 5) {
            phase = 6; phaseTick = 0; TICK_NANOS.clear();
            benchmarkPauseResets = 0;
            resetBenchmarkWork();
            BlockPos entrance = Blueprint.entrance(factory.type, factory.origin, factory.blueprintVersion);
            for (int n = 0; n < 20; n++) worker("factory_repair", entrance.offset(n % 7 - 3, 0, -n / 7));
            damageBenchmarkTarget();
            System.out.println("PIS1_BENCHMARK_BEGIN npc=20 warmup=" + WARMUP + " samples=" + SAMPLES);
        } else {
            check("benchmark recorded20 unique physical workers", WORKERS.size() == 20
                && WORKERS.stream().allMatch(id -> level.getEntity(id) instanceof WorkerEntity worker && worker.isAlive()));
            check("twenty NPC benchmark performed actual repair work", benchmarkMaxProgress > 0 && benchmarkWorkingTicks > 0);
            check("two benchmark runs each contain2400 measured server ticks", BENCHMARKS.size() == 2
                && BENCHMARKS.get(0).getAsJsonObject().get("samples").getAsInt() == SAMPLES
                && BENCHMARKS.get(1).getAsJsonObject().get("samples").getAsInt() == SAMPLES);
            System.setProperty("frontier.pis1BenchmarkActive", "false");
            player.setGameMode(GameType.CREATIVE);
            if (benchmarkOnly) finish(server);
            else { phase = 9; phaseTick = 0; }
        }
    }
    private static void resetBenchmarkWork() {
        benchmarkPreviousProgress = benchmarkMaxProgress = benchmarkWorkingTicks = benchmarkCompletedCycles = 0;
    }
    private static JsonObject timing(int count) {
        long[] sorted = TICK_NANOS.stream().mapToLong(Long::longValue).sorted().toArray();
        JsonObject result = new JsonObject();
        result.addProperty("workers", count); result.addProperty("warmupTicks", WARMUP); result.addProperty("samples", sorted.length);
        result.addProperty("pauseResets", benchmarkPauseResets);
        result.addProperty("lastPauseEpoch", benchmarkPauseEpoch);
        result.addProperty("continuousTicks", phaseTick);
        result.addProperty("meanMs", Arrays.stream(sorted).average().orElse(0) / 1_000_000d);
        result.addProperty("p50Ms", percentile(sorted, .50)); result.addProperty("p95Ms", percentile(sorted, .95));
        result.addProperty("maxMs", sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1_000_000d);
        result.addProperty("targetRepairProgressAtEnd", factory.repairProgress);
        result.addProperty("targetRepairProgressMax", benchmarkMaxProgress);
        result.addProperty("repairWorkingTicks", benchmarkWorkingTicks);
        result.addProperty("completedRepairCycles", benchmarkCompletedCycles);
        result.addProperty("clientSettings", System.getProperty("frontier.pis1BenchmarkSettings", "unconfirmed"));
        result.addProperty("cameraX", player.getX()); result.addProperty("cameraY", player.getY()); result.addProperty("cameraZ", player.getZ());
        result.addProperty("cameraYaw", player.getYRot()); result.addProperty("cameraPitch", player.getXRot());
        return result;
    }
    private static double percentile(long[] sorted, double percentile) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile) - 1)] / 1_000_000d;
    }
    private static void validateRestart(MinecraftServer server) {
        REPORT.addProperty("runId", System.getProperty("frontier.pis1RunId"));
        REPORT.addProperty("realRestartPerformed", true);
        IndustryData reloaded = IndustryData.get(server);
        player = server.getPlayerList().getPlayers().get(0);
        level = player.serverLevel(); data = reloaded;
        factory = reloaded.sites.values().stream().filter(site -> site.type == SiteType.EQUIPMENT_FACTORY).findFirst().orElseThrow();
        distribution = reloaded.sites.get(factory.upstream);
        station = distribution == null ? null : reloaded.sites.get(distribution.upstream);
        check("real server restart retained fixture site identities", reloaded.sites.size() == 3);
        for (Site site : reloaded.sites.values()) holdSiteChunks(site.type, site.origin, site.blueprintVersion);
        if (benchmarkOnly) check("real server restart retained20 worker ledger identities", reloaded.workers.values().stream().filter(Worker::active).count() == 20);
        else check("real server restart retained worker ledger for exact receipt validation", reloaded.workers.values().stream().anyMatch(Worker::active));
        check("real server restart retained finite energy and inventory", reloaded.sites.values().stream().allMatch(site ->
            site.energy >= 0 && site.energy <= IndustryRules.BUFFER && site.inventory.getSlots() == 24));
    }
    private static void check(String name, boolean passed) {
        // An earlier failure cannot be overwritten by a later successful tick.
        CHECKS.merge(name, passed, (previous, current) -> previous && current);
        if (!passed) System.err.println("PIS1_WORLD_CHECK_FAIL " + name);
    }
    private static void finish(MinecraftServer server) {
        if (done) return;
        done = true;
        if (!Boolean.getBoolean("frontier.pis1BenchmarkOnly")) {
            CatalogReloadFixture.checks().forEach((name, passed) -> check("catalog reload: " + name, passed));
            CatalogRestartFixture.checks().forEach((name, passed) -> check("catalog restart: " + name, passed));
            REPORT.add("catalogReload", CatalogReloadFixture.receipt());
            REPORT.add("catalogRestart", CatalogRestartFixture.receipt());
        }
        if (Boolean.getBoolean("frontier.pis1Restart") && !HELD_CHUNKS.isEmpty()) {
            boolean authorized = IntegrationChecksMod.authorized(server);
            check("fixture chunk release remains inside the matching isolated profile", authorized);
            if (authorized) {
                for (long packed : HELD_CHUNKS) {
                    ChunkPos chunk = new ChunkPos(packed);
                    level.setChunkForced(chunk.x, chunk.z, false);
                }
                REPORT.addProperty("releasedFixtureChunks", HELD_CHUNKS.size());
                HELD_CHUNKS.clear();
            }
        }
        REPORT.addProperty("completedAt", Instant.now().toString());
        REPORT.addProperty("elapsedTicks", elapsed);
        REPORT.addProperty("passed", !CHECKS.isEmpty() && CHECKS.values().stream().allMatch(Boolean::booleanValue));
        var gson = new GsonBuilder().setPrettyPrinting().create();
        REPORT.add("checks", gson.toJsonTree(CHECKS));
        REPORT.add("benchmarks", BENCHMARKS);
        REPORT.add("jadeWorldScreenshots", JADE_WORLD_SCREENS);
        try {
            Path output = FMLPaths.GAMEDIR.get().resolve(Boolean.getBoolean("frontier.pis1Restart") ? "pis1-world-restart-checks.json" : "pis1-world-checks.json");
            Files.writeString(output, gson.toJson(REPORT) + "\n", StandardCharsets.UTF_8);
            System.out.println("PIS1_WORLD_DONE passed=" + REPORT.get("passed") + " report=" + output);
        } catch (Exception error) { error.printStackTrace(); }
        System.setProperty("frontier.pis1Stop", "true");
    }
}
