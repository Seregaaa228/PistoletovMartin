package dev.frontier.arcade.balance;

import java.util.Locale;
import java.util.Set;

/** Ballistic damage is a fraction of target max HP per point of native projectile damage.
 * TaCZ splits one hit into armor and armor-ignore components; both stay linear. */
public final class VehicleBallistics {
    public enum Vehicle { TANK, ARMORED, LIGHT, AIRCRAFT, DRONE }
    public enum Ammo { PISTOL, SHOTGUN, INTERMEDIATE, BATTLE_RIFLE, PRECISION, ANTI_MATERIEL, GRENADE, ROCKET }
    private static final Set<String> TANKS = Set.of("m_1a_2", "t_90a", "ztz_99a", "prism_tank", "plz_05", "yx_100", "fh_77bw");
    private static final Set<String> ARMORED = Set.of("bmp_2", "bradley", "lav_25", "lav_150", "lav_ad", "patriot_truck", "mim_104_patriot_truck", "spike_nlos_jltv");
    private static final Set<String> ASSAULT_RIFLES = Set.of(
            "ak47", "aug", "g36k", "hk416d", "m16a1", "m16a4", "m4a1", "qbz_191", "qbz_95", "scar_l", "type_81", "fn_fal", "hk_g3", "scar_h",
            "ak_47", "ak_12", "m_4", "hk_416");
    private static final Set<String> AIRCRAFT = Set.of("a_10a", "ac_130h", "ah_6", "mi_28", "j_16", "ju_87", "kv_16", "tom_6", "air_sheep", "happiest_ghast", "kirov", "su24m", "su_24m", "su25", "su_25", "f16", "f_16", "j10", "j_10");
    private VehicleBallistics() {}

    public static Vehicle vehicle(String rawId) {
        String id = path(rawId);
        if (TANKS.contains(id)) return Vehicle.TANK;
        if (ARMORED.contains(id)) return Vehicle.ARMORED;
        if (AIRCRAFT.contains(id)) return Vehicle.AIRCRAFT;
        if (id.contains("drone") || id.contains("fpv") || id.contains("mavic") || id.contains("shahed") || id.contains("geranium") || id.contains("uav")) return Vehicle.DRONE;
        return Vehicle.LIGHT;
    }

    public static Ammo ammo(String rawId, String rawGun) {
        String id = path(rawId), gun = path(rawGun);
        if (id.contains("rpg") || id.contains("rocket") || gun.equals("rpg") || gun.equals("rpg7") || gun.equals("igla_9k38") || gun.equals("javelin")) return Ammo.ROCKET;
        if (id.contains("40mm") || gun.equals("m320") || gun.equals("m_79")) return Ammo.GRENADE;
        if (id.contains("50bmg") || id.contains("12_7") || id.contains("12.7") || gun.equals("m107") || gun.equals("m95") || gun.equals("ntw_20") || gun.equals("m_2_hb") || (id.isEmpty() && gun.equals("minigun"))) return Ammo.ANTI_MATERIEL;
        if (Set.of("338", "30_06", "792x57", "45_70").contains(id) || Set.of("awm", "m_98b", "svd", "kar98", "m700").contains(gun)) return Ammo.PRECISION;
        if (Set.of("308", "762x51", "762x54", "762x54r").contains(id)) return Ammo.BATTLE_RIFLE;
        if (id.equals("12g") || id.contains("shotgun") || Set.of("m_870", "aa_12", "homemade_shotgun").contains(gun)) return Ammo.SHOTGUN;
        if (Set.of("22wmr", "357mag", "45acp", "500mag", "50ae", "57x28", "9mm").contains(id)
                || Set.of("glock_17", "glock_18", "m_1911", "mp_443", "mp_5", "vector").contains(gun)) return Ammo.PISTOL;
        return Ammo.INTERMEDIATE; // Includes 5.56, 5.8, 7.62x39 and unknown gun packs.
    }

    public static float damage(Vehicle vehicle, Ammo ammo, float nativeDamage, float maxHealth) {
        return damage(vehicle, ammo, nativeDamage, maxHealth, "");
    }

    public static float damage(Vehicle vehicle, Ammo ammo, float nativeDamage, float maxHealth, String gunId) {
        if (!Float.isFinite(nativeDamage) || !Float.isFinite(maxHealth) || nativeDamage <= 0 || maxHealth <= 0) return 0;
        float factor = switch (ammo) {
            case PISTOL -> factor(vehicle, .00006f, .00010f, .0011f, .0008f, .10f);
            case SHOTGUN -> factor(vehicle, .00008f, .00018f, .0016f, .0014f, .09f);
            case INTERMEDIATE -> factor(vehicle, .00016f, .00035f, .0020f, .0024f, .12f);
            case BATTLE_RIFLE -> factor(vehicle, .00022f, .00048f, .0022f, .0023f, .12f);
            case PRECISION -> factor(vehicle, .00025f, .00055f, .0024f, .0024f, .12f);
            case ANTI_MATERIEL -> factor(vehicle, .00035f, .00085f, .0030f, .0030f, .12f);
            case GRENADE -> factor(vehicle, .0018f, .0030f, .0050f, .0060f, .12f);
            case ROCKET -> factor(vehicle, .0070f, .0120f, .0160f, .0180f, .12f);
        };
        String gun = path(gunId);
        float minigunMultiplier = gun.equals("minigun") ? 2.0f : 1.0f;
        // Calibrated against the default magazines and near-range damage of 14 TaCZ assault/battle rifles.
        float aircraftMultiplier = vehicle == Vehicle.AIRCRAFT && ASSAULT_RIFLES.contains(gun) ? 0.625f : 1.0f;
        return Math.min(maxHealth, maxHealth * nativeDamage * factor * 2.0f * minigunMultiplier * aircraftMultiplier);
    }

    private static float factor(Vehicle v, float tank, float armored, float light, float aircraft, float drone) {
        return switch (v) { case TANK -> tank; case ARMORED -> armored; case LIGHT -> light; case AIRCRAFT -> aircraft; case DRONE -> drone; };
    }
    private static String path(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1);
    }
}
