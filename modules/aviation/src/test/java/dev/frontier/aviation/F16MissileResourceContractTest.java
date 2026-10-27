package dev.frontier.aviation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies the native weapon contract without starting a world. */
public final class F16MissileResourceContractTest {
    public static void main(String[] args) throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/frontier_aviation/sbw/vehicles/f16.json"))).getAsJsonObject();
        JsonArray seatWeapons = config.getAsJsonArray("Seats").get(0).getAsJsonObject().getAsJsonArray("Weapons");
        check(seatWeapons.toString().equals("[\"Cannon\",\"Missile\"]"), "pilot weapon order");
        JsonObject weapons = config.getAsJsonObject("Weapons");
        JsonObject cannon = weapons.getAsJsonObject("Cannon");
        check(cannon.get("Projectile").getAsString().equals("superbwarfare:small_cannon_shell"), "cannon projectile");
        check(cannon.get("Magazine").getAsInt() == 500 && cannon.get("RPM").getAsInt() == 1200, "cannon unchanged");
        JsonObject missile = weapons.getAsJsonObject("Missile");
        check(missile.get("Projectile").getAsString().equals("superbwarfare:agm_65"), "native projectile");
        check(missile.get("Magazine").getAsInt() == 2, "two missiles");
        check(missile.get("Crosshair").getAsString().equals("@AirCraftMissile"), "native HUD");
        JsonArray positions = missile.getAsJsonObject("ShootPos").getAsJsonArray("Positions");
        check(positions.toString().equals("[[3.4,2.2,0.2],[-3.4,2.2,0.2]]"), "two pylon positions");
        check(missile.getAsJsonObject("ShootPos").get("BoundUpWithAmmoAmount").getAsBoolean(), "models follow ammo");
        JsonArray ammo = missile.getAsJsonArray("AmmoType");
        check(ammo.size() == 1, "only supported block target mode");
        for (var entry : ammo) {
            JsonObject option = entry.getAsJsonObject();
            check(option.get("Ammo").getAsString().equals("superbwarfare:large_anti_ground_missile"), "finite native ammo");
            check(!option.get("ShouldUnload").getAsBoolean(), "mode switching keeps loaded ammo");
        }
        check(missile.getAsJsonObject("SeekWeaponInfo").get("OnlyLockBlock").getAsBoolean(), "block mode default");
        check(missile.getAsJsonObject("SeekWeaponInfo").get("SeekRange").getAsInt() == 256, "server-supported range");
        String properties = Files.readString(Path.of("../../gradle.properties"));
        check(properties.contains("minecraft_version=1.20.1") && properties.contains("forge_version=47.4.0"), "platform versions");
        System.out.println("F16MissileResourceContractTest passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
