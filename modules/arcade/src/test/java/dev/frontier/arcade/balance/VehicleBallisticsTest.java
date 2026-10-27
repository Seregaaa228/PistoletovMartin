package dev.frontier.arcade.balance;

import static dev.frontier.arcade.balance.VehicleBallistics.*;

public final class VehicleBallisticsTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        String[] pistol = {"22wmr", "357mag", "45acp", "500mag", "50ae", "57x28", "9mm"};
        for (String id : pistol) check(ammo("tacz:" + id, "") == Ammo.PISTOL, id);
        for (String id : new String[]{"556x45", "58x42", "762x39"}) check(ammo("tacz:" + id, "") == Ammo.INTERMEDIATE, id);
        for (String id : new String[]{"308", "762x51", "762x54", "762x54r"}) check(ammo("tacz:" + id, "") == Ammo.BATTLE_RIFLE, id);
        for (String id : new String[]{"338", "30_06", "792x57", "45_70"}) check(ammo("tacz:" + id, "") == Ammo.PRECISION, id);
        check(ammo("tacz:12g", "") == Ammo.SHOTGUN, "shotgun");
        check(ammo("tacz:50bmg", "") == Ammo.ANTI_MATERIEL, ".50 BMG");
        check(ammo("tacz:40mm", "") == Ammo.GRENADE, "grenade");
        check(ammo("tacz:rpg_rocket", "") == Ammo.ROCKET, "rocket");
        check(ammo("", "superbwarfare:m_2_hb") == Ammo.ANTI_MATERIEL, "native heavy machine gun");
        check(ammo("", "superbwarfare:igla_9k38") == Ammo.ROCKET, "native missile launcher");
        check(ammo("tacz:308", "tacz:minigun") == Ammo.BATTLE_RIFLE, "TaCZ minigun uses .308");
        check(vehicle("superbwarfare:bmp_2") == Vehicle.ARMORED, "BMP");
        check(vehicle("superbwarfare:t_90a") == Vehicle.TANK, "tank");
        check(vehicle("frontier_aviation:su25") == Vehicle.AIRCRAFT, "aircraft");
        check(vehicle("uncomplicatedfpv:fpv_drone") == Vehicle.DRONE, "drone");
        for (Ammo ammo : Ammo.values()) {
            float tank = damage(Vehicle.TANK, ammo, 8f, 300f);
            float armored = damage(Vehicle.ARMORED, ammo, 8f, 300f);
            float light = damage(Vehicle.LIGHT, ammo, 8f, 300f);
            float air = damage(Vehicle.AIRCRAFT, ammo, 8f, 300f);
            check(tank > 0 && tank < armored && armored < light && armored < air, "armor ordering: " + ammo);
            float split = damage(Vehicle.ARMORED, ammo, 6f, 300f) + damage(Vehicle.ARMORED, ammo, 2f, 300f);
            check(Math.abs(split - armored) < .0001f, "TaCZ split must not duplicate: " + ammo);
        }
        float akAircraft = damage(Vehicle.AIRCRAFT, Ammo.INTERMEDIATE, 9f, 350f, "tacz:ak47");
        check(Math.abs(akAircraft - 9.45f) < .001f, "assault-rifle aircraft damage reduced to 62.5%");
        check(Math.abs(damage(Vehicle.ARMORED, Ammo.INTERMEDIATE, 9f, 300f, "tacz:ak47") - 1.89f) < .001f, "BMP damage unchanged");
        check(Math.abs(damage(Vehicle.AIRCRAFT, Ammo.BATTLE_RIFLE, 8f, 350f, "tacz:minigun") - 2f * damage(Vehicle.AIRCRAFT, Ammo.BATTLE_RIFLE, 8f, 350f)) < .001f, "minigun remains unchanged");
        float normal308 = damage(Vehicle.ARMORED, Ammo.BATTLE_RIFLE, 8f, 300f, "tacz:fn_fal");
        float taczMinigun = damage(Vehicle.ARMORED, Ammo.BATTLE_RIFLE, 8f, 300f, "tacz:minigun");
        check(Math.abs(taczMinigun - 2f * normal308) < .0001f, "TaCZ minigun gets double damage");
        float nativeMinigun = damage(Vehicle.ARMORED, Ammo.ANTI_MATERIEL, 8f, 300f, "superbwarfare:minigun");
        check(Math.abs(nativeMinigun - 2f * damage(Vehicle.ARMORED, Ammo.ANTI_MATERIEL, 8f, 300f)) < .0001f, "native minigun gets double damage");
        check(damage(Vehicle.ARMORED, Ammo.PISTOL, 0f, 300f) == 0, "zero hit");
        check(damage(Vehicle.ARMORED, Ammo.PISTOL, Float.NaN, 300f) == 0, "non-finite hit");
        float rifle = damage(Vehicle.ARMORED, Ammo.INTERMEDIATE, 9f, 300f);
        check(Math.abs(rifle - 1.89f) < .001f, "AK-style hit on 300 HP BMP is doubled");
        check(Math.abs(damage(Vehicle.AIRCRAFT, Ammo.INTERMEDIATE, 9f, 350f) - 15.12f) < .001f, "AK-style hit on aircraft is doubled");
        float rocket = damage(Vehicle.ARMORED, Ammo.ROCKET, 20f, 300f);
        check(rocket > 50 * rifle, "rocket must be much stronger than a rifle bullet");
        System.out.println("VehicleBallisticsTest: caliber, vehicle, split-hit and balance checks passed");
    }
}
