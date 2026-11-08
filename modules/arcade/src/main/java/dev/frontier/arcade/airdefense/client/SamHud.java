package dev.frontier.arcade.airdefense.client;

import com.atsuishio.superbwarfare.init.ModKeyMappings;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.airdefense.SamEnvelope;
import dev.frontier.arcade.airdefense.SamNetwork;
import dev.frontier.arcade.airdefense.SamSiteEntity;
import dev.frontier.arcade.airdefense.SamSystem;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/**
 * The crew's view from a SAM site, with two controls: look at a contact and click to engage it;
 * X switches AUTO (the site engages enemies by itself) and MANUAL (it waits for the crew).
 *
 * <p>On screen: a bracket on every radar contact (red enemy, amber unknown, green friend) with
 * kind, range and height; the contact nearest the sight is marked for the click; a scope in the
 * corner turns with the view; a status line with mode, launcher readiness and rounds.</p>
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class SamHud {
    static final int HOSTILE = 0xFFFF4A3D, UNKNOWN = 0xFFFFC23D, FRIEND = 0xFF5CFF7A, TEXT = 0xFFE6F0E6, DIM = 0xFF9FB0A0;
    /** A click engages the contact nearest the sight within this many degrees. */
    private static final double PICK_DEGREES = 7;
    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();
    private static boolean matrices;
    private static int hovered = -1;
    private static long lastSweep;

    private SamHud() { }

    static SamSiteEntity seatedSite() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() instanceof SamSiteEntity site && !site.isWreck() ? site : null;
    }

    /** Called from DeepStrike's console-key tick: in a SAM site X toggles AUTO/MANUAL. */
    public static void consoleKey(KeyMapping key) {
        var mc = Minecraft.getInstance();
        SamSiteEntity site = seatedSite();
        if (mc.screen != null || site == null) return;
        boolean toggle = false;
        while (key.consumeClick()) toggle = true;
        if (toggle) {
            SamNetwork.command(site.getId(), SamNetwork.Command.TOGGLE_MODE, 0);
            // Switching to AUTO starts the search; to MANUAL, ends it.
            SamSounds.ui(SamSounds.id("superbwarfare", site.mode() == SamSiteEntity.AUTO ? "radar_search_end" : "radar_search_start"), 0.6f, 1.0f);
        }
    }

    /** SBW's fire button: engage the contact under the sight. Cancelled so the crew does not swing at the hull. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void mouse(InputEvent.MouseButton.Pre event) {
        var mc = Minecraft.getInstance();
        SamSiteEntity site = seatedSite();
        if (site == null || mc.screen != null || event.getAction() != GLFW.GLFW_PRESS
                || !ModKeyMappings.FIRE.matchesMouse(event.getButton())) return;
        event.setCanceled(true);
        if (hovered >= 0) {
            SamNetwork.command(site.getId(), SamNetwork.Command.DESIGNATE, hovered);
            SamSounds.ui(SamSounds.id("superbwarfare", "missile_locked"), 0.7f, 1.0f);
        }
    }

    @SubscribeEvent
    public static void level(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || seatedSite() == null) return;
        VIEW.set(event.getPoseStack().last().pose());
        PROJECTION.set(event.getProjectionMatrix());
        matrices = true;
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        SamSiteEntity site = seatedSite();
        var picture = SamClientSide.picture;
        if (site == null || mc.options.hideGui || mc.screen != null || mc.level == null) { hovered = -1; return; }
        GuiGraphics g = event.getGuiGraphics();
        float partial = event.getPartialTick();
        long pictureAge = mc.level.getGameTime() - SamClientSide.pictureTick;
        boolean fresh = picture != null && picture.site() == site.getId() && pictureAge >= 0 && pictureAge < 40;
        status(g, site, fresh ? picture : null);
        if (!fresh) { hovered = -1; return; }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 look = Vec3.directionFromRotation(mc.gameRenderer.getMainCamera().getXRot(), mc.gameRenderer.getMainCamera().getYRot());
        double best = Math.toRadians(PICK_DEGREES);
        int pick = -1;
        Vec3 origin = site.getPosition(partial);
        for (var contact : picture.contacts()) {
            Vec3 at = position(contact, origin, partial);
            Vec3 toward = at.subtract(camera);
            double angle = Math.acos(Math.max(-1, Math.min(1, toward.normalize().dot(look))));
            if (angle < best) { best = angle; pick = contact.entityId(); }
        }
        if (pick != hovered && pick >= 0) {
            // A new contact under the sight: a short lock tone, higher when it can be engaged.
            int next = pick;
            boolean inZone = picture.contacts().stream().anyMatch(c -> c.entityId() == next && c.inEnvelope());
            SamSounds.ui(SamSounds.id("superbwarfare", "missile_locking"), 0.45f, inZone ? 1.2f : 0.9f);
        }
        hovered = pick;
        long now = mc.level.getGameTime();
        if (site.mode() == SamSiteEntity.AUTO && now % 80 == 0 && now != lastSweep) {
            lastSweep = now;
            SamSounds.ui(SamSounds.id("superbwarfare", "radar_search_idle"), 0.25f, 1.0f);
        }
        for (var contact : picture.contacts()) bracket(g, contact, position(contact, origin, partial).subtract(camera), picture);
        scope(g, site, picture, mc.gameRenderer.getMainCamera().getYRot());
        hint(g, picture);
        warning(g);
    }

    /** The contact's client entity when it is loaded (smooth), else the radar position. */
    private static Vec3 position(SamNetwork.Contact contact, Vec3 origin, float partial) {
        Entity entity = Minecraft.getInstance().level.getEntity(contact.entityId());
        if (entity != null) return entity.getPosition(partial).add(0, entity.getBbHeight() / 2, 0);
        return origin.add(contact.dx(), contact.dy(), contact.dz());
    }

    static int color(SamNetwork.Contact contact) {
        return contact.relation() == 0 ? HOSTILE : contact.relation() == 2 ? FRIEND : UNKNOWN;
    }

    private static void bracket(GuiGraphics g, SamNetwork.Contact contact, Vec3 toward, SamNetwork.Picture picture) {
        if (!matrices) return;
        int[] p = project(toward, g);
        if (p == null) return;
        var mc = Minecraft.getInstance();
        int color = color(contact);
        double range = toward.length();
        int size = (int) Math.max(5, Math.min(14, 900 / Math.max(1, range)));
        boolean picked = contact.entityId() == hovered, designated = contact.entityId() == picture.designated();
        corners(g, p[0], p[1], size, color);
        if (picked || designated) corners(g, p[0], p[1], size + 4, designated ? 0xFFFFFFFF : color);
        if (contact.engaged() && (mc.level.getGameTime() / 4) % 2 == 0) diamond(g, p[0], p[1], size + 7, color);
        String label = String.format(Locale.ROOT, "%s %.0f м · %d м", kindName(contact.kind()), range, contact.altitude());
        g.drawString(mc.font, label, p[0] + size + 3, p[1] - 4, color, true);
        if (picked) g.drawString(mc.font, Component.translatable(contact.inEnvelope()
            ? "hud.frontier_arcade_vehicles.sam.click" : "hud.frontier_arcade_vehicles.sam.out_of_zone"), p[0] + size + 3, p[1] + 6, TEXT, true);
    }

    static String kindName(byte kind) {
        SamEnvelope.Kind[] all = SamEnvelope.Kind.values();
        String key = all[Math.floorMod(kind, all.length)].name().toLowerCase(Locale.ROOT);
        return Component.translatable("hud.frontier_arcade_vehicles.sam.kind." + key).getString();
    }

    private static void status(GuiGraphics g, SamSiteEntity site, SamNetwork.Picture picture) {
        var mc = Minecraft.getInstance();
        SamSystem system = site.system();
        // Top right: the minimap owns the top-left corner and SBW's compass the top centre.
        int x = g.guiWidth() - 186, y = 8;
        g.fill(x - 4, y - 4, x + 178, y + 14 + 11 * (system.racks.size() + 2), 0x88000000);
        boolean auto = site.mode() == SamSiteEntity.AUTO;
        g.drawString(mc.font, Component.translatable("entity.frontier_arcade_vehicles." + system.id), x, y, TEXT, true);
        String mode = Component.translatable(auto ? "hud.frontier_arcade_vehicles.sam.auto" : "hud.frontier_arcade_vehicles.sam.manual").getString();
        g.drawString(mc.font, mode, x + 174 - mc.font.width(mode), y, auto ? FRIEND : UNKNOWN, true);
        y += 13;
        int deploy = site.deployPercent();
        String state = deploy >= 100 ? Component.translatable("hud.frontier_arcade_vehicles.sam.ready").getString()
            : deploy == 0 && system.deployTicks > 0 ? Component.translatable("hud.frontier_arcade_vehicles.sam.march").getString()
            : Component.translatable("hud.frontier_arcade_vehicles.sam.deploying", deploy).getString();
        int inFlight = picture == null ? site.inFlight() : picture.inFlight();
        g.drawString(mc.font, state + String.format(Locale.ROOT, " · %d/%d ", inFlight, system.channels)
            + Component.translatable("hud.frontier_arcade_vehicles.sam.channels").getString(), x, y, deploy >= 100 ? TEXT : UNKNOWN, true);
        for (int rack = 0; rack < system.racks.size(); rack++) {
            y += 11;
            var spec = system.racks.get(rack);
            int ready = site.ready(rack);
            String name = Component.translatable("hud.frontier_arcade_vehicles.sam.missile." + spec.missile().name().toLowerCase(Locale.ROOT)).getString();
            g.drawString(mc.font, name, x, y, TEXT, true);
            int cell = Math.max(3, Math.min(8, 96 / spec.capacity()));
            for (int i = 0; i < spec.capacity(); i++)
                g.fill(x + 60 + i * (cell + 1), y + 1, x + 60 + i * (cell + 1) + cell, y + 7, i < ready ? FRIEND : 0xFF3A4A3A);
            int reload = picture == null || rack >= picture.reload().length ? 0 : picture.reload()[rack];
            if (ready < spec.capacity() && reload > 0)
                g.drawString(mc.font, reload + "%", x + 162, y, DIM, true);
        }
    }

    private static void scope(GuiGraphics g, SamSiteEntity site, SamNetwork.Picture picture, float viewYaw) {
        var mc = Minecraft.getInstance();
        // Under the status panel, clear of the hand and the hotbar.
        int radius = 52, cx = g.guiWidth() - radius - 14, cy = 30 + 11 * (site.system().racks.size() + 2) + radius;
        SamSystem system = site.system();
        double scale = radius / system.detectRange;
        circle(g, cx, cy, radius, 0xAA2E5A3A);
        circle(g, cx, cy, (int) (system.maxRange * scale), 0x88406A4A);
        circle(g, cx, cy, (int) (system.minRange * scale), 0x55406A4A);
        g.fill(cx, cy - radius, cx + 1, cy - radius + 6, 0xAA9FD0A0);
        double yaw = Math.toRadians(viewYaw);
        for (var contact : picture.contacts()) {
            // View-up scope: rotate so the direction the crew looks is up.
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double ahead = contact.dx() * fx + contact.dz() * fz, right = contact.dx() * -fz + contact.dz() * fx;
            int px = cx + (int) Math.round(right * scale), py = cy - (int) Math.round(ahead * scale);
            int color = color(contact);
            g.fill(px - 2, py - 2, px + 2, py + 2, color);
            if (contact.entityId() == picture.designated() || contact.entityId() == hovered) {
                g.fill(px - 4, py - 4, px + 4, py - 3, 0xFFFFFFFF);
                g.fill(px - 4, py + 3, px + 4, py + 4, 0xFFFFFFFF);
            }
        }
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, TEXT);
        String range = String.format(Locale.ROOT, "%.0f м", system.detectRange);
        g.drawString(mc.font, range, cx - mc.font.width(range) / 2, cy + radius + 3, DIM, true);
    }

    private static void hint(GuiGraphics g, SamNetwork.Picture picture) {
        var mc = Minecraft.getInstance();
        Component line = Component.translatable("hud.frontier_arcade_vehicles.sam.hint");
        g.drawCenteredString(mc.font, line, g.guiWidth() / 2, g.guiHeight() - 72, DIM);
    }

    /** Shown to a pilot a site has launched at (also outside a SAM site). */
    static void warning(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        var warning = SamClientSide.warning;
        if (warning == null || mc.level == null) return;
        long age = mc.level.getGameTime() - SamClientSide.warningTick;
        // A warning from another world (game time went backwards) is gone.
        if (age < 0) { SamClientSide.warning = null; return; }
        if ((age > 100 && !SamSounds.anyInbound()) || (age / 5) % 2 == 1) return;
        float relative = net.minecraft.util.Mth.wrapDegrees(warning.bearing() - mc.player.getYRot());
        String clock = String.format(Locale.ROOT, "%s · %+.0f° · %d м",
            Component.translatable("hud.frontier_arcade_vehicles.sam.launch_warning").getString(), relative, warning.distance());
        g.drawCenteredString(mc.font, clock, g.guiWidth() / 2, 60, HOSTILE);
    }

    @SubscribeEvent
    public static void pilotWarning(RenderGuiEvent.Post event) {
        if (seatedSite() == null && !Minecraft.getInstance().options.hideGui) warning(event.getGuiGraphics());
    }

    /** Screen point of a camera-relative direction, or null when behind the camera. */
    private static int[] project(Vec3 direction, GuiGraphics g) {
        var point = new Vector4f((float) direction.x, (float) direction.y, (float) direction.z, 0);
        VIEW.transform(point);
        PROJECTION.transform(point);
        if (point.w <= 1.0E-4f) return null;
        float x = point.x / point.w, y = point.y / point.w;
        if (Math.abs(x) > 1.2f || Math.abs(y) > 1.2f) return null;
        return new int[] {Math.round((x + 1) / 2 * g.guiWidth()), Math.round((1 - y) / 2 * g.guiHeight())};
    }

    private static void corners(GuiGraphics g, int x, int y, int s, int color) {
        int l = Math.max(2, s / 2);
        g.fill(x - s, y - s, x - s + l, y - s + 1, color); g.fill(x - s, y - s, x - s + 1, y - s + l, color);
        g.fill(x + s - l, y - s, x + s, y - s + 1, color); g.fill(x + s - 1, y - s, x + s, y - s + l, color);
        g.fill(x - s, y + s - 1, x - s + l, y + s, color); g.fill(x - s, y + s - l, x - s + 1, y + s, color);
        g.fill(x + s - l, y + s - 1, x + s, y + s, color); g.fill(x + s - 1, y + s - l, x + s, y + s, color);
    }

    private static void diamond(GuiGraphics g, int x, int y, int size, int color) {
        for (int i = -size; i <= size; i++) {
            int half = size - Math.abs(i);
            g.fill(x - half, y + i, x - half + 1, y + i + 1, color);
            g.fill(x + half, y + i, x + half + 1, y + i + 1, color);
        }
    }

    private static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        if (r <= 0) return;
        int steps = Math.max(24, r);
        for (int i = 0; i < steps; i++) {
            double a = i * Math.PI * 2 / steps;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
