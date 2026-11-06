package dev.frontier.arcade.geranium;

import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.mojang.blaze3d.platform.InputConstants;
import dev.frontier.arcade.ArcadeClientTicks;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.client.GeraniumFlightHud;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
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

/**
 * Scoped to the owner's Geranium/Shahed monitor. Other native drones keep their own HUD and camera.
 * During a flight F5 switches between the nose camera and a smoothed chase view behind the
 * aircraft. The vanilla perspective is left as the station session set it (see StationClient),
 * so it never cycles to a front view and comes back unchanged after the flight.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class GeraniumClient {
    private static final ResourceLocation MONITOR = new ResourceLocation("superbwarfare", "monitor");
    private static final Method POSITION = findPositionSetter();
    private static boolean cameraAvailable = POSITION != null;
    private static UUID playerSession;
    private static int lastView = -1;
    private static GeraniumEntity resolvedTarget;
    private static long resolvedTick = Long.MIN_VALUE;
    private static Object resolvedLevel;
    /** Chase view chosen with F5 for the current flight; every flight starts on the nose camera. */
    private static boolean chase;
    private static final double CHASE_DISTANCE = 7.5, CHASE_HEIGHT = 2.4, CHASE_LOOK_AHEAD = 6;
    private static final float CHASE_SMOOTHING = .2f, CHASE_MIN_PITCH = -45, CHASE_MAX_PITCH = 60;
    private static int chaseEntity = -1;
    private static float chaseYaw, chasePitch;
    private static long chaseNanos;

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
            chase = false;
            playerSession = null;
            lastView = -1;
            resolvedTarget = null;
            resolvedLevel = null;
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

    /** Before vanilla's key handling in this tick: F5 belongs to the flight view while flying. */
    @SubscribeEvent
    public static void perspectiveKey(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (currentTarget() == null) {
            if (chase) { chase = false; chaseEntity = -1; }
            return;
        }
        while (mc.options.keyTogglePerspective.consumeClick()) {
            chase = !chase;
            chaseEntity = -1;
        }
    }

    /** True while the pilot has chosen the chase view with F5. */
    public static boolean chaseView() { return chase && currentTarget() != null; }

    /** Resolved once per client tick and shared by camera, HUD, overlays and key contexts. */
    public static GeraniumEntity currentTarget() {
        Minecraft mc = Minecraft.getInstance();
        long tick = ArcadeClientTicks.now();
        if (tick != resolvedTick || mc.level != resolvedLevel) {
            resolvedTarget = resolveTarget(mc);
            resolvedTick = tick;
            resolvedLevel = mc.level;
        } else if (resolvedTarget != null && (resolvedTarget.isRemoved() || mc.player == null || !mc.player.isAlive())) {
            resolvedTarget = null;
        }
        return resolvedTarget;
    }

    private static GeraniumEntity resolveTarget(Minecraft mc) {
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
        // SBW adds its own drone roll to the view in GameRenderer.renderLevel (after this event, on top
        // of event.getRoll()); this view sets the whole roll itself, so the bank is drawn once.
        ClientEventHandler.cameraRoll = 0;
        if (chaseView()) { chase(event, target, partial); return; }
        Vec3 point = target.nosePosition(partial);
        try {
            POSITION.invoke(event.getCamera(), point.x, point.y, point.z);
            // The nose camera follows the actual airframe, rather than a separate quadcopter gimbal.
            event.setYaw(target.getYaw(partial));
            event.setPitch(target.flightPitch(partial));
            event.setRoll(target.getRoll(partial));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            cameraAvailable = false;
        }
    }

    /**
     * Behind and above the aircraft along its smoothed heading and pitch, looking a little ahead of
     * it; pulled in where terrain would block the view. The horizon stays level.
     */
    private static void chase(ViewportEvent.ComputeCameraAngles event, GeraniumEntity target, float partial) {
        long now = System.nanoTime();
        float yaw = target.getYaw(partial), pitch = Mth.clamp(target.flightPitch(partial), CHASE_MIN_PITCH, CHASE_MAX_PITCH);
        if (chaseEntity != target.getId()) {
            chaseEntity = target.getId();
            chaseYaw = yaw;
            chasePitch = pitch;
        } else {
            float seconds = Mth.clamp((now - chaseNanos) / 1e9f, 0, .25f);
            float follow = 1 - (float) Math.exp(-seconds / CHASE_SMOOTHING);
            chaseYaw = Mth.wrapDegrees(chaseYaw + Mth.wrapDegrees(yaw - chaseYaw) * follow);
            chasePitch += (pitch - chasePitch) * follow;
        }
        chaseNanos = now;
        Vec3 centre = target.getPosition(partial).add(0, target.getBbHeight() * .5, 0);
        Vec3 heading = Vec3.directionFromRotation(chasePitch, chaseYaw);
        Vec3 wanted = centre.subtract(heading.scale(CHASE_DISTANCE)).add(0, CHASE_HEIGHT, 0);
        var hit = target.level().clip(new ClipContext(centre, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, target));
        Vec3 eye = hit.getType() == HitResult.Type.MISS ? wanted
            : hit.getLocation().add(centre.subtract(hit.getLocation()).normalize().scale(.3));
        Vec3 look = centre.add(heading.scale(CHASE_LOOK_AHEAD)).subtract(eye);
        try {
            POSITION.invoke(event.getCamera(), eye.x, eye.y, eye.z);
            event.setYaw((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
            event.setPitch((float) Math.toDegrees(Math.atan2(-look.y, Math.sqrt(look.x * look.x + look.z * look.z))));
            event.setRoll(0);
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
            event.registerEntityRenderer(GeraniumContent.SHAHED_TYPE.get(), AddonDroneRenderer<GeraniumEntity>::new);
        }

        @SubscribeEvent
        public static void keyMappings(RegisterKeyMappingsEvent event) { event.register(STABILIZE); }
    }
}
