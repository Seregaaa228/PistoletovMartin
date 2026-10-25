package dev.frontier.nations.map.xaero;

import dev.frontier.nations.map.client.PoliticalMapSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Country territory drawn as a layer over Xaero's maps.
 *
 * <p>No Xaero classes are referenced at compile time and no Xaero code is
 * modified: the layer subscribes only to public Forge client events and reads
 * the already synchronized Recruits state through {@link PoliticalMapSnapshot}.
 * The Xaero world-map camera is probed best-effort via reflection (common
 * camera/scale accessors); when Xaero internals are unavailable the layer
 * falls back to a player-centered projection instead of failing.</p>
 *
 * <p>Minimap note: Xaero's minimap exposes no public overlay hook in the
 * pinned 26.6.0 build, so chunk fills are drawn on the fullscreen M-map while
 * the in-game HUD shows the compact companion legend ({@link XaeroCountryLegend}).
 * Recruits/Living Villages stay the source of truth; this class never writes claims.</p>
 */
public final class XaeroCountryLayer {
    private static final int FILL_ALPHA = 0x66;
    private static final int LABEL_BG = 0xAA152027;
    private static final int LABEL_TEXT = 0xFFE9E8D8;

    private static volatile boolean enabled = true;
    private static volatile PoliticalMapSnapshot snapshot;
    private static int ticks;
    private static boolean initialized;

    private XaeroCountryLayer() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::clientTick);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::onHudPost);
        MinecraftForge.EVENT_BUS.addListener(XaeroCountryLayer::onMapRenderPost);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Flips the T-toggle; returns the new state. */
    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    /**
     * Best-effort Xaero world-map detection by class name, so the closed Xaero
     * JARs stay compile-optional.
     */
    public static boolean isXaeroWorldMapScreen(Screen screen) {
        if (screen == null) return false;
        String name = screen.getClass().getName();
        if (!name.startsWith("xaero.")) return false;
        return name.contains("GuiMap") || name.contains("WorldMap") || name.contains("Map");
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < 20) return;
        ticks = 0;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (!enabled && (minecraft.screen == null || !isXaeroWorldMapScreen(minecraft.screen))) return;
        try {
            snapshot = PoliticalMapSnapshot.capture();
        } catch (RuntimeException ignored) {
            // Client projection only; keep the last frame on transient failure.
        }
    }

    private static void onHudPost(RenderGuiEvent.Post event) {
        if (!enabled) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null || minecraft.level == null) return;
        PoliticalMapSnapshot snap = snapshot;
        if (snap == null || snap.countries.isEmpty()) return;
        try {
            XaeroCountryLegend.renderHud(event.getGuiGraphics(), snap);
        } catch (RuntimeException ignored) {
            // Legend is cosmetic; never break the vanilla HUD.
        }
    }

    private static void onMapRenderPost(ScreenEvent.Render.Post event) {
        if (!enabled) return;
        Screen screen = event.getScreen();
        if (!isXaeroWorldMapScreen(screen)) return;
        PoliticalMapSnapshot snap = snapshot;
        if (snap == null || snap.cells.isEmpty()) return;
        GuiGraphics graphics = event.getGuiGraphics();
        try {
            Camera camera = readCamera(screen);
            if (camera == null) camera = fallbackCamera();
            drawCells(graphics, screen, snap, camera);
            drawLabels(graphics, screen, snap, camera);
            XaeroCountryLegend.renderOnMap(graphics, screen, snap);
        } catch (RuntimeException ignored) {
            // Xaero internals are version-specific; never break their map screen.
        }
    }

    private static void drawCells(GuiGraphics graphics, Screen screen,
                                  PoliticalMapSnapshot snap, Camera camera) {
        double halfW = screen.width / 2.0;
        double halfH = screen.height / 2.0;
        for (PoliticalMapSnapshot.Cell cell : snap.cells.values()) {
            double x0 = halfW + (cell.x * 16.0 - camera.x) * camera.scale;
            double z0 = halfH + (cell.z * 16.0 - camera.z) * camera.scale;
            double x1 = halfW + ((cell.x + 1) * 16.0 - camera.x) * camera.scale;
            double z1 = halfH + ((cell.z + 1) * 16.0 - camera.z) * camera.scale;
            int left = (int) Math.floor(Math.min(x0, x1));
            int right = (int) Math.ceil(Math.max(x0, x1));
            int top = (int) Math.floor(Math.min(z0, z1));
            int bottom = (int) Math.ceil(Math.max(z0, z1));
            if (right < 0 || left > screen.width || bottom < 0 || top > screen.height) continue;
            int rgb = cell.color & 0x00FFFFFF;
            graphics.fill(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom),
                    (FILL_ALPHA << 24) | rgb);
            int edge = 0xFF000000 | rgb;
            if ((cell.edges & 1) != 0) graphics.vLine(left, top, bottom, edge);
            if ((cell.edges & 2) != 0) graphics.vLine(right, top, bottom, edge);
            if ((cell.edges & 4) != 0) graphics.hLine(left, right, top, edge);
            if ((cell.edges & 8) != 0) graphics.hLine(left, right, bottom, edge);
        }
    }

    private static void drawLabels(GuiGraphics graphics, Screen screen,
                                   PoliticalMapSnapshot snap, Camera camera) {
        var font = Minecraft.getInstance().font;
        double halfW = screen.width / 2.0;
        double halfH = screen.height / 2.0;
        for (PoliticalMapSnapshot.Country country : snap.countries) {
            double blockX = country.x() * 16.0 + 8.0;
            double blockZ = country.z() * 16.0 + 8.0;
            int x = (int) Math.round(halfW + (blockX - camera.x) * camera.scale);
            int y = (int) Math.round(halfH + (blockZ - camera.z) * camera.scale);
            if (x < 0 || x > screen.width || y < 0 || y > screen.height) continue;
            String label = font.plainSubstrByWidth(country.name(), 160);
            int width = font.width(label);
            graphics.fill(x - width / 2 - 3, y - 6, x + width / 2 + 3, y + 6, LABEL_BG);
            graphics.drawCenteredString(font, label, x, y - 4, LABEL_TEXT);
        }
    }

    private record Camera(double x, double z, double scale) {}

    private static Camera readCamera(Screen screen) {
        Double x = readNumber(screen,
                new String[]{"getCameraX", "getCamX", "getMapX", "getCenterX", "getMiddleX"});
        if (x == null) x = readFieldNumber(screen,
                new String[]{"cameraX", "camX", "mapX", "centerX", "middleX"});
        Double z = readNumber(screen,
                new String[]{"getCameraZ", "getCamZ", "getMapZ", "getCenterZ", "getMiddleZ"});
        if (z == null) z = readFieldNumber(screen,
                new String[]{"cameraZ", "camZ", "mapZ", "centerZ", "middleZ"});
        if (x == null || z == null) return null;
        Double scale = readNumber(screen,
                new String[]{"getCameraScale", "getMapScale", "getScale", "getZoom"});
        if (scale == null) scale = readFieldNumber(screen,
                new String[]{"cameraScale", "mapScale", "scale", "zoom", "renderScale"});
        double resolved = scale == null ? 1.0 : scale;
        if (Double.isNaN(resolved) || Double.isInfinite(resolved)) return null;
        return new Camera(x, z, Math.min(32.0, Math.max(0.05, resolved)));
    }

    private static Double readNumber(Object target, String[] names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                Class<?> result = method.getReturnType();
                if (result == double.class || result == float.class
                        || result == long.class || result == int.class
                        || Number.class.isAssignableFrom(result)) {
                    Object value = method.invoke(target);
                    if (value instanceof Number number) return number.doubleValue();
                }
            } catch (ReflectiveOperationException | SecurityException | RuntimeException ignored) {
                // Try the next candidate; Xaero internals are version-specific.
            }
        }
        return null;
    }

    private static Double readFieldNumber(Object target, String[] names) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    Class<?> result = field.getType();
                    if (result == double.class || result == float.class
                            || result == long.class || result == int.class
                            || Number.class.isAssignableFrom(result)) {
                        field.setAccessible(true);
                        Object value = field.get(target);
                        if (value instanceof Number number) return number.doubleValue();
                    }
                } catch (ReflectiveOperationException | SecurityException | RuntimeException ignored) {
                    // Try the next candidate; Xaero internals are version-specific.
                }
            }
        }
        return null;
    }

    private static Camera fallbackCamera() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            return new Camera(minecraft.player.getX(), minecraft.player.getZ(), 1.0);
        }
        return new Camera(0.0, 0.0, 1.0);
    }
}
