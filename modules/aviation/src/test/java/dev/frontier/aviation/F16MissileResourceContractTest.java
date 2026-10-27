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
        checkViewPosition(cannon, "cannon ADS view");
        JsonObject missile = weapons.getAsJsonObject("Missile");
        check(missile.get("DefaultFireMode").getAsString().equals("Auto"), "immediate repeat fire mode");
        check(missile.get("AvailableFireModes").getAsString().equals("Auto"), "repeat fire mode available");
        check(missile.get("RPM").getAsInt() == 60, "request cadence matches server missile cooldown");
        check(missile.get("Projectile").getAsString().equals("superbwarfare:agm_65"), "native projectile");
        check(missile.get("Magazine").getAsInt() == 2, "two missiles");
        check(missile.get("Crosshair").getAsString().equals("@AirCraftMissile"), "native HUD");
        checkViewPosition(missile, "missile ADS view");
        JsonArray positions = missile.getAsJsonObject("ShootPos").getAsJsonArray("Positions");
        check(positions.toString().equals("[[3.4,2.2,0.2],[-3.4,2.2,0.2]]"), "two pylon positions");
        check(missile.getAsJsonObject("ShootPos").get("BoundUpWithAmmoAmount").getAsBoolean(), "models follow ammo");
        check(missile.getAsJsonObject("ShootPos").getAsJsonArray("Directions").toString().equals("[[0,0,1]]"),
                "free launch follows the vehicle's forward axis");
        JsonArray ammo = missile.getAsJsonArray("AmmoType");
        check(ammo.size() == 1, "only supported block target mode");
        for (var entry : ammo) {
            JsonObject option = entry.getAsJsonObject();
            check(option.get("Ammo").getAsString().equals("superbwarfare:large_anti_ground_missile"), "finite native ammo");
            check(!option.get("ShouldUnload").getAsBoolean(), "mode switching keeps loaded ammo");
        }
        check(missile.getAsJsonObject("SeekWeaponInfo").get("OnlyLockBlock").getAsBoolean(), "block mode default");
        check(missile.getAsJsonObject("SeekWeaponInfo").get("SeekRange").getAsInt() == 256, "server-supported range");
        JsonObject canopy = findBone(JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/frontier_aviation/models/bedrock/vehicle/f16.geo.json"))).getAsJsonObject(),
                "canopy");
        checkViewAboveCanopy(cannon, canopy, "cannon view point is above F16 canopy geometry");
        checkViewAboveCanopy(missile, canopy, "missile view point is above F16 canopy geometry");
        check(missile.getAsJsonObject("ShootPos").getAsJsonArray("ShootPositionForHud").toString()
                .equals("[0,2.2,0.2]"), "HUD shooting position unchanged");
        String properties = Files.readString(Path.of("../../gradle.properties"));
        check(properties.contains("minecraft_version=1.20.1") && properties.contains("forge_version=47.4.0"), "platform versions");
        System.out.println("F16MissileResourceContractTest passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void checkViewPosition(JsonObject weapon, String message) {
        JsonArray view = weapon.getAsJsonObject("ShootPos").getAsJsonArray("ViewPosition");
        check(view.size() == 3 && close(view.get(0).getAsDouble(), 0)
                && close(view.get(1).getAsDouble(), 3.9) && close(view.get(2).getAsDouble(), 3.8), message);
    }
    private static JsonObject findBone(JsonObject model, String name) {
        for (var element : model.getAsJsonArray("minecraft:geometry")) {
            JsonArray bones = element.getAsJsonObject().getAsJsonArray("bones");
            for (var bone : bones) {
                JsonObject object = bone.getAsJsonObject();
                if (name.equals(object.get("name").getAsString())) return object;
            }
        }
        throw new AssertionError("missing geometry bone " + name);
    }
    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1.0e-9;
    }
    private static void checkViewAboveCanopy(JsonObject weapon, JsonObject canopy, String message) {
        JsonArray camera = weapon.getAsJsonObject("ShootPos").getAsJsonArray("ViewPosition");
        double cameraX = -camera.get(0).getAsDouble() * 16.0;
        double cameraY = camera.get(1).getAsDouble() * 16.0;
        double cameraZ = -camera.get(2).getAsDouble() * 16.0;
        double maxCanopyYAtCamera = Double.NEGATIVE_INFINITY;
        for (var cubeElement : canopy.getAsJsonArray("cubes")) {
            JsonObject cube = cubeElement.getAsJsonObject();
            JsonArray origin = cube.getAsJsonArray("origin");
            JsonArray size = cube.getAsJsonArray("size");
            double minX = origin.get(0).getAsDouble(), maxX = minX + size.get(0).getAsDouble();
            double minZ = origin.get(2).getAsDouble(), maxZ = minZ + size.get(2).getAsDouble();
            if (cameraX >= minX && cameraX <= maxX && cameraZ >= minZ && cameraZ <= maxZ)
                maxCanopyYAtCamera = Math.max(maxCanopyYAtCamera, origin.get(1).getAsDouble() + size.get(1).getAsDouble());
        }
        check(Double.isFinite(maxCanopyYAtCamera) && cameraY > maxCanopyYAtCamera, message);
    }
}
