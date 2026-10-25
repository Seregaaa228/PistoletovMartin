package dev.frontier.industry.integrationtests;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.arcade.control.ControlContent;
import dev.frontier.arcade.control.ControlSessions;
import dev.frontier.arcade.relay.RemoteDroneView;
import dev.frontier.industry.research.ResearchPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Opt-in, test-only operator reproduction. It never launches or moves an aircraft on the server. */
public final class Pis1DroneWalkthroughFixture {
    private static final String MAVIC = "uncomplicatedfpv:mavic_drone_no_drop";
    private static final String[] STAGES = {"book-controllers-2", "book-flight-2", "book-controllers-3", "book-flight-3",
        "place", "bind", "connect", "fly", "exit"};
    private static final BlockPos FLOOR = new BlockPos(8352, 180, 8192);
    private static final BlockPos PLACE = FLOOR.offset(2, 0, 3);
    private static final Map<String, Boolean> CHECKS = new LinkedHashMap<>();
    private static final JsonObject REPORT = new JsonObject();
    private static final List<BlockPos> PLATFORM = new ArrayList<>();
    private static final List<Long> FORCED = new ArrayList<>();
    private static boolean initialized, begun, done, supplied;
    private static int poll, stageIndex, stageStarted, selected;
    private static String token, runId;
    private static ServerPlayer owner, foreign;
    private static ServerLevel level, oldLevel;
    private static AddonDroneEntity drone;
    private static ListTag inventory;
    private static GameType oldMode;
    private static Vec3 oldPosition, flightStart;
    private static float oldYaw, oldPitch;
    private static boolean oldFlying, oldMayfly, oldNoGravity;

    private Pis1DroneWalkthroughFixture() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(Pis1DroneWalkthroughFixture::tick);
    }

    private static Path file(String name) { return FMLPaths.GAMEDIR.get().resolve(name); }

    private static JsonObject read(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536)
            throw new IllegalStateException("Missing, redirected or oversized walkthrough file: " + path.getFileName());
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || !(event.getServer() instanceof DedicatedServer server)
            || !Boolean.getBoolean("frontier.pis1Smoke") || ++poll % 5 != 0) return;
        try {
            if (!begun) {
                Path controlFile = file("pis1-drone-control.json");
                if (!Files.exists(controlFile, LinkOption.NOFOLLOW_LINKS)) return;
                JsonObject control = read(controlFile);
                runId = System.getProperty("frontier.pis1RunId", "");
                if (!runId.equals(control.get("runId").getAsString()) || !"start".equals(control.get("action").getAsString())) return;
                if (Files.exists(file("pis1-drone-walkthrough.json"), LinkOption.NOFOLLOW_LINKS)) { done = true; return; }
                if (!Files.exists(file("pis1-dedicated-checks.json"), LinkOption.NOFOLLOW_LINKS)) return;
                JsonObject lifecycle = read(file("pis1-dedicated-checks.json"));
                if (!runId.equals(lifecycle.get("runId").getAsString()) || !lifecycle.has("completedAt")
                    || !lifecycle.has("passed") || !lifecycle.get("passed").getAsBoolean()) return;
                selectPlayers(server);
                require("explicit control follows completed successful dedicated lifecycle", true);
                begun = true;
                inventory = owner.getInventory().save(new ListTag()); selected = owner.getInventory().selected;
                oldLevel = owner.serverLevel(); oldPosition = owner.position(); oldYaw = owner.getYRot(); oldPitch = owner.getXRot();
                oldMode = owner.gameMode.getGameModeForPlayer(); oldFlying = owner.getAbilities().flying;
                oldMayfly = owner.getAbilities().mayfly; oldNoGravity = owner.isNoGravity();
                REPORT.addProperty("runId", runId); REPORT.addProperty("startedAt", Instant.now().toString());
                REPORT.addProperty("ownerName", owner.getGameProfile().getName()); REPORT.addProperty("ownerUuid", owner.getStringUUID());
                REPORT.addProperty("testSupplies", true); REPORT.addProperty("purchaseProof", false); REPORT.addProperty("researchProof", false);
                REPORT.addProperty("method", "Automated operator reproduction: real client placement/interact/use and native SBW movement packets; not a novice usability study");
                stageIndex = 0; publish(server);
                return;
            }
            selectPlayers(server);
            if (server.getTickCount() - stageStarted > 600) throw new IllegalStateException("Walkthrough stage timeout: " + STAGES[stageIndex]);
            Path ackFile = file("pis1-drone-ack.json");
            if (!Files.exists(ackFile, LinkOption.NOFOLLOW_LINKS)) return;
            JsonObject ack = read(ackFile);
            if (!runId.equals(ack.get("runId").getAsString()) || !token.equals(ack.get("token").getAsString())
                || !STAGES[stageIndex].equals(ack.get("stage").getAsString())
                || !owner.getGameProfile().getName().equals(ack.get("playerName").getAsString())) return;
            require("client acknowledged " + STAGES[stageIndex], ack.get("success").getAsBoolean());
            if (stageIndex < 4) {
                require("actual book entry PNG " + STAGES[stageIndex], ack.has("screenshot") && ack.get("bookEntryObserved").getAsBoolean());
            } else if (stageIndex == 4) {
                var candidates = level.getEntitiesOfClass(AddonDroneEntity.class, new AABB(FLOOR).inflate(16),
                    entity -> MAVIC.equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))) && entity.isAlive());
                if (candidates.isEmpty()) return;
                require("real client item placement creates exactly one Mavic", candidates.size() == 1);
                drone = candidates.get(0);
                require("physical aircraft is near the requested placement", drone.distanceToSqr(Vec3.atCenterOf(PLACE.above())) < 9);
                REPORT.addProperty("droneUuid", drone.getStringUUID());
                selectSlot(1);
            } else if (stageIndex == 5) {
                var held = owner.getMainHandItem(); var tag = held.getTag();
                if (tag == null || !tag.getBoolean("Linked")) return;
                require("real interaction claims the original Mavic for this owner", owner.getUUID().equals(ControlSessions.ownerId(drone)));
                require("real interaction binds the physical pocket controller", held.is(ControlContent.POCKET.get())
                    && tag.hasUUID(ControlSessions.PAIR) && drone.getUUID().equals(tag.getUUID(ControlSessions.PAIR))
                    && drone.getStringUUID().equals(tag.getString("LinkedDrone")) && !tag.getBoolean("Using"));
            } else if (stageIndex == 6) {
                if (!viewing()) return;
                require("real use packet starts an authorized native Mavic session", ControlSessions.authorizedInput(owner, drone));
                flightStart = drone.position();
                REPORT.addProperty("flightStartX", flightStart.x); REPORT.addProperty("flightStartY", flightStart.y); REPORT.addProperty("flightStartZ", flightStart.z);
            } else if (stageIndex == 7) {
                require("client sent actual native SBW movement packets", ack.get("nativePackets").getAsInt() >= 50);
                require("native session remains active during observed flight", viewing() && ControlSessions.authorizedInput(owner, drone));
                double distance = drone.position().distanceTo(flightStart);
                require("the same physical native Mavic really moves more than three blocks", distance > 3 && level.getEntity(drone.getUUID()) == drone);
                require("actual flight camera PNG exists on the client", ack.has("screenshot") && ack.get("viewObserved").getAsBoolean());
                REPORT.addProperty("flightDisplacement", distance); REPORT.addProperty("nativePackets", ack.get("nativePackets").getAsInt());
            } else {
                var tag = owner.getMainHandItem().getTag();
                if (tag == null || tag.getBoolean("Using") || RemoteDroneView.active(owner)) return;
                require("second real use exits while retaining the original durable pair", tag.getBoolean("Linked")
                    && drone.getStringUUID().equals(tag.getString("LinkedDrone")) && level.getEntity(drone.getUUID()) == drone);
            }
            REPORT.add("client-" + STAGES[stageIndex], ack.deepCopy());
            if (++stageIndex == STAGES.length) { finish(true); return; }
            if (stageIndex == 4) preparePlatform(server);
            publish(server);
        } catch (Exception | LinkageError error) {
            REPORT.addProperty("error", error.toString());
            CHECKS.put("walkthrough completed without exception", false);
            error.printStackTrace(); finish(false);
        }
    }

    private static void selectPlayers(DedicatedServer server) {
        if (!Pis1DedicatedFixture.authorized(server)) throw new IllegalStateException("Unmatched, copied, external or non-loopback test profile");
        owner = server.getPlayerList().getPlayerByName(System.getProperty("frontier.pis1OwnerName", "Pis1Owner"));
        foreign = server.getPlayerList().getPlayerByName(System.getProperty("frontier.pis1ForeignName", "Pis1Foreign"));
        if (owner == null || foreign == null || owner == foreign || server.getPlayerList().getPlayerCount() != 2
            || !loopback(owner) || !loopback(foreign)) throw new IllegalStateException("Both exact named loopback clients must remain connected");
    }

    private static boolean loopback(ServerPlayer player) {
        return player.connection.connection.isConnected() && player.connection.connection.getRemoteAddress() instanceof InetSocketAddress address
            && address.getAddress() != null && address.getAddress().isLoopbackAddress();
    }

    private static void preparePlatform(DedicatedServer server) {
        level = server.overworld();
        for (int x = (FLOOR.getX() - 6) >> 4; x <= (FLOOR.getX() + 6) >> 4; x++)
            for (int z = (FLOOR.getZ() - 6) >> 4; z <= (FLOOR.getZ() + 20) >> 4; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (!level.getForcedChunks().contains(chunk)) { level.setChunkForced(x, z, true); FORCED.add(chunk); }
            }
        for (BlockPos pos : BlockPos.betweenClosed(FLOOR.offset(-6, 0, -6), FLOOR.offset(6, 8, 20)))
            if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Drone test platform or airspace is occupied: " + pos);
        for (BlockPos pos : BlockPos.betweenClosed(FLOOR.offset(-6, 0, -6), FLOOR.offset(6, 0, 20))) {
            BlockPos stable = pos.immutable(); level.setBlock(stable, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL); PLATFORM.add(stable);
        }
        owner.closeContainer(); owner.setGameMode(GameType.CREATIVE); owner.setNoGravity(false);
        owner.getAbilities().flying = false; owner.onUpdateAbilities();
        owner.teleportTo(level, FLOOR.getX() + .5, FLOOR.getY() + 1, FLOOR.getZ() + .5, 0, 40);
        ItemStack mavic = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(MAVIC)));
        String denial = ResearchPolicy.denial(owner, mavic);
        require("operator is allowed to deploy the test Mavic without bypassing placement guards", denial.isEmpty());
        owner.getInventory().clearContent(); owner.getInventory().setItem(0, mavic);
        owner.getInventory().setItem(1, new ItemStack(ControlContent.POCKET.get())); supplied = true; selectSlot(0);
    }

    private static void selectSlot(int slot) {
        owner.getInventory().selected = slot; owner.connection.send(new ClientboundSetCarriedItemPacket(slot));
        owner.getInventory().setChanged(); owner.inventoryMenu.broadcastChanges();
    }

    private static boolean viewing() {
        var tag = owner.getMainHandItem().getTag();
        return drone != null && drone.isAlive() && tag != null && tag.getBoolean("Using") && tag.getBoolean("Linked")
            && drone.getStringUUID().equals(tag.getString("LinkedDrone")) && RemoteDroneView.active(owner);
    }

    private static void publish(DedicatedServer server) throws Exception {
        token = UUID.randomUUID().toString(); stageStarted = server.getTickCount();
        JsonObject stage = new JsonObject(); stage.addProperty("runId", runId); stage.addProperty("token", token);
        stage.addProperty("stage", STAGES[stageIndex]); stage.addProperty("ownerName", owner.getGameProfile().getName());
        stage.addProperty("expectedItem", stageIndex == 4 ? MAVIC : "frontier_arcade_vehicles:pocket_controller");
        stage.addProperty("groundX", PLACE.getX()); stage.addProperty("groundY", PLACE.getY()); stage.addProperty("groundZ", PLACE.getZ());
        if (drone != null) stage.addProperty("droneUuid", drone.getStringUUID());
        write(file("pis1-drone-stage.json"), stage);
        System.out.println("PIS1_DRONE_STAGE " + STAGES[stageIndex] + " token=" + token);
    }

    private static void require(String name, boolean ok) {
        CHECKS.merge(name, ok, (before, after) -> before && after);
        if (!ok) throw new IllegalStateException("Drone walkthrough failed: " + name);
    }

    private static void finish(boolean success) {
        if (done) return;
        done = true;
        try {
            if (owner != null && drone != null && drone.isAlive()) {
                if (viewing()) ControlSessions.usePocket(owner, owner.getMainHandItem());
                drone.endRemoteControl(owner); drone.discard();
            }
            if (owner != null && inventory != null) {
                owner.closeContainer(); owner.getInventory().clearContent(); owner.getInventory().load(inventory);
                owner.setGameMode(oldMode); owner.setNoGravity(oldNoGravity); owner.getAbilities().mayfly = oldMayfly;
                owner.getAbilities().flying = oldFlying; owner.onUpdateAbilities();
                owner.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch); selectSlot(selected);
                CHECKS.put("original inventory restored after the isolated operator scenario", owner.getInventory().save(new ListTag()).equals(inventory));
            }
            if (level != null) {
                for (BlockPos pos : PLATFORM) if (level.getBlockState(pos).is(Blocks.STONE)) level.removeBlock(pos, false);
                for (long chunk : FORCED) level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
            }
        } catch (Exception error) { CHECKS.put("test-only fixture cleanup succeeded", false); REPORT.addProperty("cleanupError", error.toString()); }
        REPORT.addProperty("runId", runId); REPORT.addProperty("completedAt", Instant.now().toString());
        REPORT.addProperty("testSuppliesWereIssued", supplied);
        REPORT.addProperty("passed", success && !CHECKS.isEmpty() && CHECKS.values().stream().allMatch(Boolean::booleanValue));
        REPORT.add("checks", new GsonBuilder().create().toJsonTree(CHECKS));
        try { write(file("pis1-drone-walkthrough.json"), REPORT); }
        catch (Exception error) { error.printStackTrace(); }
        System.out.println("PIS1_DRONE_DONE passed=" + REPORT.get("passed"));
    }

    private static void write(Path path, JsonObject data) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".writing");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
