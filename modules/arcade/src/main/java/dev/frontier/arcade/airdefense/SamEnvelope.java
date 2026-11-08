package dev.frontier.arcade.airdefense;

/** Pure engagement rules: who is seen, who can be shot, which missile, who first. */
public final class SamEnvelope {
    private SamEnvelope() { }

    /** Kinds of air contact, by how a radar sees them. */
    public enum Kind { JET, HELICOPTER, DRONE, MISSILE }

    public static double detectRange(SamSystem system, Kind kind) {
        return kind == Kind.DRONE || kind == Kind.MISSILE ? system.detectRange * system.droneDetection : system.detectRange;
    }

    /** Radar horizon: a contact hugging the ground is lost in clutter. */
    public static boolean visible(SamSystem system, Kind kind, double range, double altitude) {
        return Double.isFinite(range) && Double.isFinite(altitude) && range <= detectRange(system, kind)
            && altitude >= system.minAltitude * 0.5;
    }

    /** Inside the zone of fire for this missile. */
    public static boolean engageable(SamSystem system, SamMissile missile, double range, double altitude) {
        double max = missile == SamMissile.M9M96 ? Math.min(system.maxRange, 160) : system.maxRange;
        return range >= system.minRange && range <= max && altitude >= system.minAltitude && altitude <= system.maxAltitude;
    }

    /**
     * Missile to use on a contact, or null if no loaded rack fits. The S-400 keeps its two
     * 48N6 for far, fast targets and spends 9M96 on drones, helicopters and close contacts.
     */
    public static SamMissile choose(SamSystem system, int[] ready, Kind kind, double range, double altitude) {
        SamMissile fallback = null;
        for (int i = 0; i < system.racks.size(); i++) {
            SamMissile missile = system.racks.get(i).missile();
            if (i >= ready.length || ready[i] <= 0 || !engageable(system, missile, range, altitude)) continue;
            boolean cheap = missile == SamMissile.M9M96;
            boolean wantsCheap = kind != Kind.JET || range <= 160;
            if (cheap == wantsCheap) return missile;
            if (fallback == null) fallback = missile;
        }
        return fallback;
    }

    /** Higher is more urgent: closing fast and near first; missiles and jets before drones. */
    public static double threat(Kind kind, double range, double closingSpeed) {
        double time = range / Math.max(0.25, closingSpeed + 0.5);
        double weight = switch (kind) { case MISSILE -> 3; case JET -> 2; case HELICOPTER -> 1.5; case DRONE -> 1.2; };
        return weight / Math.max(1, time);
    }
}
