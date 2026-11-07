package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import com.atsuishio.superbwarfare.tools.VectorToolKt;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.artillery.ArtilleryAim;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Manual laying for a self-propelled gun: the mission's launch direction is drawn as a mark in the sky, the
 * barrel's actual direction as a ring, and the error in degrees. The gunner fires with SBW's own trigger.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class ArtilleryClient {
    /** Barrel within this of the solution counts as laid; the server trues up a shot this close onto the mission. */
    private static final double LAID_DEGREES = ArtilleryAim.TRUE_UP_DEGREES / 2;
    private static final int SOLUTION = 0xFFFFB000, BARREL = 0xFF34E07A, OFF = 0xFFFF4A3D;
    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();
    private static boolean matrices;
    private static int lastGunId = -1, lastAmmo = -1;

    private ArtilleryClient() { }

    /** Called from DeepStrike's console-key tick: in a self-propelled gun X opens the fire-mission tablet. */
    public static void consoleKey(KeyMapping key) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || !(mc.player.getVehicle() instanceof SpArtilleryEntity gun) || gun.isWreck()) return;
        boolean open = false;
        while (key.consumeClick()) open = true;
        if (open) ArtilleryTabletScreen.open(gun);
    }

    private static SpArtilleryEntity seatedGun() {
        var player = Minecraft.getInstance().player;
        return player != null && player.getVehicle() instanceof SpArtilleryEntity gun && !gun.isWreck() ? gun : null;
    }

    /**
     * A round leaving the barrel (the loaded count dropping) while a mission is laid starts the impact countdown
     * at the mission's flight time.
     */
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var gun = seatedGun();
        var gunData = gun == null ? null : gun.getGunData(FireMission.WEAPON);
        int ammo = gunData == null ? -1 : gunData.ammo.get();
        if (gun != null && gun.getId() == lastGunId && ammo >= 0 && ammo < lastAmmo) {
            var solution = FireMission.solution(gun, 1);
            if (solution != null && VectorToolKt.angleTo(gun.getShootVec(FireMission.WEAPON, 1), solution) <= 2 * LAID_DEGREES)
                FireMission.fired(gun.level().getGameTime(), FireMission.flightSeconds(solution, gun));
        }
        lastGunId = gun == null ? -1 : gun.getId();
        lastAmmo = ammo;
    }

    @SubscribeEvent
    public static void level(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        // The pose holds only the camera rotation, so a direction projects to its point at infinity.
        VIEW.set(event.getPoseStack().last().pose());
        PROJECTION.set(event.getProjectionMatrix());
        matrices = true;
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        var gun = seatedGun();
        if (gun == null || mc.options.hideGui || mc.screen != null || FireMission.target(gun) == null) return;
        float partialTick = event.getPartialTick();
        var g = event.getGuiGraphics();
        int width = g.guiWidth(), center = width / 2, top = 46;
        long now = mc.level.getGameTime();
        if (FireMission.impactTick() > now)
            g.drawCenteredString(mc.font, String.format(Locale.ROOT, "%s %.0f %s", ArtilleryTabletScreen.text("impact_in"),
                Math.ceil((FireMission.impactTick() - now - partialTick) / 20.0), ArtilleryTabletScreen.text("seconds")), center, top + 24, SOLUTION);
        var solution = FireMission.solution(gun, partialTick);
        if (solution == null) {
            g.drawCenteredString(mc.font, ArtilleryTabletScreen.text(FireMission.tooClose(gun) ? "too_close" : "out_of_reach"), center, top, OFF);
            return;
        }
        Vec3 barrel = gun.getShootVec(FireMission.WEAPON, partialTick);
        double error = VectorToolKt.angleTo(barrel, solution);
        boolean laid = error <= LAID_DEGREES;
        if (matrices) {
            int[] mark = project(solution, g);
            int[] ring = project(barrel, g);
            if (mark != null) diamond(g, mark[0], mark[1], 7, laid ? BARREL : SOLUTION);
            if (ring != null) box(g, ring[0], ring[1], 4, BARREL);
        }
        double bearing = FireMission.bearingDegrees(solution) - FireMission.bearingDegrees(barrel);
        bearing = (bearing + 540) % 360 - 180;
        double elevation = FireMission.elevationDegrees(solution) - FireMission.elevationDegrees(barrel);
        String line = laid ? String.format(Locale.ROOT, "%s (%.2f°)", ArtilleryTabletScreen.text("laid"), error)
            : String.format(Locale.ROOT, "%s %s %.2f°   %s %s %.2f°", ArtilleryTabletScreen.text("traverse"), bearing < 0 ? "←" : "→",
                Math.abs(bearing), ArtilleryTabletScreen.text("elevate"), elevation < 0 ? "↓" : "↑", Math.abs(elevation));
        g.drawCenteredString(mc.font, line, center, top, laid ? BARREL : SOLUTION);
        String info = String.format(Locale.ROOT, "%s · %.0f %s · %.1f %s",
            ArtilleryTabletScreen.text(FireMission.flat() ? "flat" : "high"),
            Math.hypot(FireMission.target(gun).getX() + 0.5 - gun.getX(), FireMission.target(gun).getZ() + 0.5 - gun.getZ()),
            ArtilleryTabletScreen.text("meters"), FireMission.flightSeconds(solution, gun), ArtilleryTabletScreen.text("seconds"));
        g.drawCenteredString(mc.font, info, center, top + 12, 0xFFB8C4CC);
    }

    /** Screen point of a world direction, or null when it lies behind the camera. */
    private static int[] project(Vec3 direction, GuiGraphics g) {
        var point = new Vector4f((float) direction.x, (float) direction.y, (float) direction.z, 0);
        VIEW.transform(point);
        PROJECTION.transform(point);
        if (point.w <= 1.0E-4f) return null;
        float x = point.x / point.w, y = point.y / point.w;
        if (Math.abs(x) > 1.5f || Math.abs(y) > 1.5f) return null;
        return new int[] {Math.round((x + 1) / 2 * g.guiWidth()), Math.round((1 - y) / 2 * g.guiHeight())};
    }

    private static void diamond(GuiGraphics g, int x, int y, int size, int color) {
        for (int i = -size; i <= size; i++) {
            int half = size - Math.abs(i);
            g.fill(x - half - 1, y + i, x - half + 1, y + i + 1, color);
            g.fill(x + half - 1, y + i, x + half + 1, y + i + 1, color);
        }
        g.fill(x, y, x + 1, y + 1, color);
    }

    private static void box(GuiGraphics g, int x, int y, int size, int color) {
        g.renderOutline(x - size, y - size, 2 * size + 1, 2 * size + 1, color);
    }
}
