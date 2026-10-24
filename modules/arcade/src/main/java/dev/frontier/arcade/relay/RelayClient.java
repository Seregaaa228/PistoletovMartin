package dev.frontier.arcade.relay;

import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.relay.client.RelayFlightHud;
import dev.frontier.arcade.geranium.GeraniumClient;
import dev.frontier.arcade.geranium.GeraniumNet;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import ru.lavafrai.uncomplicatedfpv.client.renderer.AddonDroneRenderer;

/** Scoped to the owner's relay monitor. Other native drones keep their own HUD and camera. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class RelayClient {
    private static final ResourceLocation MONITOR = new ResourceLocation("superbwarfare", "monitor");
    private static final Method POSITION = findPositionSetter();
    private static boolean cameraAvailable = POSITION != null;
    private static UUID target;
    private static int hops;
    private static long received;

    private static final IKeyConflictContext FLIGHT_CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() { return Minecraft.getInstance().screen == null && currentTarget() != null; }
        @Override public boolean conflicts(IKeyConflictContext other) { return this == other; }
    };
    // New action IDs prevent old saved B/R bindings from overriding the conflict-free defaults.
    public static final KeyMapping TOGGLE_ORBIT = new KeyMapping(
        "key.frontier_arcade_vehicles.relay_flight_mode", FLIGHT_CONTEXT,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, "key.categories.frontier_arcade_vehicles.relay");
    public static final KeyMapping STABILIZE = new KeyMapping(
        "key.frontier_arcade_vehicles.relay_level_flight", FLIGHT_CONTEXT,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.frontier_arcade_vehicles.relay");

    // Keep chat, subtitles, damage effects, debug information and player list available.
    // The body inventory and native quadcopter HUD do not describe this aircraft's state.
    private static final Set<ResourceLocation> BODY_OVERLAYS = Set.of(
        vanilla("hotbar"), vanilla("crosshair"), vanilla("experience_bar"), vanilla("jump_bar"),
        vanilla("item_name"), vanilla("player_health"), vanilla("armor_level"), vanilla("food_level"),
        vanilla("air_level"), vanilla("mount_health"), vanilla("potion_icons"),
        new ResourceLocation("superbwarfare", "superbwarfare_drone_hud"));

    private RelayClient() { }

    private static ResourceLocation vanilla(String path) { return new ResourceLocation("minecraft", path); }

    private static Method findPositionSetter() {
        try {
            return ObfuscationReflectionHelper.findMethod(Camera.class, "m_90584_", double.class, double.class, double.class);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Shared route telemetry retained for every aircraft using the relay network. */
    public static int hopCount(UUID id) {
        return id != null && id.equals(target) && System.nanoTime() - received < 3_000_000_000L ? hops : 0;
    }

    public static void status(UUID id, int count) {
        target = id;
        hops = Math.max(0, count);
        received = System.nanoTime();
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        boolean toggle = false, level = false;
        while (TOGGLE_ORBIT.consumeClick()) toggle = true;
        while (STABILIZE.consumeClick()) level = true;
        Minecraft mc = Minecraft.getInstance();
        RelayEntity relay = currentTarget();
        if (mc.screen != null || relay == null || !relay.isLaunched()) return;
        // Only the authoritative response changes the displayed mode and aircraft attitude.
        if (toggle) GeraniumNet.sendRelayAction(true);
        if (level) GeraniumNet.sendRelayAction(false);
    }

    public static RelayEntity currentTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) return null;
        var held = mc.player.getMainHandItem();
        var tag = held.getTag();
        if (!held.is(ForgeRegistries.ITEMS.getValue(MONITOR)) || tag == null
            || !tag.getBoolean("Using") || !tag.getBoolean("Linked")) return null;
        String id = tag.getString("LinkedDrone");
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof RelayEntity drone && drone.getStringUUID().equals(id)
                && drone.ownedBy(mc.player) && !drone.isRemoved()) return drone;
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        RelayEntity target = currentTarget();
        if (target == null || !cameraAvailable) return;
        float partial = (float) event.getPartialTick();
        Vec3 point = target.nosePosition(partial);
        try {
            POSITION.invoke(event.getCamera(), point.x, point.y, point.z);
            // The nose camera follows the actual airframe, rather than a separate quadcopter gimbal.
            event.setYaw(target.getYaw(partial));
            event.setPitch(target.getBodyPitch(partial));
            event.setRoll(target.getRoll(partial));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            cameraAvailable = false;
        }
    }

    @SubscribeEvent
    public static void overlay(RenderGuiOverlayEvent.Pre event) {
        if (BODY_OVERLAYS.contains(event.getOverlay().id()) && currentTarget() != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        RelayEntity relay = currentTarget();
        if (relay != null) RelayFlightHud.render(event.getGuiGraphics(), event.getPartialTick(), relay, TOGGLE_ORBIT, STABILIZE);
        radioStatus(event, relay != null || GeraniumClient.currentTarget() != null);
    }

    private static void radioStatus(RenderGuiEvent.Post event, boolean flightHud) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null || System.nanoTime() - received > 3_000_000_000L) return;
        var held = mc.player.getMainHandItem();
        var tag = held.getTag();
        if (!held.is(ForgeRegistries.ITEMS.getValue(MONITOR)) || tag == null || !tag.getBoolean("Using")
            || !tag.getBoolean("Linked") || !target.toString().equals(tag.getString("LinkedDrone"))) return;
        String text = hops > 0 ? "СВЯЗЬ ЧЕРЕЗ РЕТРАНСЛЯТОР ×" + hops : "ПРЯМАЯ СВЯЗЬ";
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        int x = (width - mc.font.width(text)) / 2, y = flightHud ? 64 : height - 48;
        event.getGuiGraphics().fill(x - 5, y - 3, x + mc.font.width(text) + 5, y + 11, 0xB010191D);
        event.getGuiGraphics().drawString(mc.font, text, x, y, hops > 0 ? 0xFF9DD5BF : 0xFF9AAEAF, false);
    }

    @Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(RelayContent.TYPE.get(), AddonDroneRenderer<RelayEntity>::new);
        }

        @SubscribeEvent
        public static void keyMappings(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_ORBIT);
            event.register(STABILIZE);
        }
    }
}
