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
        double max = missile.shortReach() > 0 ? Math.min(system.maxRange, missile.shortReach()) : system.maxRange;
        return range >= system.minRange && range <= max && altitude >= system.minAltitude && altitude <= system.maxAltitude;
    }

    /**
     * Missile to use on a contact, or null if no loaded rack fits. The S-400 and Patriot keep their
     * big rounds (48N6, PAC-2) for far, fast targets and spend the short ones (9M96, PAC-3) on
     * drones, helicopters, missiles and close contacts.
     */
    public static SamMissile choose(SamSystem system, int[] ready, Kind kind, double range, double altitude) {
        if (kind == Kind.MISSILE && !system.engagesMissiles()) return null;
        double shortReach = system.racks.stream().mapToDouble(rack -> rack.missile().shortReach()).max().orElse(0);
        boolean wantsShort = kind != Kind.JET || range <= shortReach;
        SamMissile fallback = null;
        for (int i = 0; i < system.racks.size(); i++) {
            SamMissile missile = system.racks.get(i).missile();
            if (i >= ready.length || ready[i] <= 0 || !engageable(system, missile, range, altitude)) continue;
            if ((missile.shortReach() > 0) == wantsShort) return missile;
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
