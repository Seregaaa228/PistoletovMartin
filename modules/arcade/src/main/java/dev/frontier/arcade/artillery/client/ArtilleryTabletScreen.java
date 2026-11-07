package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.sbw.deepstrike.client.FlightUi;
import dev.sbw.deepstrike.client.TerrainMap;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Fire-mission tablet opened with DeepStrike's own console key (X) in a self-propelled gun. DeepStrike's terrain
 * map and FlightUi draw it; right click sets the target, left drag pans, the wheel zooms.
 */
public final class ArtilleryTabletScreen extends Screen {
    /** Five 16px info rows fit above the buttons, which stay put for clicks. */
    private static final int PANEL_W = 236, PANEL_TOP = 54, BUTTONS = 136, BUTTON_H = 22;
    private final int gunId;
    private final TerrainMap terrain = new TerrainMap();
    /** Distance the map frames on opening, from the gun to the map's edge. */
    private static final double OPENING_REACH = 2500;
    /** GUI pixels per block; set on first layout to frame {@link #OPENING_REACH}. */
    private double zoom;
    private double centerX, centerZ;
    private boolean dragging, moved;
    private double pressX, pressY;

    private ArtilleryTabletScreen(SpArtilleryEntity gun) {
        super(Component.translatable("gui.frontier_arcade_vehicles.artillery.title"));
        gunId = gun.getId();
        var target = FireMission.target(gun);
        centerX = target == null ? gun.getX() : (gun.getX() + target.getX()) / 2;
        centerZ = target == null ? gun.getZ() : (gun.getZ() + target.getZ()) / 2;
    }

    static void open(SpArtilleryEntity gun) {
        Minecraft.getInstance().setScreen(new ArtilleryTabletScreen(gun));
    }

    private SpArtilleryEntity gun() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && mc.level.getEntity(gunId) instanceof SpArtilleryEntity gun
            && mc.player.getVehicle() == gun && !gun.isWreck() ? gun : null;
    }

    private int panelX() { return width - PANEL_W - 14; }
    private boolean inPanel(double x, double y) { return x >= panelX() && y >= PANEL_TOP && y <= PANEL_TOP + panelHeight(); }
    private int panelHeight() { return BUTTONS + 2 * BUTTON_H + 48; }
    /** The map's centre sits in the area left of the panel. */
    private double mapMiddle() { return panelX() / 2.0; }
    private int screenX(double worldX) { return (int) Math.round(mapMiddle() + (worldX - centerX) * zoom); }
    private int screenZ(double worldZ) { return (int) Math.round(height / 2.0 + (worldZ - centerZ) * zoom); }
    private double worldX(double x) { return centerX + (x - mapMiddle()) / zoom; }
    private double worldZ(double y) { return centerZ + (y - height / 2.0) / zoom; }

    @Override
    protected void init() {
        super.init();
        if (zoom <= 0) zoom = Math.max(0.02, (Math.min(mapMiddle(), height / 2.0) - 12) / OPENING_REACH);
    }

    @Override
    public void tick() {
        if (gun() == null) onClose();
    }

    @Override
    public void removed() {
        terrain.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var gun = gun();
        // DeepStrike draws the terrain around the screen centre, which is right of the map's own middle.
        terrain.refresh(worldX(width / 2.0), centerZ, Math.max(1, (int) Math.round(width / zoom)), Math.max(1, (int) Math.round(height / zoom)));
        terrain.render(g, 0, 0, width, height);
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, 1100);
        if (gun != null) {
            drawMap(g, gun, partialTick);
            drawPanel(g, gun, mouseX, mouseY, partialTick);
            if (!inPanel(mouseX, mouseY)) drawCursor(g, gun, mouseX, mouseY);
        }
        FlightUi.label(g, text("hint"), 14, height - 22, FlightUi.MUTED);
        g.pose().popPose();
    }

    private void drawMap(GuiGraphics g, SpArtilleryEntity gun, float partialTick) {
        int gx = screenX(gun.getX()), gz = screenZ(gun.getZ());
        FlightUi.circle(g, gx, gz, (float) (FireMission.maxRange(gun) * zoom), FlightUi.AMBER, true);
        FlightUi.circle(g, gx, gz, (float) (FireMission.MIN_RANGE * zoom), FlightUi.RED, true);
        FlightUi.crosshair(g, gx, gz, 7, FlightUi.CYAN);
        var target = FireMission.target(gun);
        if (target == null) return;
        var solution = FireMission.solution(gun, partialTick);
        int color = solution == null ? FlightUi.RED : FlightUi.GREEN;
        int tx = screenX(target.getX() + 0.5), tz = screenZ(target.getZ() + 0.5);
        FlightUi.line(g, gx, gz, tx, tz, color);
        FlightUi.circle(g, tx, tz, 6, color, false);
        FlightUi.line(g, tx - 9, tz - 9, tx + 9, tz + 9, color);
        FlightUi.line(g, tx - 9, tz + 9, tx + 9, tz - 9, color);
        FlightUi.label(g, meters(distance(gun, target.getX() + 0.5, target.getZ() + 0.5)), tx + 12, tz - 4, color);
    }

    private void drawPanel(GuiGraphics g, SpArtilleryEntity gun, int mouseX, int mouseY, float partialTick) {
        int x = panelX(), y = PANEL_TOP, inner = PANEL_W - 24;
        FlightUi.panel(g, x, y, PANEL_W, panelHeight(), FlightUi.SURFACE, FlightUi.BORDER);
        FlightUi.label(g, text("title"), x + 12, y + 12, FlightUi.TEXT);
        FlightUi.label(g, gun.getDisplayName().getString(), x + 12, y + 26, FlightUi.MUTED);
        int row = y + 46;
        var target = FireMission.target(gun);
        if (target == null) {
            FlightUi.label(g, text("no_target"), x + 12, row, FlightUi.AMBER);
        } else {
            var solution = FireMission.solution(gun, partialTick);
            String height = FireMission.heightKnown() ? Integer.toString(target.getY()) : "≈" + target.getY();
            row = line(g, x, row, text("target"), target.getX() + " / " + height + " / " + target.getZ(), FlightUi.TEXT);
            row = line(g, x, row, text("range"), meters(distance(gun, target.getX() + 0.5, target.getZ() + 0.5)), FlightUi.TEXT);
            if (solution == null) {
                row = line(g, x, row, text("solution"), text(FireMission.tooClose(gun) ? "too_close" : "out_of_reach"), FlightUi.RED);
            } else {
                row = line(g, x, row, text("bearing"), degrees(FireMission.bearingDegrees(solution)), FlightUi.GREEN);
                row = line(g, x, row, text("elevation"), degrees(FireMission.elevationDegrees(solution)), FlightUi.GREEN);
                row = line(g, x, row, text("flight"), String.format(Locale.ROOT, "%.1f %s", FireMission.flightSeconds(solution, gun), text("seconds")), FlightUi.TEXT);
            }
        }
        row = y + BUTTONS;
        int half = (inner - 6) / 2;
        button(g, x + 12, row, half, text("flat"), mouseX, mouseY, FireMission.flat());
        button(g, x + 18 + half, row, half, text("high"), mouseX, mouseY, !FireMission.flat());
        row += BUTTON_H + 8;
        button(g, x + 12, row, inner, text("clear"), mouseX, mouseY, false);
        row += BUTTON_H + 10;
        FlightUi.label(g, FlightUi.clip(text("lay_hint"), inner), x + 12, row, FlightUi.MUTED);
        FlightUi.label(g, FlightUi.clip(text("fire_hint"), inner), x + 12, row + 12, FlightUi.MUTED);
    }

    /** Range under the cursor, coloured by whether map fire can take it. */
    private void drawCursor(GuiGraphics g, SpArtilleryEntity gun, int mouseX, int mouseY) {
        double wx = worldX(mouseX), wz = worldZ(mouseY), range = distance(gun, wx, wz);
        int color = range < FireMission.MIN_RANGE || range > FireMission.maxRange(gun) ? FlightUi.RED : FlightUi.TEXT;
        FlightUi.label(g, (int) Math.floor(wx) + " / " + (int) Math.floor(wz) + " · " + meters(range), 14, height - 36, color);
    }

    private int line(GuiGraphics g, int x, int y, String name, String value, int color) {
        FlightUi.label(g, name, x + 12, y, FlightUi.MUTED);
        FlightUi.label(g, value, x + 100, y, color);
        return y + 16;
    }

    private void button(GuiGraphics g, int x, int y, int w, String caption, int mouseX, int mouseY, boolean selected) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_H;
        FlightUi.control(g, x, y, w, BUTTON_H, caption, hover, true, selected, false);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        var gun = gun();
        if (gun == null) return true;
        if (inPanel(x, y)) {
            if (button == 0) panelClick(x, y);
            return true;
        }
        if (button == 1) {
            FireMission.set(gun, (int) Math.floor(worldX(x)), (int) Math.floor(worldZ(y)));
            return true;
        }
        if (button == 0) {
            dragging = true; moved = false; pressX = x; pressY = y;
            return true;
        }
        return false;
    }

    private void panelClick(double mx, double my) {
        int x = panelX(), inner = PANEL_W - 24, half = (inner - 6) / 2;
        int row = PANEL_TOP + BUTTONS;
        if (my >= row && my < row + BUTTON_H) {
            if (mx >= x + 12 && mx < x + 12 + half) FireMission.flat(true);
            else if (mx >= x + 18 + half && mx < x + 18 + 2 * half) FireMission.flat(false);
        } else if (my >= row + BUTTON_H + 8 && my < row + 2 * BUTTON_H + 8 && mx >= x + 12 && mx < x + 12 + inner) {
            FireMission.clear();
        }
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 0 || !dragging) return false;
        moved |= Math.hypot(x - pressX, y - pressY) > 3;
        if (moved) { centerX -= dx / zoom; centerZ -= dy / zoom; }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) dragging = false;
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (inPanel(x, y)) return true;
        double wx = worldX(x), wz = worldZ(y);
        zoom = Math.max(0.02, Math.min(8, zoom * Math.pow(1.25, delta)));
        centerX += wx - worldX(x);
        centerZ += wz - worldZ(y);
        return true;
    }

    private static double distance(SpArtilleryEntity gun, double x, double z) {
        return Math.hypot(x - gun.getX(), z - gun.getZ());
    }

    private static String meters(double value) {
        return Math.round(value) + " " + text("meters");
    }

    private static String degrees(double value) {
        return String.format(Locale.ROOT, "%.1f°", value);
    }

    static String text(String key) {
        return Component.translatable("gui.frontier_arcade_vehicles.artillery." + key).getString();
    }
}
