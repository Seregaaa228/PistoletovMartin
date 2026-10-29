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
        check(seatWeapons.toString().equals("[\"Cannon\",\"Missile\",\"AAMissile\",\"LightAAMissile\",\"MediumAGMissile\"]"),
            "pilot weapon order");
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
        check(missile.get("Crosshair").equals(cannon.get("Crosshair")), "common direct-fire reticle");
        check(missile.get("CrosshairZooming").equals(cannon.get("Crosshair")), "common ADS reticle");
        check(!missile.get("UseNacelleCamera").getAsBoolean(), "direct-fire camera uses the common sight axis");
        checkViewPosition(missile, "missile ADS view");
        JsonArray positions = missile.getAsJsonObject("ShootPos").getAsJsonArray("Positions");
        check(positions.toString().equals("[[3.4,2.2,0.2],[-3.4,2.2,0.2]]"), "two pylon positions");
        check(missile.getAsJsonObject("ShootPos").get("BoundUpWithAmmoAmount").getAsBoolean(), "models follow ammo");
        check(missile.getAsJsonObject("ShootPos").getAsJsonArray("Directions").toString().equals("[[0,0,1]]"),
                "native pylon basis retained before reticle convergence");
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
        check(missile.getAsJsonObject("ShootPos").getAsJsonArray("ShootPositionForHud")
                .equals(cannon.getAsJsonObject("ShootPos").getAsJsonArray("Positions").get(0)), "one HUD origin");
        check(missile.getAsJsonObject("ShootPos").getAsJsonArray("ShootDirectionForHud")
                .equals(cannon.getAsJsonObject("ShootPos").getAsJsonArray("ShootDirectionForHud")), "one HUD direction");
        check(cannon.getAsJsonObject("ShootPos").getAsJsonArray("ShootDirectionForHud")
                .equals(cannon.getAsJsonObject("ShootPos").getAsJsonArray("Directions").get(0)), "HUD follows the real cannon barrel");
        checkAam(weapons.getAsJsonObject("AAMissile"), "AAMissile",
            "superbwarfare:large_anti_air_missile", "superbwarfare:ru_9m100_missile",
            "[[3.4,2.2,0.2],[-3.4,2.2,0.2]]");
        checkAam(weapons.getAsJsonObject("LightAAMissile"), "LightAAMissile",
            "superbwarfare:medium_anti_air_missile", "superbwarfare:ru_9m336_missile",
            "[[5.65625,2.6,1.25],[-5.65625,2.6,1.25]]");
        checkAgm(weapons.getAsJsonObject("MediumAGMissile"));
        for (String locale : new String[]{"ru_ru", "en_us"}) {
            JsonObject translations = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/frontier_aviation/lang/" + locale + ".json"))).getAsJsonObject();
            for (String name : new String[]{"AAMissile", "LightAAMissile", "MediumAGMissile"}) {
                String key = weapons.getAsJsonObject(name).get("Name").getAsString();
                check(translations.has(key) && !translations.get(key).getAsString().isBlank(),
                    locale + " missing weapon translation " + key);
            }
        }
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
    private static void checkAam(JsonObject weapon, String name, String ammoId, String projectileId,
                                 String positions) {
        check(weapon.get("Projectile").getAsString().equals(projectileId), name + " native projectile");
        check(weapon.getAsJsonArray("AmmoType").get(0).getAsJsonObject().get("Ammo").getAsString().equals(ammoId),
            name + " native ammo");
        check(!weapon.getAsJsonArray("AmmoType").get(0).getAsJsonObject().get("ShouldUnload").getAsBoolean(),
            name + " preserves loaded ammo on switching");
        check(weapon.getAsJsonObject("SeekWeaponInfo").get("OnlyLockEntity").getAsBoolean()
                && !weapon.getAsJsonObject("SeekWeaponInfo").get("OnlyLockBlock").getAsBoolean(),
            name + " locks entities through the aircraft sight");
        check(weapon.get("Crosshair").getAsString().equals("@AirCraftCommon")
                && weapon.get("CrosshairZooming").getAsString().equals("@AirCraftCommon"),
            name + " uses the aircraft reticle");
        check(weapon.getAsJsonObject("ShootPos").getAsJsonArray("Directions").toString().equals("[[0,0,1]]"),
            name + " launches forward");
        check(weapon.getAsJsonObject("ShootPos").getAsJsonArray("Positions").toString().equals(positions),
            name + " uses the assigned F-16 stations");
    }
    private static void checkAgm(JsonObject weapon) {
        check(weapon.get("Projectile").getAsString().equals("superbwarfare:wire_guide_missile"),
            "medium AGM native projectile");
        check(weapon.getAsJsonArray("AmmoType").get(0).getAsJsonObject().get("Ammo").getAsString()
                .equals("superbwarfare:medium_anti_ground_missile"), "medium AGM native ammo");
        check(!weapon.getAsJsonArray("AmmoType").get(0).getAsJsonObject().get("ShouldUnload").getAsBoolean(),
            "medium AGM preserves loaded ammo on switching");
        JsonObject seek = weapon.getAsJsonObject("SeekWeaponInfo");
        check(!seek.get("OnlyLockEntity").getAsBoolean() && !seek.get("OnlyLockBlock").getAsBoolean(),
            "medium AGM accepts both target categories");
        check(weapon.get("Crosshair").getAsString().equals("@AirCraftCommon")
                && weapon.get("CrosshairZooming").getAsString().equals("@AirCraftCommon"),
            "medium AGM uses the aircraft reticle");
        JsonObject shoot = weapon.getAsJsonObject("ShootPos");
        check(shoot.getAsJsonArray("Directions").toString().equals("[[0,0,1]]"),
            "medium AGM launches forward");
        check(shoot.getAsJsonArray("Positions").toString().equals("[[3.4,2.2,0.2],[-3.4,2.2,0.2]]"),
            "medium AGM uses F-16 underwing stations");
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
