package dev.frontier.arcade.geranium;

import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.client.GeraniumFlightHud;
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

/** Scoped to the owner's Geranium monitor. Other native drones keep their own HUD and camera. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class GeraniumClient {
    private static final ResourceLocation MONITOR = new ResourceLocation("superbwarfare", "monitor");
    private static final Method POSITION = findPositionSetter();
    private static boolean cameraAvailable = POSITION != null;
    private static UUID playerSession;
    private static int lastView = -1;

    private static final IKeyConflictContext FLIGHT_CONTEXT = new IKeyConflictContext() {
        @Override public boolean isActive() { return Minecraft.getInstance().screen == null && currentTarget() != null; }
        @Override public boolean conflicts(IKeyConflictContext other) { return this == other; }
    };
    // New action IDs prevent old saved B/R bindings from overriding the conflict-free defaults.
    public static final KeyMapping STABILIZE = new KeyMapping(
        "key.frontier_arcade_vehicles.geranium_level_flight", FLIGHT_CONTEXT,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.frontier_arcade_vehicles.geranium");

    // Keep chat, subtitles, damage effects, debug information and player list available.
    // The body inventory and native quadcopter HUD do not describe this aircraft's state.
    private static final Set<ResourceLocation> BODY_OVERLAYS = Set.of(
        vanilla("hotbar"), vanilla("crosshair"), vanilla("experience_bar"), vanilla("jump_bar"),
        vanilla("item_name"), vanilla("player_health"), vanilla("armor_level"), vanilla("food_level"),
        vanilla("air_level"), vanilla("mount_health"), vanilla("potion_icons"),
        new ResourceLocation("superbwarfare", "superbwarfare_drone_hud"));

    private GeraniumClient() { }

    private static ResourceLocation vanilla(String path) { return new ResourceLocation("minecraft", path); }

    private static Method findPositionSetter() {
        try {
            return ObfuscationReflectionHelper.findMethod(Camera.class, "m_90584_", double.class, double.class, double.class);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            playerSession = null;
            lastView = -1;
            while (STABILIZE.consumeClick()) { /* Never carry a menu click into the next flight. */ }
            return;
        }
        int view = mc.options.renderDistance().get();
        if (!mc.player.getUUID().equals(playerSession) || view != lastView) {
            playerSession = mc.player.getUUID();
            lastView = view;
            GeraniumNet.sendView(view);
        }
        boolean requested = false;
        while (STABILIZE.consumeClick()) requested = true;
        GeraniumEntity target = currentTarget();
        if (requested && mc.screen == null && target != null && target.isLaunched()) GeraniumNet.sendStabilize();
    }

    public static GeraniumEntity currentTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) return null;
        var held = mc.player.getMainHandItem();
        var tag = held.getTag();
        if (!held.is(ForgeRegistries.ITEMS.getValue(MONITOR)) || tag == null
            || !tag.getBoolean("Using") || !tag.getBoolean("Linked")) return null;
        String id = tag.getString("LinkedDrone");
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof GeraniumEntity drone && drone.getStringUUID().equals(id)
                && drone.ownedBy(mc.player) && !drone.isRemoved()) return drone;
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        GeraniumEntity target = currentTarget();
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
        if (mc.options.hideGui || mc.screen != null) return;
        GeraniumEntity target = currentTarget();
        if (target != null) GeraniumFlightHud.render(event.getGuiGraphics(), event.getPartialTick(), target, STABILIZE);
    }

    @Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(GeraniumContent.TYPE.get(), AddonDroneRenderer<GeraniumEntity>::new);
        }

        @SubscribeEvent
        public static void keyMappings(RegisterKeyMappingsEvent event) { event.register(STABILIZE); }
    }
}
