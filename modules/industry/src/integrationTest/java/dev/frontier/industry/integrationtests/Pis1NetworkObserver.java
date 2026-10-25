package dev.frontier.industry.integrationtests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.industry.WorkerEntity;
import dev.frontier.industry.repair.PressWorkstationEntity;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.phys.BlockHitResult;
import snownee.jade.api.BlockAccessor;
import snownee.jade.impl.ObjectDataCenter;

/** Opt-in loopback-only observer. Reads real client caches; never creates entity or Jade data. */
final class Pis1NetworkObserver {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static long lastSequence;
    private static int pollTicks, ticks, observationTicks, freshPayloads, stableTicks;
    private static boolean initialized, readyAnnounced, capturing, connecting;
    private static JsonObject command, evidence;
    private static Path directory;
    private static CompoundTag initialPayload, previousPayload;
    private static String waitReason = "Waiting for command", lastRejected = "";

    private Pis1NetworkObserver() {}

    static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("frontier.pis1Smoke") || System.getProperty("frontier.pis1Server", "").isBlank()) return;
        try {
            if (!initialized) {
                directory = Pis1SmokeClient.matchingProfile(mc);
                Pis1SmokeClient.loopbackServer(System.getProperty("frontier.pis1Server"));
                initialized = true;
            }
            if (!readyAnnounced && mc.player != null && mc.level != null && mc.getOverlay() == null) {
                requireConnection(mc); readyAnnounced = true;
                System.setProperty("frontier.pis1ClientReady", "true");
                System.out.println("PIS1_CLIENT_READY player=" + mc.player.getGameProfile().getName());
            }
            if (++pollTicks % 5 == 0) readCommand(mc);
            if (command == null || capturing) return;
            ticks++;
            String action = text(command, "action", "observe");
            if (action.equals("idle")) { finish(mc, true, "Idle", ""); return; }
            if (action.equals("stop")) { finish(mc, true, "Client stopped by guarded test command", ""); mc.stop(); return; }
            if (action.equals("disconnect")) {
                if (ticks == 1) {
                    requireConnection(mc);
                    mc.level.disconnect(); mc.clearLevel(new TitleScreen());
                }
                if (mc.getConnection() == null && mc.level == null) finish(mc, true, "Disconnected", "");
                return;
            }
            if (action.equals("reconnect")) {
                if (!connecting) {
                    if (mc.getConnection() != null || mc.level != null) throw new IllegalStateException("Reconnect requires a disconnected client");
                    Pis1SmokeClient.connectNetwork(mc); connecting = true;
                }
                if (mc.player != null && mc.level != null && mc.getOverlay() == null) {
                    requireConnection(mc); finish(mc, true, "Reconnected", "");
                } else timeout(mc);
                return;
            }
            if (mc.player == null || mc.level == null || mc.getOverlay() != null) { waitReason = "Client world is not ready"; timeout(mc); return; }
            requireConnection(mc);
            observationTicks++;
            String kind = text(command, "kind", "jade");
            if (observationTicks == 1) {
                int scale = integer(command, "guiScale", 2);
                mc.options.guiScale().set(scale); mc.resizeDisplay();
                if (kind.equals("guide") || kind.equals("production")) Pis1SmokeClient.beginNetworkUi();
                else { mc.setScreen(null); mc.mouseHandler.releaseMouse(); }
            }
            boolean valid;
            if (kind.equals("guide") || kind.equals("production")) {
                evidence = Pis1SmokeClient.networkUi(mc, kind, observationTicks);
                valid = evidence != null;
                waitReason = "Waiting for actual Operations response and selected " + kind;
            } else {
                aim(mc, position(command, "target"));
                valid = kind.equals("jade") ? observeJade(mc) : observeWorld(mc);
            }
            stableTicks = valid ? stableTicks + 1 : 0;
            if (valid && observationTicks >= integer(command, "settleTicks", 40) && stableTicks >= 8) {
                if (bool(command, "capture", true)) capture(mc);
                else finish(mc, true, "Observed", "");
            } else timeout(mc);
        } catch (Exception error) {
            if (command != null) {
                try { finish(mc, false, error.toString(), ""); }
                catch (Exception writeError) { System.err.println("PIS1_NETWORK_ACK_FAIL " + writeError); }
            } else if (!error.toString().equals(lastRejected)) {
                lastRejected = error.toString(); System.err.println("PIS1_NETWORK_REJECT " + error);
            }
        }
    }

    private static void readCommand(Minecraft mc) throws Exception {
        if (command != null || capturing) return;
        Path path = directory.resolve("pis1-network-control.json");
        if (!Files.isRegularFile(path)) return;
        if (Files.size(path) > 32768) throw new IllegalArgumentException("Oversized network control file");
        JsonObject next = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!System.getProperty("frontier.pis1RunId").equals(text(next, "runId", ""))) throw new IllegalArgumentException("Network control runId does not match client profile");
        long sequence = next.get("sequence").getAsLong();
        if (sequence <= lastSequence) return;
        if (sequence < 1) throw new IllegalArgumentException("Sequence must be positive");
        Pis1SmokeClient.matchingProfile(mc);
        String role = text(next, "role", "");
        String name = role.equals("owner") ? System.getProperty("frontier.pis1OwnerName", "Pis1Owner")
            : role.equals("foreign") ? System.getProperty("frontier.pis1ForeignName", "Pis1Foreign") : "";
        if (name.isEmpty() || !name.equals(mc.getUser().getName())) throw new IllegalArgumentException("Role does not match actual client username");
        String action = text(next, "action", "observe");
        if (!action.matches("observe|disconnect|reconnect|idle|stop")) throw new IllegalArgumentException("Unknown network action");
        if (!text(next, "phase", "").matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid observation phase");
        String kind = text(next, "kind", "jade");
        if (!kind.matches("jade|world|guide|production")) throw new IllegalArgumentException("Unknown observation kind");
        int scale = integer(next, "guiScale", 2), settle = integer(next, "settleTicks", 40), timeout = integer(next, "timeoutTicks", 400);
        if ((scale != 0 && scale != 2 && scale != 3) || settle < 8 || settle > 1200 || timeout <= settle || timeout > 2400)
            throw new IllegalArgumentException("Invalid observation timing or GUI scale");
        if (action.equals("observe") && (kind.equals("jade") || kind.equals("world"))) position(next, "target");
        command = next.deepCopy(); lastSequence = sequence;
        ticks = observationTicks = freshPayloads = stableTicks = 0; capturing = connecting = false;
        evidence = new JsonObject(); waitReason = "Waiting for a fresh client observation";
        initialPayload = previousPayload = ObjectDataCenter.getServerData();
        writeResult(mc, false, false, "Observing", "");
        System.out.println("PIS1_NETWORK_COMMAND " + sequence + " " + role + " " + action + " " + text(next, "phase", ""));
    }

    private static void requireConnection(Minecraft mc) {
        if (mc.hasSingleplayerServer() || mc.getConnection() == null || mc.player == null || mc.level == null)
            throw new IllegalStateException("Observer requires a real dedicated client connection");
        var connection = mc.getConnection().getConnection();
        var expected = Pis1SmokeClient.loopbackServer(System.getProperty("frontier.pis1Server"));
        if (connection.isMemoryConnection() || !(connection.getRemoteAddress() instanceof InetSocketAddress peer)
                || peer.getAddress() == null || !peer.getAddress().isLoopbackAddress() || peer.getPort() != expected.getPort())
            throw new IllegalStateException("Actual server socket is not the guarded loopback endpoint");
        if (command == null) {
            String name = mc.player.getGameProfile().getName();
            if (!name.equals(System.getProperty("frontier.pis1OwnerName", "Pis1Owner"))
                    && !name.equals(System.getProperty("frontier.pis1ForeignName", "Pis1Foreign")))
                throw new IllegalStateException("Connected username is not a dedicated fixture participant");
            return;
        }
        String role = text(command, "role", "");
        String expectedName = System.getProperty(role.equals("owner") ? "frontier.pis1OwnerName" : "frontier.pis1ForeignName",
            role.equals("owner") ? "Pis1Owner" : "Pis1Foreign");
        if (!mc.player.getGameProfile().getName().equals(expectedName)) throw new IllegalStateException("Connected player does not match role");
    }

    private static boolean observeJade(Minecraft mc) {
        BlockPos target = position(command, "target");
        evidence = new JsonObject(); evidence.add("target", command.get("target").deepCopy());
        if (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(target)) { waitReason = "Actual crosshair does not hit target"; return false; }
        if (!(ObjectDataCenter.get() instanceof BlockAccessor accessor) || !accessor.getPosition().equals(target)) { waitReason = "Actual Jade accessor does not match target"; return false; }
        CompoundTag wire = ObjectDataCenter.getServerData();
        if (!ObjectDataCenter.serverConnected || wire == null || !accessor.verifyData(wire)) { waitReason = "No verified Jade server cache"; return false; }
        if (wire != initialPayload && wire != previousPayload) { freshPayloads++; previousPayload = wire; }
        evidence.addProperty("wireSnbt", wire.toString());
        evidence.addProperty("wireUtf8Bytes", wire.toString().getBytes(StandardCharsets.UTF_8).length);
        evidence.addProperty("freshPayloadsSinceCommand", freshPayloads);
        evidence.addProperty("jadeLastRequestMillis", ObjectDataCenter.timeLastUpdate);
        evidence.addProperty("accessorVerified", true);
        evidence.addProperty("accessorX", accessor.getPosition().getX());
        evidence.addProperty("accessorY", accessor.getPosition().getY());
        evidence.addProperty("accessorZ", accessor.getPosition().getZ());
        CompoundTag displayed = accessor.getServerData();
        evidence.addProperty("displayedSnbt", displayed.toString());
        boolean foreign = text(command, "role", "").equals("foreign");
        if (foreign && (hasPrivateData(wire) || hasPrivateData(displayed)))
            throw new IllegalStateException("Verified foreign Jade target " + target
                + " disclosed private Industry or generic energy keys; raw wire/displayed SNBT retained in evidence");
        if (freshPayloads < 2) { waitReason = "Waiting for two new actual Jade reply cache instances"; return false; }
        if (foreign) {
            waitReason = "Foreign data is scrubbed";
            return true;
        }
        if (!wire.contains("FrontierIndustry", 10)) { waitReason = "Owner has no actual FrontierIndustry payload"; return false; }
        CompoundTag state = wire.getCompound("FrontierIndustry");
        if (!state.contains("powered", 1) || !state.contains("energy", 3) || state.getString("status").isBlank()) { waitReason = "Owner payload is incomplete"; return false; }
        boolean expected = command.has("expectedPowered") ? command.get("expectedPowered").getAsBoolean() : !text(command, "phase", "").equals("cut");
        evidence.addProperty("powered", state.getBoolean("powered")); evidence.addProperty("expectedPowered", expected);
        waitReason = "Waiting for owner powered state " + expected;
        return state.getBoolean("powered") == expected && displayed.contains("FrontierIndustry", 10)
            && displayed.getCompound("FrontierIndustry").getBoolean("powered") == expected;
    }

    private static boolean hasPrivateData(CompoundTag data) {
        return data.contains("FrontierIndustry") || data.contains("JadeEnergyStorage") || data.contains("JadeEnergyStorageUid");
    }

    @SuppressWarnings("unchecked")
    private static boolean observeWorld(Minecraft mc) throws ReflectiveOperationException {
        evidence = new JsonObject();
        if (command.has("workerId")) {
            UUID id = UUID.fromString(command.get("workerId").getAsString());
            WorkerEntity worker = null;
            for (var entity : mc.level.entitiesForRendering()) if (entity instanceof WorkerEntity candidate && candidate.getUUID().equals(id)) { worker = candidate; break; }
            if (worker == null) { waitReason = "Worker is not tracked by this real client"; return false; }
            var field = WorkerEntity.class.getDeclaredField("WORK_ANIMATION"); field.setAccessible(true);
            int animation = worker.getEntityData().get((EntityDataAccessor<Integer>)field.get(null));
            evidence.addProperty("workerId", id.toString()); evidence.addProperty("workerAnimation", animation);
            if (command.has("expectedWorkerAnimation") && animation != command.get("expectedWorkerAnimation").getAsInt()) { waitReason = "Worker synched animation differs"; return false; }
        }
        if (command.has("pressPos")) {
            if (!(mc.level.getBlockEntity(position(command, "pressPos")) instanceof PressWorkstationEntity press)) { waitReason = "Press is not loaded on this client"; return false; }
            evidence.addProperty("pressOperating", press.operating()); evidence.add("pressPos", command.get("pressPos").deepCopy());
            if (command.has("expectedPressOperating") && press.operating() != command.get("expectedPressOperating").getAsBoolean()) { waitReason = "Press client update flag differs"; return false; }
        }
        evidence.addProperty("actualScreen", mc.screen == null ? "world" : mc.screen.getClass().getName());
        return mc.screen == null;
    }

    private static void aim(Minecraft mc, BlockPos target) {
        var delta = net.minecraft.world.phys.Vec3.atCenterOf(target).subtract(mc.player.getEyePosition());
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float)-Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
    }

    private static void capture(Minecraft mc) {
        capturing = true;
        String filename = "pis1-network-" + System.getProperty("frontier.pis1RunId") + "-" + text(command, "role", "") + "-" + lastSequence + "-" + text(command, "phase", "") + ".png";
        Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(), message -> mc.execute(() -> {
            try {
                boolean written = Files.isRegularFile(directory.resolve("screenshots").resolve(filename));
                finish(mc, written, written ? "Observed and captured" : "Screenshot was not written", written ? "screenshots/" + filename : "");
            } catch (Exception error) { System.err.println("PIS1_NETWORK_SCREENSHOT_FAIL " + error); capturing = false; }
        }));
    }

    private static void timeout(Minecraft mc) throws Exception {
        if (ticks > integer(command, "timeoutTicks", 400)) finish(mc, false, "Timed out: " + waitReason, "");
    }

    private static void finish(Minecraft mc, boolean passed, String status, String screenshot) throws Exception {
        writeResult(mc, passed, true, status, screenshot);
        System.out.println("PIS1_NETWORK_RESULT " + lastSequence + " " + text(command, "role", "") + " " + passed + " " + status);
        command = null; capturing = false; connecting = false;
    }

    private static void writeResult(Minecraft mc, boolean passed, boolean completed, String status, String screenshot) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("runId", System.getProperty("frontier.pis1RunId")); result.addProperty("sequence", lastSequence);
        result.addProperty("phase", text(command, "phase", "")); result.addProperty("role", text(command, "role", ""));
        result.addProperty("kind", text(command, "kind", "jade")); result.addProperty("action", text(command, "action", "observe"));
        result.addProperty("passed", passed); result.addProperty("completed", completed); result.addProperty("status", status);
        result.addProperty("ticks", ticks); result.addProperty("recordedAtMillis", System.currentTimeMillis());
        result.addProperty("username", mc.getUser().getName());
        if (mc.player != null) result.addProperty("playerUuid", mc.player.getUUID().toString());
        if (mc.getConnection() != null) result.addProperty("remoteAddress", mc.getConnection().getConnection().getRemoteAddress().toString());
        result.addProperty("guiScale", mc.options.guiScale().get());
        if (!screenshot.isEmpty()) result.addProperty("screenshot", screenshot);
        if (evidence != null) {
            result.add("evidence", evidence.deepCopy());
            // Stable top-level bridge fields, matching the server lifecycle stage.
            for (String key : new String[]{"workerAnimation", "pressOperating"}) if (evidence.has(key)) result.add(key, evidence.get(key));
        }
        Path temporary = directory.resolve("pis1-network-observation.json.tmp");
        Files.writeString(temporary, JSON.toJson(result));
        Files.move(temporary, directory.resolve("pis1-network-observation.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (completed) Files.writeString(directory.resolve("pis1-network-observations.jsonl"), new Gson().toJson(result) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static BlockPos position(JsonObject value, String key) {
        var pos = value.getAsJsonObject(key);
        return new BlockPos(pos.get("x").getAsInt(), pos.get("y").getAsInt(), pos.get("z").getAsInt());
    }
    private static String text(JsonObject value, String key, String fallback) { return value.has(key) ? value.get(key).getAsString() : fallback; }
    private static int integer(JsonObject value, String key, int fallback) { return value.has(key) ? value.get(key).getAsInt() : fallback; }
    private static boolean bool(JsonObject value, String key, boolean fallback) { return value.has(key) ? value.get(key).getAsBoolean() : fallback; }
}
