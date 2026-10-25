package dev.frontier.industry.integrationtests;

import com.atsuishio.superbwarfare.network.NetworkRegistry;
import com.atsuishio.superbwarfare.network.message.send.VehicleMovementMessage;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;
import vazkii.patchouli.api.PatchouliAPI;
import vazkii.patchouli.client.book.gui.GuiBookEntry;

/** Separate local stage/ack bridge: all gameplay actions cross Minecraft's real client network. */
public final class Pis1DroneWalkthroughClient {
    private static final ResourceLocation BOOK = new ResourceLocation("frontier_industry", "field_guide");
    private static boolean initialized, acted, capturing, complete;
    private static int poll, age, nativePackets, viewSamples;
    private static JsonObject stage;
    private static String token = "", current = "", clientRunId;
    private static int previousScale = -1;

    private Pis1DroneWalkthroughClient() {}

    public static void init() {
        if (initialized || !Boolean.getBoolean("frontier.pis1Smoke") || System.getProperty("frontier.pis1Server", "").isBlank()) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(Pis1DroneWalkthroughClient::tick);
    }

    private static Path file(Minecraft mc, String name) { return mc.gameDirectory.toPath().resolve(name); }

    private static JsonObject read(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 262144)
            throw new IllegalStateException("Redirected, missing or oversized drone client file");
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void tick(TickEvent.ClientTickEvent event) {
        var mc = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            if ("fly".equals(current) && acted && !complete) keys(mc, input());
            return;
        }
        if (!Boolean.getBoolean("frontier.pis1Smoke") || mc.player == null || mc.level == null || mc.gameMode == null || mc.getOverlay() != null) return;
        try {
            if (++poll % 5 == 0 && Files.exists(file(mc, "pis1-drone-stage.json"), LinkOption.NOFOLLOW_LINKS)) {
                JsonObject next = read(file(mc, "pis1-drone-stage.json"));
                if (!next.get("token").getAsString().equals(token)) accept(mc, next);
            }
            if (stage == null || complete) return;
            if (++age > 500) throw new IllegalStateException("Drone client stage timeout: " + current);
            if (current.startsWith("book-")) { book(mc); return; }
            if (age < 10) return; // Allow carried-slot and inventory synchronization before using an item.
            if (!acted) {
                mc.setScreen(null);
                String heldId = String.valueOf(ForgeRegistries.ITEMS.getKey(mc.player.getMainHandItem().getItem()));
                if (!stage.get("expectedItem").getAsString().equals(heldId)) return;
                switch (current) {
                    case "place" -> {
                        BlockPos ground = new BlockPos(stage.get("groundX").getAsInt(), stage.get("groundY").getAsInt(), stage.get("groundZ").getAsInt());
                        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(new Vec3(ground.getX() + .5, ground.getY() + 1, ground.getZ() + .5), Direction.UP, ground, false));
                    }
                    case "bind" -> {
                        Entity target = target(mc);
                        if (target == null) return;
                        var result = mc.gameMode.interactAt(mc.player, target, new EntityHitResult(target, target.position()), InteractionHand.MAIN_HAND);
                        if (!result.consumesAction()) mc.gameMode.interact(mc.player, target, InteractionHand.MAIN_HAND);
                    }
                    case "connect", "exit" -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    case "fly" -> { }
                    default -> throw new IllegalStateException("Unsupported drone client stage " + current);
                }
                acted = true;
            }
            if ("fly".equals(current)) {
                keys(mc, input());
                NetworkRegistry.PACKET_HANDLER.sendToServer(new VehicleMovementMessage(input()));
                nativePackets++;
                if (cameraViewing(mc)) viewSamples++;
                if (age >= 85 && nativePackets >= 65) capture(mc, false);
            } else if (age >= 35) {
                if ("connect".equals(current) && !viewing(mc)) return;
                if ("exit".equals(current) && using(mc)) return;
                acknowledge(mc, true, null, false);
            }
        } catch (Exception | LinkageError error) {
            try { acknowledge(mc, false, error.toString(), false); }
            catch (Exception writeError) { writeError.printStackTrace(); }
            error.printStackTrace();
        }
    }

    private static void accept(Minecraft mc, JsonObject next) throws Exception {
        keys(mc, (short)0);
        JsonObject manifest = read(file(mc, "test-profile.json"));
        clientRunId = System.getProperty("frontier.pis1RunId", "");
        if (clientRunId.isBlank() || !clientRunId.equals(manifest.get("runId").getAsString()) || manifest.get("worldsCopied").getAsBoolean()
            || !next.has("clientRunId") || !clientRunId.equals(next.get("clientRunId").getAsString()))
            throw new IllegalStateException("Drone command must be explicitly bridged to this exact fresh client runId");
        String endpoint = System.getProperty("frontier.pis1Server", "");
        if (!endpoint.matches("(?:localhost|127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}|\\[::1\\]|::1):[0-9]{1,5}"))
            throw new IllegalStateException("Drone walkthrough is restricted to literal loopback test connections");
        if (mc.getCurrentServer() == null || !mc.getCurrentServer().ip.matches("(?:127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}|\\[::1\\]):[0-9]{1,5}"))
            throw new IllegalStateException("Current Minecraft connection is not the explicit loopback test server");
        if (!next.get("ownerName").getAsString().equals(mc.player.getGameProfile().getName())) return;
        UUID.fromString(next.get("runId").getAsString()); UUID.fromString(next.get("token").getAsString());
        String name = next.get("stage").getAsString();
        if (!java.util.Set.of("book-controllers-2", "book-flight-2", "book-controllers-3", "book-flight-3", "place", "bind", "connect", "fly", "exit").contains(name))
            throw new IllegalStateException("Unknown drone stage");
        stage = next; token = next.get("token").getAsString(); current = name;
        age = nativePackets = viewSamples = 0; acted = capturing = complete = false;
        System.out.println("PIS1_DRONE_CLIENT_STAGE " + current + " token=" + token);
    }

    private static void book(Minecraft mc) throws Exception {
        String entry = current.contains("controllers") ? "controllers" : "flight";
        if (!acted) {
            if (previousScale < 0) previousScale = mc.options.guiScale().get();
            mc.options.guiScale().set(current.endsWith("-2") ? 2 : 3); mc.resizeDisplay();
            PatchouliAPI.get().openBookEntry(BOOK, new ResourceLocation("frontier_industry", entry), entry.equals("flight") ? 4 : 1);
            acted = true;
        }
        if (age >= 35 && mc.screen instanceof GuiBookEntry gui && gui.getEntry().getId().equals(new ResourceLocation("frontier_industry", entry))
            && BOOK.equals(PatchouliAPI.get().getOpenBookGui())) capture(mc, true);
    }

    private static Entity target(Minecraft mc) {
        UUID id = UUID.fromString(stage.get("droneUuid").getAsString());
        for (Entity entity : mc.level.entitiesForRendering()) if (id.equals(entity.getUUID())) return entity;
        return null;
    }

    private static boolean using(Minecraft mc) {
        var tag = mc.player.getMainHandItem().getTag();
        return tag != null && tag.getBoolean("Using");
    }

    private static boolean viewing(Minecraft mc) {
        var tag = mc.player.getMainHandItem().getTag();
        return mc.screen == null && tag != null && tag.getBoolean("Using") && tag.getBoolean("Linked")
            && stage.get("droneUuid").getAsString().equals(tag.getString("LinkedDrone")) && target(mc) != null;
    }

    private static boolean cameraViewing(Minecraft mc) {
        if (!viewing(mc)) return false;
        Entity target = target(mc);
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        // Native SBW CameraMixin puts the Mavic lens about 0.2 blocks from its interpolated origin.
        // Tolerate one render/tick of interpolation while excluding the stationary operator's view.
        return camera.distanceTo(target.getPosition(mc.getFrameTime())) < 1.5
            && camera.distanceTo(mc.player.getEyePosition(mc.getFrameTime())) > 3;
    }

    private static short input() { return (short)(age < 20 ? 16 : age < 45 ? 4 : 0); }

    private static void keys(Minecraft mc, short keys) {
        if (mc.options == null) return;
        mc.options.keyJump.setDown((keys & 16) != 0); mc.options.keyUp.setDown((keys & 4) != 0);
        mc.options.keyDown.setDown(false); mc.options.keyLeft.setDown(false); mc.options.keyRight.setDown(false); mc.options.keyShift.setDown(false);
    }

    private static void capture(Minecraft mc, boolean book) {
        if (capturing) return;
        capturing = true; keys(mc, (short)0);
        String capturedToken = token;
        String filename = "pis1-drone-" + current + "-" + token.substring(0, 8) + ".png";
        Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(), message -> mc.execute(() -> {
            if (!capturedToken.equals(token)) return;
            try {
                if (!Files.isRegularFile(file(mc, "screenshots/" + filename), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Drone screenshot was not saved");
                stage.addProperty("capturedScreenshot", "screenshots/" + filename);
                acknowledge(mc, true, null, book);
            } catch (Exception error) {
                try { acknowledge(mc, false, error.toString(), book); }
                catch (Exception writeError) { writeError.printStackTrace(); }
            }
        }));
    }

    private static void acknowledge(Minecraft mc, boolean success, String error, boolean book) throws Exception {
        if (stage == null || complete) return;
        JsonObject ack = new JsonObject(); ack.addProperty("runId", stage.get("runId").getAsString()); ack.addProperty("clientRunId", clientRunId);
        ack.addProperty("token", token); ack.addProperty("stage", current); ack.addProperty("playerName", mc.player.getGameProfile().getName());
        ack.addProperty("success", success); ack.addProperty("completedAt", Instant.now().toString());
        ack.addProperty("bookEntryObserved", book); ack.addProperty("viewObserved", viewSamples >= 10);
        ack.addProperty("guiScale", mc.options.guiScale().get());
        ack.addProperty("nativePackets", nativePackets); ack.addProperty("viewSamples", viewSamples);
        if (stage.has("capturedScreenshot")) ack.add("screenshot", stage.get("capturedScreenshot"));
        if (error != null) ack.addProperty("error", error);
        if (mc.screen != null) ack.addProperty("screenClass", mc.screen.getClass().getName());
        ack.addProperty("cameraX", mc.gameRenderer.getMainCamera().getPosition().x);
        ack.addProperty("cameraY", mc.gameRenderer.getMainCamera().getPosition().y);
        ack.addProperty("cameraZ", mc.gameRenderer.getMainCamera().getPosition().z);
        if (stage.has("droneUuid") && target(mc) != null) {
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            ack.addProperty("cameraDistanceToDrone", camera.distanceTo(target(mc).getPosition(mc.getFrameTime())));
            ack.addProperty("cameraDistanceToOperator", camera.distanceTo(mc.player.getEyePosition(mc.getFrameTime())));
        }
        Path path = file(mc, "pis1-drone-ack.json"), temporary = file(mc, "pis1-drone-ack." + UUID.randomUUID() + ".writing");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(ack) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        complete = true; keys(mc, (short)0);
        if (book) mc.setScreen(null);
        if (("exit".equals(current) || !success) && previousScale >= 0) { mc.options.guiScale().set(previousScale); mc.resizeDisplay(); }
        System.out.println("PIS1_DRONE_CLIENT_ACK " + current + " success=" + success);
    }
}
