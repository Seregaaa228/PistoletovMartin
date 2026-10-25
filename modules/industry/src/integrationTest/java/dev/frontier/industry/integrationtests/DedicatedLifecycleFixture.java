package dev.frontier.industry.integrationtests;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.WorkerEntity;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import dev.frontier.industry.engine.IndustryRules;
import dev.frontier.industry.repair.PressWorkstationEntity;
import dev.frontier.industry.repair.RepairStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;

/** Destructive, fresh-world-only acceptance extension. No product code calls this class. */
final class DedicatedLifecycleFixture {
    private final DedicatedServer server;
    private final ServerLevel level;
    private final IndustryData data;
    private final Site station, distribution, factory;
    private final Path profile = FMLPaths.GAMEDIR.get();
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private final Set<Long> held = new LinkedHashSet<>();
    private final List<BlockPos> cage = new ArrayList<>();
    private final JsonArray acknowledgements = new JsonArray();
    private final JsonObject report = new JsonObject();
    private final BlockPos entrance, press, controller, gridDamage;
    private final BlockState gridState;
    private ServerPlayer owner, foreign, reconnectOriginal;
    private UUID repairId, deadId;
    private BlockPos sceneTarget;
    private String waitingPhase;
    private Integer expectedAnimation;
    private Boolean expectedOperating;
    private int phase, phaseTicks, elapsed, sequence, waitingSequence;
    private int previousProgress, workingTicks, completions, pausedProgress, paidProgress;
    private long previousPath = Long.MIN_VALUE;
    private int pathAttempts;
    private boolean sawNoPath, reconnect, sawDisconnect, sawReconnect, finished, unloaded;

    DedicatedLifecycleFixture(DedicatedServer server, ServerLevel level, Site station, Site distribution, Site factory,
                              ServerPlayer owner, ServerPlayer foreign, Set<Long> initialTickets) {
        if (!Pis1DedicatedFixture.authorized(server)) throw new IllegalStateException("Lifecycle requires authorized fresh dedicated profile");
        this.server = server; this.level = level; this.station = station; this.distribution = distribution; this.factory = factory;
        this.owner = owner; this.foreign = foreign; this.data = IndustryData.get(server);
        held.addAll(initialTickets);
        if (data.sites.size() != 3 || !data.workers.isEmpty()) throw new IllegalStateException("Lifecycle requires exactly the three newly created fixture sites");
        entrance = Blueprint.entrance(factory.type, factory.origin, factory.blueprintVersion);
        press = factory.origin.offset(4, 1, 2);
        controller = Blueprint.controller(factory.type, factory.origin, factory.blueprintVersion);
        gridDamage = Blueprint.criticalBlocks(distribution.type, distribution.origin, distribution.blueprintVersion).keySet().iterator().next();
        gridState = level.getBlockState(gridDamage);
        sceneTarget = controller;
        holdChunks();
        report.addProperty("startedAt", Instant.now().toString());
        report.addProperty("evidencePath", "Actual server entities, death, chunk unload/reload, and both clients' tracked entity/block-entity state");
    }

    boolean done() { return finished; }
    boolean allowsForeignReconnect() { return reconnect; }
    Map<String, Boolean> checks() { return Map.copyOf(checks); }
    JsonObject report() {
        report.addProperty("phase", phase); report.addProperty("workingTicks", workingTicks); report.addProperty("completedRepairCycles", completions);
        report.addProperty("pathAttempts", pathAttempts); report.addProperty("actualChunkUnload", unloaded);
        if (repairId != null) report.addProperty("repairWorkerId", repairId.toString());
        if (deadId != null) report.addProperty("deadWorkerId", deadId.toString());
        report.add("clientAcknowledgements", acknowledgements);
        return report;
    }

    void tick(ServerPlayer owner, ServerPlayer foreign) throws Exception {
        this.owner = owner; this.foreign = foreign;
        if (!Pis1DedicatedFixture.authorized(server)) throw new IllegalStateException("Lifecycle authorization expired");
        if (++elapsed > 22000) throw new IllegalStateException("Lifecycle exceeded 22000 ticks at phase " + phase);
        phaseTicks++;
        trackRepair();
        if (reconnect) {
            if (foreign == null) sawDisconnect = true;
            else if (sawDisconnect && foreign != reconnectOriginal) sawReconnect = true;
        }
        if (waitingSequence != 0) {
            if (phaseTicks > 2400) throw new IllegalStateException("Client acknowledgement timed out for sequence " + waitingSequence);
            if (!acknowledged()) return;
            if (reconnect) {
                check("real foreign reconnect with original UUID at sequence " + waitingSequence, sawDisconnect && sawReconnect);
                if (foreign == null) throw new IllegalStateException("Foreign must be connected before advancing");
            }
            waitingSequence = 0; reconnect = false; phase++; phaseTicks = 0;
        }
        switch (phase) {
            case 0 -> stage("jade-powered", "jade", controller, null, null, null, false);
            case 1 -> { grid(false); next(); }
            case 2 -> { if (phaseTicks >= 20) stage("jade-cut", "jade", controller, null, null, null, false); }
            case 3 -> { grid(true); next(); }
            case 4 -> { if (phaseTicks >= 20) stage("jade-restored", "jade", controller, null, null, null, false); }
            case 5 -> { setupCagedRepair(); next(); }
            case 6 -> {
                WorkerEntity worker = repairEntity();
                if (worker != null) {
                    sawNoPath |= worker.getRepairStatus() == RepairStatus.NO_PATH;
                    long attempt = worker.getPersistentData().getLong("frontierRepairLastPath");
                    if (attempt > 0 && attempt != previousPath) {
                        if (previousPath != Long.MIN_VALUE) check("path retry respects 100 tick backoff " + pathAttempts, attempt - previousPath >= 100);
                        previousPath = attempt; pathAttempts++;
                    }
                }
                if (phaseTicks >= 240) {
                    check("real enclosed worker reports no path", sawNoPath);
                    check("unreachable job neither progresses nor reserves kit", factory.repairProgress == 0 && !factory.repairKit && kits() == 1);
                    check("unreachable path attempts are bounded", pathAttempts > 0 && pathAttempts <= 3);
                    stage("repair-unreachable", "world", worker.blockPosition(), repairId, 0, false, false);
                }
            }
            case 7 -> { cage.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)); next(); }
            case 8 -> {
                requireRepairBudget();
                if (factory.repairProgress >= 100 && repairEntity() != null && repairEntity().getRepairStatus() == RepairStatus.REPAIRING) {
                    check("opening physical cage enables actual path and shared repair work", factory.repairKit && kits() == 0);
                    stage("repair-working", "world", repairEntity().blockPosition(), repairId, 2, false, false);
                } else timeout(600, "Worker did not reach the entrance after cage opening");
            }
            case 9 -> {
                requireRepairBudget(); pausedProgress = factory.repairProgress; deadId = repairId;
                WorkerEntity worker = repairEntity();
                if (worker == null || !worker.hurt(level.damageSources().genericKill(), 1000)) throw new IllegalStateException("Real worker death was not applied");
                next();
            }
            case 10 -> {
                if (phaseTicks >= 100) {
                    Worker dead = data.workers.get(deadId);
                    check("death leaves one inactive ledger tombstone without assignment", dead != null && dead.dead && !dead.active() && dead.site == null);
                    check("death pauses progress and retains the single reserved kit", factory.repairProgress == pausedProgress && factory.repairRequested && factory.repairKit && kits() == 0);
                    check("no replacement entity is automatically minted on death", liveRepairers() == 0);
                    stage("repair-death-paused", "world", entrance, null, null, false, false);
                }
            }
            case 11 -> { repairId = worker("factory_repair", entrance.offset(0, 0, -1)).getUUID(); next(); }
            case 12 -> {
                requireRepairBudget();
                if (factory.repairProgress >= pausedProgress + 80) {
                    check("explicit replacement continues existing paid repair", factory.repairKit && liveRepairers() == 1 && data.workers.get(deadId).dead);
                    pausedProgress = factory.repairProgress;
                    server.saveEverything(false, true, true);
                    releaseChunks();
                    away(owner); away(foreign); next();
                } else timeout(500, "Replacement did not resume shared repair");
            }
            case 13 -> {
                requireRepairBudget();
                if (!level.hasChunkAt(factory.origin) && level.getEntity(repairId) == null) {
                    unloaded = true; pausedProgress = factory.repairProgress;
                    report.addProperty("progressAtActualUnload", pausedProgress); next();
                } else timeout(1200, "Factory chunk/entity did not actually unload");
            }
            case 14 -> {
                if (phaseTicks >= 100) {
                    check("actual unloaded site preserves progress and reserved kit", factory.repairProgress == pausedProgress && factory.repairKit && factory.repairRequested);
                    holdChunks(); placeObserver(owner); placeObserver(foreign); next();
                }
            }
            case 15 -> {
                requireRepairBudget();
                WorkerEntity worker = repairEntity();
                if (worker != null && factory.repairProgress > pausedProgress) {
                    check("chunk reload restores original UUID owner role and assignment", worker.getUUID().equals(repairId)
                        && worker.getPersistentData().getString("frontierIndustryOwner").equals(owner.getUUID().toString())
                        && worker.getPersistentData().getString("frontierIndustryRole").equals("factory_repair")
                        && worker.getPersistentData().getString("frontierIndustrySite").equals(factory.id.toString()));
                    check("chunk reload resumes existing progress without second kit or duplicate", factory.repairKit && kits() == 0 && liveRepairers() == 1);
                    stage("repair-chunk-resumed", "world", worker.blockPosition(), repairId, 2, false, false);
                } else timeout(400, "Reloaded worker did not resume repair");
            }
            case 16 -> {
                requireRepairBudget();
                stage("repair-reconnect", "world", repairEntity().blockPosition(), repairId, 2, false, true);
            }
            case 17 -> {
                if (!factory.repairRequested && completions == 1) {
                    check("repair accumulates exactly 1800 shared work ticks across death and unload", workingTicks == IndustryRules.REPAIR_TICKS);
                    check("real repair finishes once restores blocks and consumes exactly one kit", factory.matching == factory.total && !factory.repairKit && kits() == 0 && completions == 1);
                    stage("repair-complete-idle", "world", repairEntity().blockPosition(), repairId, 0, false, false);
                } else timeout(2200, "Repair did not finish after resume");
            }
            case 18 -> {
                check("idle finished repair cannot complete twice", completions == 1 && !factory.repairRequested && factory.repairProgress == 0 && kits() == 0);
                factory.orders.peek().quantity = 64;
                factory.inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
                factory.inventory.setStackInSlot(1, new ItemStack(Items.REDSTONE, 64));
                factory.inventory.setStackInSlot(2, new ItemStack(Items.GUNPOWDER, 64));
                worker("production", factory.origin.offset(0, 1, 0)); data.setDirty(); next();
            }
            case 19 -> {
                if (factory.operating && pressOperating()) stage("press-working", "world", press, repairId, 0, true, false);
                else timeout(600, "Production did not advance or press operating state did not sync server-side");
            }
            case 20 -> { paidProgress = factory.orders.peek().progress; grid(false); next(); }
            case 21 -> {
                if (phaseTicks >= 20) {
                    check("actual grid outage stops production and press animation", !factory.operating && !pressOperating());
                    check("grid outage preserves paid unit progress", factory.orders.peek() != null && factory.orders.peek().reserved && factory.orders.peek().progress == paidProgress);
                    stage("press-grid-cut", "world", press, repairId, 0, false, false);
                }
            }
            case 22 -> { grid(true); next(); }
            case 23 -> {
                if (factory.operating && pressOperating()) stage("press-restored", "world", press, repairId, 0, true, false);
                else timeout(600, "Press did not resume after grid restoration");
            }
            case 24 -> stage("press-reconnect", "world", press, repairId, 0, true, true);
            case 25 -> {
                check("no duplicate completion after production and two real reconnects", completions == 1 && liveRepairers() == 1 && data.workers.get(deadId).dead);
                finished = true; report.addProperty("completedAt", Instant.now().toString()); cleanup();
            }
            default -> throw new IllegalStateException("Unknown lifecycle phase " + phase);
        }
    }

    private void setupCagedRepair() {
        for (int x = -3; x <= 8; x++) for (int z = -6; z <= 0; z++) {
            BlockPos floor = entrance.offset(x, -1, z);
            if (level.getBlockState(floor).isAir()) level.setBlock(floor, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos missing = Blueprint.blocks(factory.type, factory.origin, factory.blueprintVersion).keySet().stream()
            .filter(pos -> pos.getY() >= factory.origin.getY() + 4 && level.getBlockEntity(pos) == null).findFirst().orElseThrow();
        level.setBlock(missing, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); IndustryEngine.poll(level, factory);
        factory.inventory.setStackInSlot(11, new ItemStack(IndustryContent.REPAIR_KIT.get()));
        IndustryEngine.action(owner, factory.id, "repair", "", 0);
        if (!factory.repairRequested) throw new IllegalStateException("Owner repair action was denied");
        BlockPos center = entrance.offset(6, 0, -2);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 3; y++)
            if (Math.abs(x) == 1 || Math.abs(z) == 1 || y == 3) {
                BlockPos pos = center.offset(x, y, z);
                if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Cage must occupy fresh empty air");
                cage.add(pos); level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
            }
        repairId = worker("factory_repair", center).getUUID();
        previousProgress = factory.repairProgress;
        report.addProperty("damagedFactoryBlock", missing.toShortString());
    }
    private WorkerEntity worker(String role, BlockPos pos) {
        WorkerEntity entity = IndustryContent.WORKER.get().create(level);
        if (entity == null) throw new IllegalStateException("Cannot create real worker");
        Worker record = new Worker(entity.getUUID(), owner.getUUID(), role, level.dimension().location());
        record.site = factory.id; data.workers.put(record.id, record); IndustryEngine.initialize(entity, record);
        entity.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        if (!level.addFreshEntity(entity)) throw new IllegalStateException("Cannot add real worker");
        data.setDirty(); return entity;
    }
    private WorkerEntity repairEntity() { return repairId != null && level.getEntity(repairId) instanceof WorkerEntity worker ? worker : null; }
    private long liveRepairers() {
        return data.workers.values().stream().filter(w -> w.active() && factory.id.equals(w.site) && w.role.equals("factory_repair")).count();
    }
    private int kits() {
        int count = 0;
        for (int i = 0; i < IndustryRules.INPUTS; i++)
            if (factory.inventory.getStackInSlot(i).is(IndustryContent.REPAIR_KIT.get())) count += factory.inventory.getStackInSlot(i).getCount();
        return count;
    }
    private void trackRepair() {
        int current = factory.repairProgress;
        if (current > previousProgress) workingTicks += current - previousProgress;
        if (previousProgress == IndustryRules.REPAIR_TICKS - 1 && current == 0 && !factory.repairRequested) { workingTicks++; completions++; }
        previousProgress = current;
    }
    private void requireRepairBudget() {
        if (!factory.repairRequested) throw new IllegalStateException("Repair finished before lifecycle observation; stage latency exceeded actual job duration at phase " + phase);
    }
    private void grid(boolean restored) {
        level.setBlock(gridDamage, restored ? gridState : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        IndustryEngine.poll(level, distribution);
    }
    private boolean pressOperating() { return level.getBlockEntity(press) instanceof PressWorkstationEntity be && be.operating(); }
    private void next() { phase++; phaseTicks = 0; }
    private void timeout(int ticks, String reason) { if (phaseTicks > ticks) throw new IllegalStateException(reason); }
    private void check(String name, boolean value) {
        checks.put(name, value);
        if (!value) System.err.println("PIS1_DEDICATED_LIFECYCLE_FAIL " + name);
    }

    private void stage(String name, String kind, BlockPos target, UUID workerId, Integer animation, Boolean operating, boolean reconnect) throws Exception {
        this.sceneTarget = target; placeObserver(owner); placeObserver(foreign);
        JsonObject stage = new JsonObject();
        stage.addProperty("runId", System.getProperty("frontier.pis1RunId"));
        stage.addProperty("sequence", ++sequence); stage.addProperty("phase", name); stage.addProperty("kind", kind);
        waitingPhase = name;
        expectedAnimation = animation; expectedOperating = operating;
        stage.add("target", position(target)); stage.addProperty("capture", true);
        stage.addProperty("settleTicks", 40); stage.addProperty("timeoutTicks", 400);
        if (kind.equals("jade")) stage.addProperty("expectedPowered", !name.contains("cut"));
        if (workerId != null) stage.addProperty("workerId", workerId.toString());
        if (animation != null) stage.addProperty("expectedWorkerAnimation", animation);
        if (operating != null) { stage.add("pressPos", position(press)); stage.addProperty("expectedPressOperating", operating); }
        stage.addProperty("requiresReconnect", reconnect);
        Files.writeString(profile.resolve("pis1-dedicated-stage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(stage) + "\n", StandardCharsets.UTF_8);
        waitingSequence = sequence; phaseTicks = 0;
        this.reconnect = reconnect; sawDisconnect = sawReconnect = false; reconnectOriginal = foreign;
        System.out.println("PIS1_DEDICATED_STAGE " + sequence + " " + name);
    }
    private boolean acknowledged() throws Exception {
        Path path = profile.resolve("pis1-dedicated-ack.json");
        if (!Files.isRegularFile(path)) return false;
        if (Files.size(path) > 131072 || !path.toRealPath().getParent().equals(profile.toRealPath()))
            throw new IllegalStateException("Acknowledgement must be a bounded local profile file");
        JsonObject ack = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!ack.has("runId") || !System.getProperty("frontier.pis1RunId").equals(ack.get("runId").getAsString())
            || !ack.has("sequence") || ack.get("sequence").getAsInt() != waitingSequence || !truth(ack, "proceed")) return false;
        boolean own = evidence(ack, "owner", owner), other = evidence(ack, "foreign", foreign);
        check("owner actual client observation sequence " + waitingSequence, own);
        check("foreign actual client observation sequence " + waitingSequence, other);
        acknowledgements.add(ack.deepCopy());
        return true;
    }
    private boolean evidence(JsonObject ack, String role, ServerPlayer participant) {
        if (participant == null || !truth(ack, role + "Passed") || !ack.has("evidence") || !ack.get("evidence").isJsonObject()) return false;
        JsonObject all = ack.getAsJsonObject("evidence");
        if (!all.has(role) || !all.get(role).isJsonObject()) return false;
        JsonObject receipt = all.getAsJsonObject(role);
        if (expectedAnimation != null && (!receipt.has("workerAnimation") || receipt.get("workerAnimation").getAsInt() != expectedAnimation)) return false;
        if (expectedOperating != null && (!receipt.has("pressOperating") || receipt.get("pressOperating").getAsBoolean() != expectedOperating)) return false;
        // Client control sequence numbers are profile-local and also count disconnect/connect commands.
        return truth(receipt, "passed") && truth(receipt, "completed") && receipt.has("sequence")
            && receipt.get("sequence").getAsInt() > 0 && receipt.has("phase") && waitingPhase.equals(receipt.get("phase").getAsString())
            && receipt.has("role") && role.equals(receipt.get("role").getAsString())
            && receipt.has("playerUuid") && participant.getUUID().toString().equals(receipt.get("playerUuid").getAsString())
            && receipt.has("screenshot") && !receipt.get("screenshot").getAsString().isBlank();
    }
    private static boolean truth(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean(); }
    private static JsonObject position(BlockPos pos) {
        JsonObject result = new JsonObject(); result.addProperty("x", pos.getX()); result.addProperty("y", pos.getY()); result.addProperty("z", pos.getZ()); return result;
    }
    void placeObserver(ServerPlayer player) {
        if (player == null) return;
        Vec3 center = Vec3.atCenterOf(sceneTarget);
        // Choose a real unobstructed ray. Jade must target the controller rather than a wall.
        Vec3 position = null;
        // The foreign observer uses a separate diagonal lane: overlapping players
        // intercept the real client's entity ray before it reaches the Jade block.
        boolean other = !player.getUUID().equals(factory.owner);
        List<Vec3> offsets = other
            ? List.of(new Vec3(-3, 0, 1), new Vec3(1, 0, 3), new Vec3(-1, 0, -3), new Vec3(3, 0, -1))
            : List.of(new Vec3(-3, 0, 0), new Vec3(0, 0, 3), new Vec3(0, 0, -3), new Vec3(3, 0, 0));
        for (Vec3 delta : offsets) {
            Vec3 feet = center.add(delta).add(0, -.5, 0);
            ServerPlayer participant = other ? owner : foreign;
            if (participant != null && participant.serverLevel() == level
                && participant.position().distanceToSqr(feet) < 1) continue;
            var hit = level.clip(new ClipContext(feet.add(0, player.getEyeHeight(), 0), center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(sceneTarget)) { position = feet; break; }
        }
        if (position == null) position = center.add(other ? 1 : -1, .5, -4);
        Vec3 direction = center.subtract(position.add(0, player.getEyeHeight(), 0));
        float yaw = (float) (Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z)));
        player.teleportTo(level, position.x, position.y, position.z, yaw, pitch);
        player.setDeltaMovement(Vec3.ZERO); player.setNoGravity(true);
    }
    private void away(ServerPlayer player) { if (player != null) player.teleportTo(level, .5, 200, .5, 0, 0); }
    private void holdChunks() {
        for (Site site : List.of(station, distribution, factory)) {
            var bounds = Blueprint.bounds(site.type, site.blueprintVersion);
            for (int x = (site.origin.getX() + bounds.minX() - 8) >> 4; x <= (site.origin.getX() + bounds.maxX() + 8) >> 4; x++)
                for (int z = (site.origin.getZ() + bounds.minZ() - 8) >> 4; z <= (site.origin.getZ() + bounds.maxZ() + 8) >> 4; z++) {
                    long key = ChunkPos.asLong(x, z);
                    if (!level.getForcedChunks().contains(key)) { level.setChunkForced(x, z, true); held.add(key); }
                }
        }
    }
    private void releaseChunks() {
        for (long key : held) { ChunkPos chunk = new ChunkPos(key); level.setChunkForced(chunk.x, chunk.z, false); }
        held.clear();
    }
    void cleanup() { releaseChunks(); }
}
