package dev.frontier.industry.combat;

import java.util.Arrays;
import java.util.UUID;

/** Pure game rules: these numbers deliberately do not model real weapons. */
public final class CombatRules {
    public static final int MAX_SALVO = 3;
    public static final int MAX_AMMO = 64;
    public static final int MAX_RANGE = 2048;
    public static final int MAX_FLIGHT_TICKS = 560;
    public static final int LAUNCH_COOLDOWN = 60;
    public static final int INTERCEPTOR_LIFETIME = 100;
    private CombatRules() { }

    public static int flightTicks(double distance) {
        return Math.max(45, Math.min(520, (int) Math.ceil(distance / 4.0)));
    }
    public static double arcHeight(double distance) {
        return Math.max(24.0, Math.min(112.0, distance * 0.12));
    }
    public static double arcOffset(double progress, double distance) {
        return 4.0 * progress * (1.0 - progress) * arcHeight(distance);
    }
    public static boolean hostile(UUID owner, UUID candidate, boolean sameTeam) {
        return owner != null && candidate != null && !owner.equals(candidate) && !sameTeam;
    }
    /** Reserve a complete salvo without mutating any caller-owned stock. */
    public static int[] allocate(int[] ammunition, int count) {
        if (count < 1 || count > MAX_SALVO) return null;
        int[] reserved = new int[ammunition.length];
        int remaining = count;
        for (int round = 0; round < MAX_SALVO && remaining > 0; round++) {
            for (int index = 0; index < ammunition.length && remaining > 0; index++) {
                if (ammunition[index] > reserved[index]) { reserved[index]++; remaining--; }
            }
        }
        return remaining == 0 ? reserved : null;
    }
    public static boolean sameSnapshot(int[] before, int[] current) {
        return Arrays.equals(before, current);
    }
}
