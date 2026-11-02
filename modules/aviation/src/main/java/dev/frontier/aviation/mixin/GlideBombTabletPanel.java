package dev.frontier.aviation.mixin;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.frontier.aviation.weapons.GlideBombRackView;
import dev.frontier.aviation.weapons.PylonOrdnance;
import dev.frontier.aviation.weapons.WeaponLoadout;
import dev.sbw.deepstrike.client.FlightUi;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileItem;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.network.Su24mNetwork;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the right-hand panel of DeepStrike's aircraft tablet, on every aircraft
 * that uses it, with one strike flow for every pylon weapon: pick the pylon and its
 * weapon (< КАБ-500 >, from what that aircraft can carry), load it on the ground,
 * set the target with a right click on the map or by typing X/Z, press ПУСК.
 * DeepStrike's cruise-missile route builder, launch timers and route presets are
 * hidden. The panel is drawn with DeepStrike's own FlightUi; the map, top bar and
 * telemetry are untouched, and all actions use DeepStrike's own Action packets.
 */
@Mixin(targets = "dev.sbw.deepstrike.client.Su24mScreen", remap = false)
public abstract class GlideBombTabletPanel {
    @Unique private static final int PANEL_TOP = 76, PANEL_WIDTH = 326, CONTENT = 302;
    @Unique private static final int ROW_TOP = 96, ROW_STEP = 24, ROW_HEIGHT = 22, ARROW = 22;
    @Unique private static final double LAUNCH_KMH = 40;
    @Unique private static final double KAB_SECTOR_DEGREES = 60;
    @Unique private static final double STORM_SHADOW_SPEED = 1200;

    @Shadow @Final private int vehicle;
    @Shadow @Final private List<RoutePoint>[] draft;
    @Shadow @Final private List<RoutePoint>[] submitted;
    @Shadow @Final private boolean[] dirty;
    @Shadow private Su24mNetwork.Snapshot snapshot;
    @Shadow private int slot;
    @Shadow private int panelX;
    @Shadow private EditBox x;
    @Shadow private EditBox z;

    @Shadow private void send(int op, int index, int mode, int seconds, double value, List<RoutePoint> route, String name) {
        throw new AssertionError();
    }

    @Shadow private void selectSlot(int next) { throw new AssertionError(); }

    @Shadow private void step(int next) { throw new AssertionError(); }

    @Unique private boolean aviation$chromeVisible;
    @Unique private boolean aviation$fieldsCleared;
    @Unique private RoutePoint aviation$target;

    @Unique
    private Entity aviation$plane() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(vehicle);
    }

    /** Every aircraft on this tablet (Su-25, F-16, J-10, DeepStrike's Su-24M) gets the same panel. */
    @Unique
    private boolean aviation$active() {
        return aviation$plane() instanceof Su24mEntity;
    }

    /** Every tablet aircraft offers the same pylon weapons. */
    @Unique
    private List<String> aviation$weapons() {
        return PylonOrdnance.ALL;
    }

    /**
     * Null when the pilot carries a store the server will accept for {@code type},
     * otherwise why Load cannot work. Mirrors the server: КАБ-500 is SBW's medium
     * aerial bomb; Storm Shadow must be DeepStrike-assembled (warhead) and fuelled.
     */
    @Unique
    private String aviation$missingStore(String type) {
        var player = Minecraft.getInstance().player;
        if (player == null) return aviation$t("sync");
        boolean raw = false;
        int kab = PylonOrdnance.kabIndex(type);
        int bombs = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (kab >= 0) {
                if (stack.is(PylonOrdnance.KABS.get(kab).item().get())) bombs += stack.getCount();
            } else if (stack.getItem() instanceof MissileItem item && item.kind() == MissileKind.STORM_SHADOW && !item.isAirframe()) {
                MissileData data = MissileData.fromItemStack(stack);
                if (data != null && data.isFueled() && data.supportsWarhead()) return null;
                raw = true;
            }
        }
        if (kab >= 0) return bombs >= PylonOrdnance.KABS.get(kab).count() ? null : aviation$t("missing_" + type);
        return aviation$t(raw ? "unprepared_storm" : "missing_storm");
    }

    @Unique
    private static boolean aviation$isKab(String type) {
        return PylonOrdnance.kabIndex(type) >= 0;
    }

    @Unique
    private String aviation$chosen(int index) {
        var weapons = aviation$weapons();
        String chosen = PylonOrdnance.clientSelected(vehicle, index);
        return weapons.contains(chosen) ? chosen : weapons.get(0);
    }

    @Unique
    private static String aviation$t(String key, Object... args) {
        return Component.translatable("frontier_aviation.tablet." + key, args).getString();
    }

    @Unique
    private static String aviation$weapon(String type) {
        return aviation$t("weapon." + type);
    }

    @Unique
    private static String aviation$fmt(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    @Unique private int aviation$racks() { return snapshot == null ? 2 : snapshot.racks().size(); }
    @Unique private int aviation$after() { return ROW_TOP + aviation$racks() * ROW_STEP; }
    @Unique private int aviation$loadY() { return aviation$after() + 14; }
    @Unique private int aviation$fieldsY() { return aviation$after() + 64; }
    @Unique private int aviation$fireY() { return aviation$after() + 102; }
    @Unique private int aviation$cannonY() { return aviation$after() + 146; }

    @Unique
    private static boolean aviation$inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Unique
    private Su24mNetwork.Rack aviation$rack(int index) {
        return snapshot == null || index < 0 || index >= snapshot.racks().size() ? null : snapshot.racks().get(index);
    }

    /** What pylon {@code index} holds, or the weapon chosen for it while empty. */
    @Unique
    private String aviation$pylonWeapon(int index) {
        var rack = aviation$rack(index);
        if (rack != null && rack.loaded()) {
            int kab = aviation$plane() instanceof GlideBombRackView view ? view.aviation$kabVariant(index) : -1;
            return kab >= 0 ? PylonOrdnance.KABS.get(kab).id() : PylonOrdnance.STORM_SHADOW;
        }
        return aviation$chosen(index);
    }

    @Unique
    private RoutePoint aviation$parseTarget() {
        try {
            double tx = Double.parseDouble(x.getValue().trim().replace(',', '.'));
            double tz = Double.parseDouble(z.getValue().trim().replace(',', '.'));
            if (!Double.isFinite(tx) || !Double.isFinite(tz) || Math.abs(tx) > 2.9999E7 || Math.abs(tz) > 2.9999E7) return null;
            var level = Minecraft.getInstance().level;
            Entity plane = aviation$plane();
            if (level == null || plane == null) return null;
            // Terrain at the target if this client has that chunk, otherwise the terrain
            // under the aircraft (never the aircraft's own altitude: that reads as "no height").
            int bx = Mth.floor(tx), bz = Mth.floor(tz);
            if (!level.hasChunk(bx >> 4, bz >> 4)) { bx = plane.getBlockX(); bz = plane.getBlockZ(); }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            double ty = ground > level.getMinBuildHeight() ? ground : plane.getY() - 64;
            // The server re-grounds this point (GlideBombPylon); Y only feeds the map marker and range estimate.
            return new RoutePoint(tx, ty, tz, STORM_SHADOW_SPEED);
        } catch (NumberFormatException missing) {
            return null;
        }
    }

    /** Null when ПУСК may be pressed, otherwise the reason shown under the button. */
    @Unique
    private String aviation$fireBlocker() {
        var rack = aviation$rack(slot);
        Entity plane = aviation$plane();
        var player = Minecraft.getInstance().player;
        if (rack == null || plane == null) return aviation$t("sync");
        if (!rack.loaded()) return aviation$t("need_load");
        if (aviation$target == null) return aviation$t("need_target");
        if (player == null || player.getVehicle() != plane) return aviation$t("need_crew");
        if (snapshot.parked() || snapshot.speed() < LAUNCH_KMH) return aviation$t("need_flight", aviation$fmt(LAUNCH_KMH));
        if (aviation$isKab(aviation$pylonWeapon(slot))) {
            double dx = aviation$target.x() - plane.getX(), dz = aviation$target.z() - plane.getZ();
            double range = Math.sqrt(dx * dx + dz * dz + Math.pow(aviation$target.y() - plane.getY(), 2));
            double limit = WeaponLoadout.GLIDE_BOMB.maximumRange();
            // The server refuses anything beyond this, so it is the one КАБ check that blocks.
            if (range > limit) return aviation$t("out_of_range", aviation$fmt(range), aviation$fmt(limit));
        }
        return null;
    }

    /** Advice only, never blocks ПУСК: a КАБ that is short or off the nose may miss. */
    @Unique
    private String aviation$fireWarning() {
        Entity plane = aviation$plane();
        if (plane == null || aviation$target == null || snapshot == null
                || !aviation$isKab(aviation$pylonWeapon(slot))) return null;
        double dx = aviation$target.x() - plane.getX(), dz = aviation$target.z() - plane.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double reach = aviation$kabReach(plane);
        if (horizontal > reach) return aviation$t("short", aviation$fmt(horizontal), aviation$fmt(reach));
        double yaw = Math.toRadians(plane.getYRot());
        if (horizontal > 1) {
            double cos = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / horizontal;
            double off = Math.toDegrees(Math.acos(Mth.clamp(cos, -1, 1)));
            if (off > KAB_SECTOR_DEGREES) return aviation$t("out_of_sector", aviation$fmt(off));
        }
        return null;
    }

    // Horizontal reach (m) of a level release, measured with real bombs in game
    // (KabEnvelopeScenario), lower bounds: rows are release speeds, columns heights above
    // the target. Every КАБ class flies alike; only the blast differs.
    @Unique private static final double[] REACH_SPEEDS = {1.2, 1.8, 3.0}; // blocks/tick (86, 130, 216 km/h)
    @Unique private static final double[] REACH_HEIGHTS = {0, 20, 40, 80, 140};
    @Unique private static final double[][] REACH = {
        {0, 28, 170, 290, 300},
        {0, 40, 250, 410, 600},
        {0, 62, 445, 590, 600}};

    /** Horizontal distance a КАБ released now still reaches: the table above, with a 10% margin. */
    @Unique
    private double aviation$kabReach(Entity plane) {
        double height = Math.max(0, plane.getY() - aviation$target.y());
        double speed = snapshot.speed() / 72.0;
        int s = speed <= REACH_SPEEDS[1] ? 0 : 1;
        double ts = Mth.clamp((speed - REACH_SPEEDS[s]) / (REACH_SPEEDS[s + 1] - REACH_SPEEDS[s]), 0, 1);
        int h = 0;
        while (h < REACH_HEIGHTS.length - 2 && height > REACH_HEIGHTS[h + 1]) h++;
        double th = Mth.clamp((height - REACH_HEIGHTS[h]) / (REACH_HEIGHTS[h + 1] - REACH_HEIGHTS[h]), 0, 1);
        double low = Mth.lerp(th, REACH[s][h], REACH[s][h + 1]);
        double high = Mth.lerp(th, REACH[s + 1][h], REACH[s + 1][h + 1]);
        double reach = Mth.lerp(ts, low, high);
        // Slower than the slowest measured release: the glide shrinks with airspeed.
        if (speed < REACH_SPEEDS[0]) reach *= Math.max(0, speed / REACH_SPEEDS[0]);
        return 0.9 * reach;
    }

    // ---- layout of native widgets ------------------------------------------------

    @Inject(method = "m_7856_", at = @At("TAIL"))
    private void aviation$layoutWidgets(CallbackInfo ci) {
        if (!aviation$active()) return;
        if (!aviation$fieldsCleared) {
            // Native fields default to the pilot's own position: never a target by default.
            aviation$fieldsCleared = true;
            x.setValue("");
            z.setValue("");
        }
        for (var child : ((Screen) (Object) this).children()) {
            if (child instanceof AbstractWidget widget) widget.visible = widget == x || widget == z;
        }
        int p = panelX + 12, y = aviation$fieldsY();
        x.setX(p + 12); x.setY(y); x.setWidth(92);
        z.setX(p + 122); z.setY(y); z.setWidth(88);
    }

    // ---- target sync ------------------------------------------------------------

    @Inject(method = "m_86600_", at = @At("TAIL"))
    private void aviation$syncTarget(CallbackInfo ci) {
        if (!aviation$active()) return;
        RoutePoint target = aviation$parseTarget();
        if (Objects.equals(target, aviation$target)) return;
        aviation$target = target;
        // The native map draws draft[slot] as the route with its last point labelled
        // "Цель"; keep every pylon's draft equal to the one target. dirty stops
        // snapshot acks from replacing it with the server's previous route.
        for (int i = 0; i < draft.length; i++) {
            draft[i] = target == null ? new ArrayList<>() : new ArrayList<>(List.of(target));
            dirty[i] = true;
            submitted[i] = null;
        }
    }

    // A left click on the map appends a route point (and opens the route tab); here
    // it only moves the single target. Native code has already copied the point into
    // the X/Z fields, which the tick sync above reads.
    @Inject(method = "addWaypointAt", at = @At("TAIL"))
    private void aviation$mapClickIsTarget(double mx, double my, CallbackInfo ci) {
        if (aviation$active()) step(0);
    }

    // ---- drawing ----------------------------------------------------------------

    @Inject(method = "drawOverlay", at = @At("HEAD"))
    private void aviation$resetChrome(GuiGraphics g, int mx, int my, float partial, CallbackInfo ci) {
        aviation$chromeVisible = false;
    }

    // The first FlightUi.panel call in drawOverlay is the top bar, drawn only while
    // the tablet chrome is shown (chromeHidden lives in DeepStrike's package-private map base).
    @Inject(method = "drawOverlay", at = @At(value = "INVOKE", ordinal = 0,
            target = "Ldev/sbw/deepstrike/client/FlightUi;panel(Lnet/minecraft/client/gui/GuiGraphics;IIIIII)V"))
    private void aviation$chromeShown(GuiGraphics g, int mx, int my, float partial, CallbackInfo ci) {
        aviation$chromeVisible = true;
    }

    @ModifyConstant(method = "drawOverlay", constant = @Constant(stringValue = "weapons"), remap = false)
    private String aviation$weaponsKey(String original) {
        return aviation$active() ? "weapons_bomb" : original;
    }

    @Inject(method = "drawOverlay", at = @At("TAIL"))
    private void aviation$drawPanel(GuiGraphics g, int mx, int my, float partial, CallbackInfo ci) {
        if (!aviation$chromeVisible || !aviation$active()) return;
        int height = ((Screen) (Object) this).height - 82;
        // Native panel text is still queued and sits at z+1 (FlightUi.label): draw it now,
        // then drop its depth so this opaque panel covers it. Widgets drawn after
        // drawOverlay (the X/Z fields) still land on top.
        g.flush();
        RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        g.fill(panelX, PANEL_TOP, panelX + PANEL_WIDTH, PANEL_TOP + height, 0xFF1A1B19);
        FlightUi.panel(g, panelX, PANEL_TOP, PANEL_WIDTH, height, -182838503, -10854328);
        boolean down = Minecraft.getInstance().mouseHandler.isLeftPressed();
        int p = panelX + 12;

        FlightUi.label(g, aviation$t("pylons"), p, 84, FlightUi.MUTED);
        for (int i = 0; i < aviation$racks(); i++) {
            var rack = aviation$rack(i);
            boolean loaded = rack != null && rack.loaded(), armed = rack != null && rack.mode() >= 0;
            boolean cycle = !loaded && !armed && aviation$weapons().size() > 1;
            String weapon = aviation$weapon(aviation$pylonWeapon(i));
            int y = ROW_TOP + i * ROW_STEP;
            aviation$button(g, mx, my, down, p, y, ARROW, ROW_HEIGHT, "<", cycle, false, FlightUi.Tone.NAVIGATION);
            aviation$button(g, mx, my, down, p + 26, y, CONTENT - 52, ROW_HEIGHT,
                aviation$t("pylon", i + 1, weapon, loaded ? 1 : 0), true, i == slot, FlightUi.Tone.NAVIGATION);
            FlightUi.glyph(g, p + 33, y + 7, FlightUi.ICON_ROCKET, loaded ? FlightUi.GREEN : FlightUi.MUTED);
            aviation$button(g, mx, my, down, p + CONTENT - ARROW, y, ARROW, ROW_HEIGHT, ">", cycle, false, FlightUi.Tone.NAVIGATION);
        }

        var rack = aviation$rack(slot);
        boolean parked = snapshot != null && snapshot.parked();
        boolean loaded = rack != null && rack.loaded(), armed = rack != null && rack.mode() >= 0;
        int after = aviation$after();
        // DeepStrike's own status texts talk about routes ("нужен маршрут"); use this flow's wording.
        String statusKey = "frontier_aviation.tablet.status." + (rack == null ? "" : rack.status());
        String status = rack == null ? aviation$t("sync") : net.minecraft.client.resources.language.I18n.exists(statusKey)
            ? Component.translatable(statusKey).getString() : Component.translatable("su24.status." + rack.status()).getString();
        FlightUi.label(g, FlightUi.clip(aviation$t("selected", slot + 1, status), CONTENT), p, after + 2,
            armed ? FlightUi.AMBER : FlightUi.TEXT);

        int loadY = aviation$loadY();
        String missing = loaded ? null : aviation$missingStore(aviation$chosen(slot));
        aviation$button(g, mx, my, down, p, loadY, 149, 22, aviation$t("load", aviation$weapon(aviation$pylonWeapon(slot))),
            parked && rack != null && !loaded && !armed && missing == null, false, FlightUi.Tone.PRIMARY);
        aviation$button(g, mx, my, down, p + 153, loadY, 149, 22, aviation$t("unload"),
            parked && loaded && !armed, false, FlightUi.Tone.SECONDARY);
        if (!loaded && missing != null) FlightUi.label(g, FlightUi.clip(missing, CONTENT), p, after + 40, FlightUi.AMBER);
        else if (!parked) FlightUi.label(g, aviation$t("parked_only"), p, after + 40, FlightUi.MUTED);

        FlightUi.label(g, aviation$t("target_title"), p, after + 52, FlightUi.TEXT);
        int fieldsY = aviation$fieldsY();
        FlightUi.label(g, "X", p + 2, fieldsY + 6, FlightUi.MUTED);
        FlightUi.label(g, "Z", p + 112, fieldsY + 6, FlightUi.MUTED);
        FlightUi.label(g, aviation$t("y_auto"), p + 216, fieldsY + 6, FlightUi.MUTED);
        Entity plane = aviation$plane();
        String readout = aviation$target == null || plane == null ? aviation$t("target_none")
            : aviation$t("target_set", aviation$fmt(aviation$target.x()), aviation$fmt(aviation$target.z()),
                aviation$fmt(Math.hypot(aviation$target.x() - plane.getX(), aviation$target.z() - plane.getZ())));
        if (aviation$target != null && plane != null && snapshot != null && !snapshot.parked()
                && aviation$isKab(aviation$pylonWeapon(slot)))
            readout += aviation$t("reach", aviation$fmt(aviation$kabReach(plane)));
        FlightUi.label(g, FlightUi.clip(readout, CONTENT), p, after + 90, aviation$target == null ? FlightUi.MUTED : FlightUi.CYAN);

        int fireY = aviation$fireY();
        String blocker = armed ? null : aviation$fireBlocker();
        if (armed) {
            aviation$button(g, mx, my, down, p, fireY, CONTENT, 24, aviation$t("cancel"), true, false, FlightUi.Tone.DANGER);
        } else {
            aviation$button(g, mx, my, down, p, fireY, CONTENT, 24,
                aviation$t("fire", aviation$weapon(aviation$pylonWeapon(slot))), blocker == null, false, FlightUi.Tone.LAUNCH);
        }
        String warning = armed || blocker != null ? null : aviation$fireWarning();
        String hint = armed ? aviation$t("armed") : blocker != null ? blocker : warning != null ? warning : aviation$t("ready");
        FlightUi.label(g, FlightUi.clip(hint, CONTENT), p, after + 130,
            armed || blocker != null || warning != null ? FlightUi.AMBER : FlightUi.GREEN);

        int cannonY = aviation$cannonY();
        int rounds = snapshot == null ? 0 : snapshot.rounds();
        FlightUi.label(g, aviation$t("cannon", rounds), p, cannonY + 6, FlightUi.TEXT);
        aviation$button(g, mx, my, down, p + 150, cannonY, CONTENT - 150, 20, aviation$t("reload"),
            parked && rounds < 500, false, FlightUi.Tone.SECONDARY);
    }

    @Unique
    private static void aviation$button(GuiGraphics g, int mx, int my, boolean down, int x, int y, int w, int h,
                                        String caption, boolean enabled, boolean selected, FlightUi.Tone tone) {
        boolean hover = enabled && aviation$inside(mx, my, x, y, w, h);
        FlightUi.control(g, x, y, w, h, caption, hover, enabled, selected, hover && down, tone);
    }

    // ---- clicks -----------------------------------------------------------------

    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true)
    private void aviation$click(double mx, double my, int button, CallbackInfoReturnable<Boolean> result) {
        if (!aviation$chromeVisible || !aviation$active() || mx < panelX || my < PANEL_TOP) return;
        if (x.isMouseOver(mx, my) || z.isMouseOver(mx, my)) return; // native focuses the field
        var screen = (Screen) (Object) this;
        result.setReturnValue(true);
        screen.setFocused(null);
        if (button != 0) return;
        int p = panelX + 12;
        for (int i = 0; i < aviation$racks(); i++) {
            var rack = aviation$rack(i);
            boolean cycle = rack != null && !rack.loaded() && rack.mode() < 0;
            int y = ROW_TOP + i * ROW_STEP;
            int direction = aviation$inside(mx, my, p, y, ARROW, ROW_HEIGHT) ? -1
                : aviation$inside(mx, my, p + CONTENT - ARROW, y, ARROW, ROW_HEIGHT) ? 1 : 0;
            if (direction != 0 && cycle) {
                var weapons = aviation$weapons();
                int at = weapons.indexOf(aviation$chosen(i));
                PylonOrdnance.setClientSelected(vehicle, i, weapons.get(Math.floorMod(at + direction, weapons.size())));
                if (slot != i) selectSlot(i);
                aviation$clickSound();
                return;
            }
            if (aviation$inside(mx, my, p + 26, y, CONTENT - 52, ROW_HEIGHT)) {
                if (slot != i) selectSlot(i);
                aviation$clickSound();
                return;
            }
        }
        var rack = aviation$rack(slot);
        boolean parked = snapshot != null && snapshot.parked();
        boolean loaded = rack != null && rack.loaded(), armed = rack != null && rack.mode() >= 0;
        int loadY = aviation$loadY();
        if (aviation$inside(mx, my, p, loadY, 149, 22) && parked && rack != null && !loaded && !armed
                && aviation$missingStore(aviation$chosen(slot)) == null) {
            send(Su24mNetwork.LOAD, slot, 0, 0, 0.0, List.of(), aviation$chosen(slot));
            aviation$clickSound();
        } else if (aviation$inside(mx, my, p + 153, loadY, 149, 22) && parked && loaded && !armed) {
            send(Su24mNetwork.UNLOAD, slot, 0, 0, 0.0, List.of(), "");
            aviation$clickSound();
        } else if (aviation$inside(mx, my, p, aviation$fireY(), CONTENT, 24)) {
            if (armed) {
                send(Su24mNetwork.CANCEL, slot, 0, 0, 0.0, List.of(), "");
                aviation$clickSound();
            } else if (aviation$fireBlocker() == null) {
                // PREPARE then ARM "now": DeepStrike's own order gate then releases the
                // store on the next server tick, exactly as for a Storm Shadow launch.
                send(Su24mNetwork.PREPARE, slot, 0, 0, 0.0, List.of(aviation$target), "");
                send(Su24mNetwork.ARM, slot, 0, 0, 0.0, List.of(), "");
                aviation$clickSound();
            }
        } else if (aviation$inside(mx, my, p + 150, aviation$cannonY(), CONTENT - 150, 20)
                && parked && snapshot.rounds() < 500) {
            send(Su24mNetwork.RELOAD, slot, 0, 0, 0.0, List.of(), "");
            aviation$clickSound();
        }
    }

    @Unique
    private static void aviation$clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
