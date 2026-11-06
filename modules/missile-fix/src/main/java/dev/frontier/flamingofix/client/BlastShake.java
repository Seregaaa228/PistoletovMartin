package dev.frontier.flamingofix.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * Camera shake from a missile blast near the player. It arrives with the shock wave (~340 m/s),
 * weakens with distance in blast radii and fades out quadratically; the vanilla screen effect
 * scale (the accessibility slider) scales it and turns it off. DeepStrike's own small shake stays.
 */
public final class BlastShake {
    /** @param start game tick the shock reaches the camera; @param amplitude degrees */
    private record Shake(double start, float amplitude, float duration, float phase) {}
    private static final List<Shake> ACTIVE = new ArrayList<>();
    private static final int MAX = 6;
    private static ClientLevel world;

    public static void add(float radius, double distanceSqr, long gameTime, long seed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(radius > 0)) return;
        if (world != mc.level) { world = mc.level; ACTIVE.clear(); }
        double distance = Math.sqrt(distanceSqr);
        float amplitude = (float) Mth.clamp(radius / (1 + distance / (radius * 3)) * 0.12, 0, 6);
        if (amplitude < 0.05f) return;
        if (ACTIVE.size() >= MAX) ACTIVE.remove(0);
        ACTIVE.add(new Shake(gameTime + distance / 17, amplitude, 12 + radius * 0.6f, (seed & 1023) * 0.1f));
    }

    public static void apply(ViewportEvent.ComputeCameraAngles event) {
        if (ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != world) { ACTIVE.clear(); return; }
        double now = mc.level.getGameTime() + event.getPartialTick();
        ACTIVE.removeIf(s -> now > s.start + s.duration);
        float scale = mc.options.screenEffectScale().get().floatValue();
        if (scale <= 0) return;
        double pitch = 0, yaw = 0, roll = 0;
        for (Shake s : ACTIVE) {
            double age = now - s.start;
            if (age < 0) continue;
            double fade = 1 - age / s.duration, a = s.amplitude * fade * fade * scale, x = age + s.phase;
            // Incommensurate frequencies (~7-17 Hz) so the shake never settles into a visible wobble.
            pitch += a * (Math.sin(x * 2.9) * 0.6 + Math.sin(x * 5.3) * 0.4);
            yaw += a * (Math.cos(x * 2.3) * 0.5 + Math.sin(x * 4.7) * 0.3);
            roll += a * Math.sin(x * 3.7) * 0.45;
        }
        if (pitch == 0 && yaw == 0 && roll == 0) return;
        event.setPitch(event.getPitch() + (float) pitch);
        event.setYaw(event.getYaw() + (float) yaw);
        event.setRoll(event.getRoll() + (float) roll);
    }

    private BlastShake() {}
}
