package dev.frontier.aviation.client;

import com.google.gson.Gson;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.GsonUtil;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockAnimationFile;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks that both rendered models retain the animated gear and control surfaces. Every owned
 * jet (Su-25, F-16, J-10) carries the same gear_*, gear_*_suspension, gear_*_wheel hierarchy.
 */
public final class F16SuspensionResourceContractTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/frontier_aviation/models/bedrock");
    private static final Path ANIMATIONS = Path.of("src/main/resources/assets/frontier_aviation/animations/bedrock");
    private static final String[] JETS = {"su25", "f16", "j10"};
    private static final String[] GEARS = {"gear_nose", "gear_main_l", "gear_main_r"};
    private static final String[] SURFACES = {"f16_aileron_left", "f16_aileron_right",
            "f16_elevator_left", "f16_elevator_right", "f16_rudder"};

    public static void main(String[] args) throws Exception {
        checkPreservedBones();
        for (String jet : JETS) {
            check(Files.mismatch(ANIMATIONS.resolve("vehicle/" + jet + ".animation.json"),
                    ANIMATIONS.resolve("vehicle_lod/" + jet + ".animation.json")) == -1, jet + " LOD animation bones");
            checkGearBound(jet);
            for (String folder : new String[]{"vehicle", "vehicle_lod"}) {
                checkGearModel(jet, folder);
                checkBakedGear(jet, folder);
            }
            check(Files.mismatch(MODELS.resolve("vehicle/" + jet + "_lod.geo.json"),
                    MODELS.resolve("vehicle_lod/" + jet + ".geo.json")) == -1, jet + " authoring LOD matches runtime LOD");
            String config = Files.readString(Path.of("src/main/resources/assets/frontier_aviation/sbw/vehicles/" + jet + ".json"));
            check(config.contains("frontier_aviation:models/bedrock/vehicle_lod/" + jet + ".geo.json"), jet + " runtime LOD path");
        }
        // Su-25 cockpit-view instrument panel (AircraftRenderer shows it in first person only).
        check(Files.readString(ANIMATIONS.resolve("vehicle/su25.animation.json")).contains("\"cockpit_panel\""),
                "baker must retain su25 cockpit_panel");
        JsonObject panel = bones(MODELS.resolve("vehicle/su25.geo.json"), "su25").get("cockpit_panel");
        check(panel != null && panel.has("cubes") && !panel.getAsJsonArray("cubes").isEmpty(), "su25 cockpit_panel geometry");
        check(bones(MODELS.resolve("vehicle_lod/su25.geo.json"), "su25 lod").containsKey("cockpit_panel"), "su25 LOD cockpit_panel bone");
        check(Files.mismatch(Path.of("src/main/resources/assets/frontier_aviation/animations/bedrock/vehicle/f16.animation.json"),
                Path.of("src/main/resources/assets/frontier_aviation/animations/bedrock/vehicle_lod/f16.animation.json")) == -1,
                "LOD listener receives matching animation bones");
        checkModel("vehicle", 2);
        checkModel("vehicle_lod", 1);
        checkBakedModel("vehicle");
        checkBakedModel("vehicle_lod");
        check(Files.mismatch(MODELS.resolve("vehicle/f16_lod.geo.json"), MODELS.resolve("vehicle_lod/f16.geo.json")) == -1,
                "authoring LOD matches runtime LOD");
        String vehicleConfig = Files.readString(Path.of("src/main/resources/assets/frontier_aviation/sbw/vehicles/f16.json"));
        check(vehicleConfig.contains("frontier_aviation:models/bedrock/vehicle_lod/f16.geo.json"), "runtime LOD path");
        Map<String, String> unchanged = Map.of(
                "vehicle/j10.geo.json", "7aa325e98c06de3f6e526378c55427c7a56ed0b5c5b8ea9a2a854d93492eaa56",
                "vehicle/j10_lod.geo.json", "38df5b9e95daa28b4ab326297552e8ce87f32ec2ba2484ab6ff6bfcf62335866",
                "vehicle/su25.geo.json", "0a4f1baab665403b590e90e4b042413d88bba413ab2447bb3719bf160ab30855",
                "vehicle/su25_lod.geo.json", "485107dd8dac1687472fbaa9d3820335624682c8622ff846898420795baf0b6c",
                "vehicle_lod/j10.geo.json", "38df5b9e95daa28b4ab326297552e8ce87f32ec2ba2484ab6ff6bfcf62335866",
                "vehicle_lod/su25.geo.json", "485107dd8dac1687472fbaa9d3820335624682c8622ff846898420795baf0b6c");
        for (var entry : unchanged.entrySet()) {
            check(hash(Files.readAllBytes(MODELS.resolve(entry.getKey()))).equals(entry.getValue()), entry.getKey());
        }
        System.out.println("F16SuspensionResourceContractTest passed");
    }

    private static void checkPreservedBones() throws Exception {
        // SBW bakes away bones not named by its animation file. Java transforms
        // can only reach bones included in this otherwise-neutral animation.
        Path path = Path.of("src/main/resources/assets/frontier_aviation/animations/bedrock/vehicle/f16.animation.json");
        JsonObject animations = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("animations");
        JsonObject bones = animations.getAsJsonObject("animation.frontier_aviation.bind").getAsJsonObject("bones");
        for (String surface : SURFACES) check(bones.has(surface), "baker must retain " + surface);
        for (String side : new String[]{"left", "right"})
            check(bones.has("f16_airbrake_" + side), "baker must retain aft airbrake " + side);
        for (String gear : GEARS) {
            for (String suffix : new String[] {"", "_suspension", "_wheel"}) {
                check(bones.has(gear + suffix), "baker must retain " + gear + suffix);
            }
        }
        check(bones.has("canopy"), "baker must retain canopy");
        for (int i = 1; i <= 2; i++) check(bones.has("dummy_0_1_" + i), "baker must retain missile mount " + i);
    }

    /** SBW's baker drops bones its animation file does not name; Java could not move them. */
    private static void checkGearBound(String jet) throws Exception {
        JsonObject animations = JsonParser.parseString(Files.readString(ANIMATIONS.resolve("vehicle/" + jet + ".animation.json")))
                .getAsJsonObject().getAsJsonObject("animations");
        JsonObject bones = animations.getAsJsonObject("animation.frontier_aviation.bind").getAsJsonObject("bones");
        for (String gear : GEARS)
            for (String suffix : new String[] {"", "_suspension", "_wheel"})
                check(bones.has(gear + suffix), jet + " baker must retain " + gear + suffix);
    }

    /**
     * Gear hierarchy body > gear_* > gear_*_suspension > gear_*_wheel; the wheel's first cube is the
     * axis-aligned tyre bar, centred on the wheel pivot, standing on the ground (y = 0), and its
     * radius is the one the wheel-spin code turns the wheel with.
     */
    private static void checkGearModel(String jet, String folder) throws Exception {
        Map<String, JsonObject> byName = bones(MODELS.resolve(folder + "/" + jet + ".geo.json"), jet + " " + folder);
        F16WheelSpin spin = F16WheelSpin.forAircraft(jet);
        for (String gear : GEARS) {
            String where = jet + " " + folder + " " + gear;
            JsonObject leg = byName.get(gear), suspension = byName.get(gear + "_suspension"), wheel = byName.get(gear + "_wheel");
            check(leg != null && suspension != null && wheel != null, where + " bones");
            check(leg.has("parent") && "body".equals(leg.get("parent").getAsString()), where + " gear parent");
            check(suspension.has("parent") && gear.equals(suspension.get("parent").getAsString()), where + " suspension parent");
            check(wheel.has("parent") && (gear + "_suspension").equals(wheel.get("parent").getAsString()), where + " wheel parent");
            check(leg.has("cubes") && !leg.getAsJsonArray("cubes").isEmpty(), where + " fixed leg geometry");
            check(wheel.has("cubes") && !wheel.getAsJsonArray("cubes").isEmpty(), where + " wheel geometry");
            JsonObject tire = wheel.getAsJsonArray("cubes").get(0).getAsJsonObject();
            check(!tire.has("rotation") || isZero(tire.getAsJsonArray("rotation")), where + " tyre bar is axis-aligned");
            JsonArray pivot = wheel.getAsJsonArray("pivot");
            for (int axis = 0; axis < 3; axis++) {
                double center = tire.getAsJsonArray("origin").get(axis).getAsDouble() + tire.getAsJsonArray("size").get(axis).getAsDouble() / 2;
                check(Math.abs(center - pivot.get(axis).getAsDouble()) < 1e-4, where + " wheel pivot");
            }
            check(Math.abs(tire.getAsJsonArray("origin").get(1).getAsDouble()) < 1e-4, where + " tyre stands on the ground");
            double radius = tire.getAsJsonArray("size").get(1).getAsDouble() / 2;
            check(Math.abs(radius / 16 - spin.radius(gear.equals("gear_nose"))) < 1e-4, where + " wheel-spin radius");
        }
    }

    private static void checkBakedGear(String jet, String folder) throws Exception {
        Gson gson = GsonUtil.CLIENT_GSON;
        BedrockAnimationFile file = gson.fromJson(Files.readString(ANIMATIONS.resolve(folder + "/" + jet + ".animation.json")),
                BedrockAnimationFile.class);
        BedrockModelPOJO model = gson.fromJson(Files.readString(MODELS.resolve(folder + "/" + jet + ".geo.json")), BedrockModelPOJO.class);
        var instance = BakedBedrockModel.bake(model, BakerOptions.ofAnimationFile(file)).createInstance();
        for (String gear : GEARS) {
            var parent = instance.getBone(gear);
            var suspension = instance.getBone(gear + "_suspension");
            var wheel = instance.getBone(gear + "_wheel");
            check(parent != null && suspension != null && wheel != null, jet + " " + folder + " baked " + gear);
            check(suspension.parentIndex() == parent.index(), jet + " " + folder + " baked suspension hierarchy " + gear);
            check(wheel.parentIndex() == suspension.index(), jet + " " + folder + " baked wheel hierarchy " + gear);
        }
    }

    private static Map<String, JsonObject> bones(Path path, String where) throws Exception {
        JsonObject model = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        Map<String, JsonObject> byName = new TreeMap<>();
        for (JsonElement element : model.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            check(byName.put(name, bone) == null, where + " duplicate bone " + name);
        }
        return byName;
    }

    private static boolean isZero(JsonArray values) {
        for (JsonElement value : values) if (Math.abs(value.getAsDouble()) > 1e-9) return false;
        return true;
    }

    private static void checkBakedModel(String folder) throws Exception {
        Gson gson = GsonUtil.CLIENT_GSON;
        Path animation = Path.of("src/main/resources/assets/frontier_aviation/animations/bedrock/" + folder + "/f16.animation.json");
        BedrockAnimationFile file = gson.fromJson(Files.readString(animation), BedrockAnimationFile.class);
        BedrockModelPOJO model = gson.fromJson(Files.readString(MODELS.resolve(folder + "/f16.geo.json")), BedrockModelPOJO.class);
        var instance = BakedBedrockModel.bake(model, BakerOptions.ofAnimationFile(file)).createInstance();
        for (String surface : SURFACES) check(instance.getBone(surface) != null, folder + " baked " + surface);
        for (String side : new String[]{"left", "right"})
            check(instance.getBone("f16_airbrake_" + side) != null, folder + " baked aft airbrake " + side);
        var body = instance.getBone("body");
        check(body != null, folder + " baked body");
        for (int i = 1; i <= 2; i++) {
            var mount = instance.getBone("dummy_0_1_" + i);
            check(mount != null, folder + " baked missile mount " + i);
            check(mount.parentIndex() == body.index(), folder + " missile mount parent " + i);
            var pivot = mount.definition();
            check(Math.abs(pivot.pivotX() - (i == 1 ? -3.4 : 3.4)) < 1e-5, folder + " baked missile pivot X " + i);
            check(Math.abs(pivot.pivotY() - 2.2) < 1e-5, folder + " baked missile pivot Y " + i);
            check(Math.abs(pivot.pivotZ() + 0.2) < 1e-5, folder + " baked missile pivot Z " + i);
        }
        for (String gear : GEARS) {
            var parent = instance.getBone(gear);
            var suspension = instance.getBone(gear + "_suspension");
            var wheel = instance.getBone(gear + "_wheel");
            check(parent != null && suspension != null && wheel != null, folder + " baked " + gear);
            check(suspension.parentIndex() == parent.index(), folder + " baked suspension hierarchy " + gear);
            check(wheel.parentIndex() == suspension.index(), folder + " baked wheel hierarchy " + gear);
        }
    }

    private static void checkModel(String folder, int gearCubes) throws Exception {
        JsonObject model = JsonParser.parseString(Files.readString(MODELS.resolve(folder + "/f16.geo.json"))).getAsJsonObject();
        JsonArray bones = model.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
        Map<String, JsonObject> byName = new TreeMap<>();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            check(byName.put(name, bone) == null, folder + " duplicate bone " + name);
        }
        for (int i = 1; i <= 2; i++) {
            JsonObject mount = byName.get("dummy_0_1_" + i);
            check(mount != null && "body".equals(mount.get("parent").getAsString()), folder + " mount parent " + i);
            JsonArray pivot = mount.getAsJsonArray("pivot");
            double[] expected = {i == 1 ? 54.4 : -54.4, 35.2, -3.2};
            for (int axis = 0; axis < 3; axis++) {
                check(Math.abs(pivot.get(axis).getAsDouble() - expected[axis]) < 1e-6, folder + " mount pivot " + i);
            }
        }
        for (String gear : GEARS) {
            JsonObject parent = byName.get(gear), child = byName.get(gear + "_suspension");
            check(parent != null && child != null, folder + " missing " + gear);
            check(parent.has("parent") && "body".equals(parent.get("parent").getAsString()), folder + " gear parent " + gear);
            check(child.has("parent") && gear.equals(child.get("parent").getAsString()), folder + " suspension parent " + gear);
            check(parent.has("pivot") && parent.getAsJsonArray("pivot").size() == 3, folder + " gear pivot " + gear);
            int expectedGearCubes = gearCubes + (gear.startsWith("gear_main_") ? 1 : 0);
            check(parent.getAsJsonArray("cubes").size() == expectedGearCubes, folder + " fixed cubes " + gear);
            if (gear.startsWith("gear_main_")) {
                JsonArray parts = parent.getAsJsonArray("cubes");
                JsonObject strut = parts.get(0).getAsJsonObject();
                JsonObject brace = parts.get(parts.size() - 1).getAsJsonObject();
                check(overlaps(brace, strut), folder + " brace meets strut " + gear);
                boolean meetsBody = false;
                for (JsonElement cube : byName.get("body").getAsJsonArray("cubes"))
                    meetsBody |= overlaps(brace, cube.getAsJsonObject());
                check(meetsBody, folder + " brace meets fuselage " + gear);
            }
            JsonObject wheel = byName.get(gear + "_wheel");
            check(wheel != null && (gear + "_suspension").equals(wheel.get("parent").getAsString()), folder + " wheel parent " + gear);
            // The near model's tyre is a 12-sided prism (six bars) plus the hub; its first bar is
            // the axis-aligned one, centred on the wheel pivot like the single LOD tyre box.
            check(wheel.getAsJsonArray("cubes").size() >= gearCubes, folder + " wheel cubes " + gear);
            JsonObject tire = wheel.getAsJsonArray("cubes").get(0).getAsJsonObject();
            for (int axis = 0; axis < 3; axis++) {
                double center = tire.getAsJsonArray("origin").get(axis).getAsDouble() + tire.getAsJsonArray("size").get(axis).getAsDouble() / 2;
                check(Math.abs(center - wheel.getAsJsonArray("pivot").get(axis).getAsDouble()) < 1e-6, folder + " wheel pivot " + gear);
            }
        }
        for (String surface : SURFACES) {
            JsonObject bone = byName.get(surface);
            check(bone != null, folder + " missing " + surface);
            check(bone.has("parent") && "body".equals(bone.get("parent").getAsString()), folder + " parent " + surface);
            check(bone.has("pivot") && bone.getAsJsonArray("pivot").size() == 3, folder + " pivot " + surface);
            check(bone.has("cubes") && !bone.getAsJsonArray("cubes").isEmpty(), folder + " geometry " + surface);
        }
        for (String side : new String[]{"left", "right"}) {
            JsonObject brake = byName.get("f16_airbrake_" + side);
            JsonObject rail = byName.get("f16_tip_pylon_" + side);
            check(brake != null && rail != null, folder + " brake and wingtip " + side);
            JsonArray brakePivot = brake.getAsJsonArray("pivot"), railPivot = rail.getAsJsonArray("pivot");
            check(Math.abs(Math.abs(brakePivot.get(0).getAsDouble()) - 11.15) < 1e-6
                && Math.abs(brakePivot.get(2).getAsDouble() - 70) < 1e-6, folder + " aft brake pivot " + side);
            boolean brakeAttached = false;
            for (JsonElement cube : byName.get("engine").getAsJsonArray("cubes"))
                brakeAttached |= overlaps(brake.getAsJsonArray("cubes").get(0).getAsJsonObject(), cube.getAsJsonObject());
            check(brakeAttached, folder + " aft airbrake must meet engine " + side);
            check(Math.abs(Math.abs(railPivot.get(0).getAsDouble()) - 79.2) < 1e-6
                && Math.abs(railPivot.get(2).getAsDouble() - 20) < 1e-6, folder + " wingtip pivot " + side);
            JsonObject railCube = rail.getAsJsonArray("cubes").get(0).getAsJsonObject();
            JsonArray wingCubes = byName.get(side.equals("left") ? "wing_l" : "wing_r").getAsJsonArray("cubes");
            boolean attached = false;
            for (JsonElement candidate : wingCubes) {
                JsonObject wing = candidate.getAsJsonObject();
                boolean intersects = true;
                for (int axis = 0; axis < 3; axis++) {
                    double a = railCube.getAsJsonArray("origin").get(axis).getAsDouble();
                    double b = wing.getAsJsonArray("origin").get(axis).getAsDouble();
                    intersects &= a <= b + wing.getAsJsonArray("size").get(axis).getAsDouble()
                        && b <= a + railCube.getAsJsonArray("size").get(axis).getAsDouble();
                }
                attached |= intersects;
            }
            check(attached, folder + " wingtip rail must meet the wing " + side);
        }
        for (String mount : new String[]{"pylon_l", "pylon_r", "pylon_outer_1", "pylon_outer_2"}) {
            JsonObject pylon = byName.get(mount).getAsJsonArray("cubes").get(0).getAsJsonObject();
            String wingName = pylon.getAsJsonArray("origin").get(0).getAsDouble() > 0 ? "wing_l" : "wing_r";
            boolean attached = false;
            for (JsonElement element : byName.get(wingName).getAsJsonArray("cubes")) {
                JsonObject wing = element.getAsJsonObject();
                boolean intersects = true;
                for (int axis = 0; axis < 3; axis++) {
                    double a = pylon.getAsJsonArray("origin").get(axis).getAsDouble();
                    double b = wing.getAsJsonArray("origin").get(axis).getAsDouble();
                    intersects &= a <= b + wing.getAsJsonArray("size").get(axis).getAsDouble()
                        && b <= a + pylon.getAsJsonArray("size").get(axis).getAsDouble();
                }
                attached |= intersects;
            }
            check(attached, folder + " underwing pylon must meet wing " + mount);
        }
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static boolean overlaps(JsonObject a, JsonObject b) {
        for (int axis = 0; axis < 3; axis++) {
            double x = a.getAsJsonArray("origin").get(axis).getAsDouble();
            double y = b.getAsJsonArray("origin").get(axis).getAsDouble();
            if (x > y + b.getAsJsonArray("size").get(axis).getAsDouble()
                    || y > x + a.getAsJsonArray("size").get(axis).getAsDouble()) return false;
        }
        return true;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
