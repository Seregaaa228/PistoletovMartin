package dev.frontier.arcade.airdefense;

import java.util.List;

/**
 * The complexes. Distances are blocks, altitude is height above the ground under the target,
 * times are ticks. Each one has a clear niche: the S-125 is a cheap static medium-altitude site
 * that a well-flown jet can beat; the S-300 and S-400 deny the sky far out but need deploying
 * and do not see low flyers early; the Pantsir protects a point against drones and helicopters;
 * the Patriot pairs long-range PAC-2 with hit-to-kill PAC-3 against missiles. The old S-125 does not
 * engage missiles.
 */
public enum SamSystem {
    S125("s125_pechora", 256, 20, 200, 6, 160, 1, 40, 30, 0, 0.6, false,
        List.of(new Rack(SamMissile.V601, 4, 240, Ammo.MEDIUM))),
    S300("s300ps", 320, 30, 288, 8, 288, 2, 30, 40, 120, 0.6, false,
        List.of(new Rack(SamMissile.M48N6, 4, 400, Ammo.LARGE))),
    S400("s400_triumf", 384, 12, 352, 4, 320, 4, 24, 20, 100, 0.8, false,
        List.of(new Rack(SamMissile.M48N6, 2, 400, Ammo.LARGE), new Rack(SamMissile.M9M96, 8, 160, Ammo.MEDIUM))),
    PANTSIR("pantsir_s1", 224, 6, 160, 2, 100, 2, 12, 12, 0, 1.0, true,
        List.of(new Rack(SamMissile.M57E6, 12, 120, Ammo.MEDIUM))),
    PATRIOT("patriot_m901", 352, 20, 320, 6, 300, 3, 28, 24, 140, 0.7, false,
        List.of(new Rack(SamMissile.PAC2, 2, 400, Ammo.LARGE), new Rack(SamMissile.PAC3, 8, 200, Ammo.MEDIUM)));

    /** Native SBW anti-air missile items used as reloads. */
    public enum Ammo {
        MEDIUM("superbwarfare:medium_anti_air_missile"), LARGE("superbwarfare:large_anti_air_missile");
        public final String item;
        private net.minecraft.world.item.Item resolved;
        Ammo(String item) { this.item = item; }

        /** The registered item (looked up once; registries are frozen before any site ticks). */
        public net.minecraft.world.item.Item resolve() {
            if (resolved == null) resolved = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(item));
            return resolved;
        }
    }

    /** A missile type on the launcher: ready rounds and ticks to reload one round from stores. */
    public record Rack(SamMissile missile, int capacity, int reloadTicks, Ammo ammo) { }

    public final String id;
    public final double detectRange, minRange, maxRange, minAltitude, maxAltitude;
    /** Missiles guided at once (fire channels). */
    public final int channels;
    /** Ticks a track must be held before the first launch at it. */
    public final int lockTicks;
    /** Minimum ticks between two launches. */
    public final int launchInterval;
    /** Ticks to raise the launch pack after stopping; zero for systems that fire at once. */
    public final int deployTicks;
    /** Detection range factor against small drones. */
    public final double droneDetection;
    /** Fires while moving (no deployment). */
    public final boolean firesOnMove;
    public final List<Rack> racks;

    SamSystem(String id, double detectRange, double minRange, double maxRange, double minAltitude, double maxAltitude,
              int channels, int lockTicks, int launchInterval, int deployTicks, double droneDetection, boolean firesOnMove,
              List<Rack> racks) {
        this.id = id;
        this.detectRange = detectRange;
        this.minRange = minRange;
        this.maxRange = maxRange;
        this.minAltitude = minAltitude;
        this.maxAltitude = maxAltitude;
        this.channels = channels;
        this.lockTicks = lockTicks;
        this.launchInterval = launchInterval;
        this.deployTicks = deployTicks;
        this.droneDetection = droneDetection;
        this.firesOnMove = firesOnMove;
        this.racks = racks;
    }

    public static SamSystem of(int ordinal) {
        SamSystem[] all = values();
        return all[Math.floorMod(ordinal, all.length)];
    }

    public int totalCapacity() { return racks.stream().mapToInt(Rack::capacity).sum(); }

    /** Cruise and ballistic missiles are tracked and engaged (not by the old S-125). */
    public boolean engagesMissiles() { return this != S125; }
}
