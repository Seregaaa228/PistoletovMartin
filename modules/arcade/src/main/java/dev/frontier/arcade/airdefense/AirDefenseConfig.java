package dev.frontier.arcade.airdefense;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server settings of the air defence (config/frontier-air-defense-server.toml in the world's
 * serverconfig folder; changes apply without a restart).
 */
public final class AirDefenseConfig {
    /** Whom a site in AUTO fires at on its own. The crew can still designate any contact in either case. */
    public enum AutoTargets {
        /** Every air contact except the owner's own, teammates, country, allies and treaty partners. */
        ALL_BUT_FRIENDS,
        /** Only contacts of countries the owner's country has declared enemies (Recruits diplomacy). */
        ENEMIES_ONLY
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<AutoTargets> AUTO_TARGETS;
    public static final ForgeConfigSpec.BooleanValue ENGAGE_MISSILES;

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.push("airDefense");
        AUTO_TARGETS = builder
            .comment("Whom sites in AUTO engage by themselves.",
                "ALL_BUT_FRIENDS: everything that flies except the owner's own, team, country, allies and treaty partners (neutrals too).",
                "ENEMIES_ONLY: only aircraft and drones of countries at war with the owner's country.")
            .defineEnum("autoTargets", AutoTargets.ALL_BUT_FRIENDS);
        ENGAGE_MISSILES = builder
            .comment("Sites (all but the S-125) also intercept cruise and ballistic missiles that are not their friends'.")
            .define("engageMissiles", true);
        builder.pop();
        SPEC = builder.build();
    }

    private AirDefenseConfig() { }

    public static AutoTargets autoTargets() {
        try { return AUTO_TARGETS.get(); } catch (IllegalStateException notLoaded) { return AutoTargets.ALL_BUT_FRIENDS; }
    }

    public static boolean engageMissiles() {
        try { return ENGAGE_MISSILES.get(); } catch (IllegalStateException notLoaded) { return true; }
    }
}
