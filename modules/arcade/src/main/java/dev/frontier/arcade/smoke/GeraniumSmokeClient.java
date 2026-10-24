package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.network.NetworkRegistry;
import com.atsuishio.superbwarfare.network.message.send.MouseMoveMessage;
import com.atsuishio.superbwarfare.network.message.send.DroneFireMessage;
import com.atsuishio.superbwarfare.network.message.send.VehicleMovementMessage;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.geranium.GeraniumNet;
import java.nio.file.Files;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-authored native SBW control packets and camera observations; never moves an entity. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class GeraniumSmokeClient {
    private static boolean opened, modelPhoto, fpvPhoto, stabilizeSent;
    private static int ticks, finishing, fireSent, originalRenderDistance = -1;
    private GeraniumSmokeClient() { }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("frontier.geraniumSmoke")) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) { keys(mc); return; }
        if (!opened && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            opened = true; mc.options.setCameraType(CameraType.FIRST_PERSON);
            originalRenderDistance = mc.options.renderDistance().get(); mc.options.renderDistance().set(6);
            if (Files.exists(mc.gameDirectory.toPath().resolve("saves/Geranium-Test/level.dat"))) mc.createWorldOpenFlows().loadLevel(mc.screen, "Geranium-Test");
            else mc.createWorldOpenFlows().createFreshLevel("Geranium-Test",
                new LevelSettings("Geranium Test", GameType.SURVIVAL, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(20261024L, true, false), registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if (mc.player == null || mc.level == null) return;
        if (ticks % 20 == 0) GeraniumNet.sendView(6);
        keys(mc);
        if (GeraniumSmoke.flying && GeraniumSmoke.activeUuid != null && !GeraniumSmoke.done) {
            // These are the stock registered C2S payloads used by actual monitor input.
            NetworkRegistry.PACKET_HANDLER.sendToServer(new VehicleMovementMessage(GeraniumSmoke.scriptedInput));
            NetworkRegistry.PACKET_HANDLER.sendToServer(new MouseMoveMessage(GeraniumSmoke.mouseX, GeraniumSmoke.mouseY));
            GeraniumSmoke.packetsSent();
            if (GeraniumSmoke.stabilizeRequest && !stabilizeSent) { stabilizeSent = true; net.minecraft.client.KeyMapping.click(dev.frontier.arcade.geranium.GeraniumClient.STABILIZE.getKey()); }
            if (GeraniumSmoke.phase == 13 && fireSent < GeraniumSmoke.fireRequest) {
                var camera = mc.gameRenderer.getMainCamera().getPosition();
                NetworkRegistry.PACKET_HANDLER.sendToServer(new DroneFireMessage(new org.joml.Vector3f((float)camera.x, (float)camera.y, (float)camera.z)));
                fireSent = GeraniumSmoke.fireRequest; GeraniumSmoke.firePackets++;
            }
        }
        if (++ticks > 1450 && !GeraniumSmoke.done) GeraniumSmoke.timeout();
        if (GeraniumSmoke.done && ++finishing > 45) {
            clearKeys(mc); if (originalRenderDistance >= 0) mc.options.renderDistance().set(originalRenderDistance); mc.stop();
        }
    }
    private static void keys(Minecraft mc) {
        short keys = GeraniumSmoke.flying && !GeraniumSmoke.done ? GeraniumSmoke.scriptedInput : 0;
        mc.options.keyUp.setDown((keys & 4) != 0); mc.options.keyLeft.setDown((keys & 1) != 0);
        mc.options.keyJump.setDown((keys & 16) != 0); mc.options.keyDown.setDown(false);
        mc.options.keyRight.setDown(false); mc.options.keyShift.setDown(false);
    }
    private static void clearKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false); mc.options.keyLeft.setDown(false); mc.options.keyJump.setDown(false);
        mc.options.keyDown.setDown(false); mc.options.keyRight.setDown(false); mc.options.keyShift.setDown(false);
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (!Boolean.getBoolean("frontier.geraniumSmoke") || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || GeraniumSmoke.activeUuid == null) return;
        Minecraft mc = Minecraft.getInstance(); if (mc.level == null || mc.player == null) return;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof GeraniumEntity drone) || !drone.getUUID().equals(GeraniumSmoke.activeUuid)) continue;
            var camera = mc.gameRenderer.getMainCamera().getPosition(); var held = mc.player.getMainHandItem();
            boolean linked = held.hasTag() && held.getTag().getBoolean("Using") && held.getTag().getBoolean("Linked") && drone.getStringUUID().equals(held.getTag().getString("LinkedDrone"));
            GeraniumSmoke.observeClient(camera.distanceTo(drone.nosePosition(event.getPartialTick())), camera.distanceTo(mc.player.getEyePosition()), linked);
            if (!modelPhoto && GeraniumSmoke.phase == 2 && GeraniumSmoke.age >= 8) {
                modelPhoto = true; Screenshot.grab(mc.gameDirectory, "geranium-model.png", mc.getMainRenderTarget(), c -> System.out.println("GERANIUM_SCREENSHOT " + c.getString()));
            }
            break;
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void guiPhoto(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        if (!Boolean.getBoolean("frontier.geraniumSmoke") || fpvPhoto || GeraniumSmoke.phase != 3 || GeraniumSmoke.age < 48) return;
        Minecraft mc = Minecraft.getInstance();
        if (dev.frontier.arcade.geranium.GeraniumClient.currentTarget() == null) return;
        if (!GeraniumSmoke.audioChecked) {
            try {
                Class<?> soundType = Class.forName("ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity$ClientAudio$InteriorSound");
                var constructor = soundType.getDeclaredConstructor(ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity.class);
                constructor.setAccessible(true);
                var nativeSound = (net.minecraft.client.resources.sounds.SoundInstance)constructor.newInstance(dev.frontier.arcade.geranium.GeraniumClient.currentTarget());
                GeraniumSmoke.shahedAudioCorrect = nativeSound.getLocation().equals(ru.lavafrai.uncomplicatedfpv.registry.ModSounds.SHAHED136_ENGINE.getId());
                var originalType = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation("uncomplicatedfpv","fpv_drone"));
                var original = originalType.create(mc.level);
                try {
                    var originalSound = (net.minecraft.client.resources.sounds.SoundInstance)constructor.newInstance(original);
                    GeraniumSmoke.originalAudioUnchanged = originalSound.getLocation().equals(ru.lavafrai.uncomplicatedfpv.registry.ModSounds.FPV_DRONE_ENGINE_INT.getId());
                } finally { original.invalidateCaps(); }
            } catch (Throwable failure) { failure.printStackTrace(); }
            GeraniumSmoke.audioChecked = true;
        }
        fpvPhoto = true; Screenshot.grab(mc.gameDirectory, "geranium-fpv.png", mc.getMainRenderTarget(), c -> System.out.println("GERANIUM_SCREENSHOT " + c.getString()));
    }

}
