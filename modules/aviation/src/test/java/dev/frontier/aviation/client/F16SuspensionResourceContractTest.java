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

/** Checks that both rendered models retain the animated gear and control surfaces. */
public final class F16SuspensionResourceContractTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/frontier_aviation/models/bedrock");
    private static final String[] GEARS = {"gear_nose", "gear_main_l", "gear_main_r"};
    private static final String[] SURFACES = {"f16_aileron_left", "f16_aileron_right",
            "f16_elevator_left", "f16_elevator_right", "f16_rudder"};

    public static void main(String[] args) throws Exception {
        checkPreservedBones();
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
                "vehicle/j10.geo.json", "0a33891a7d7fc03c8d1bc7f61849231c65d2eec1347c8a0e386b079b215dafb4",
                "vehicle/j10_lod.geo.json", "07aca619f13ccc9ee346a6f3bc5b763fbfeb7fcd93206393e1aa17c19ce4fe18",
                "vehicle/su25.geo.json", "1e0d490060941006d16f58a914f5f99670c103f7046c9b1427df94bb3e5e15ac",
                "vehicle/su25_lod.geo.json", "ffe7ad7ebd69cccfe188b784344a548c2290ee625fb28736ddfc9a42f8b5bcf0",
                "vehicle_lod/j10.geo.json", "07aca619f13ccc9ee346a6f3bc5b763fbfeb7fcd93206393e1aa17c19ce4fe18",
                "vehicle_lod/su25.geo.json", "ffe7ad7ebd69cccfe188b784344a548c2290ee625fb28736ddfc9a42f8b5bcf0");
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
        for (String gear : GEARS) {
            for (String suffix : new String[] {"", "_suspension", "_wheel"}) {
                check(bones.has(gear + suffix), "baker must retain " + gear + suffix);
            }
        }
        check(bones.has("canopy"), "baker must retain canopy");
        for (int i = 1; i <= 2; i++) check(bones.has("dummy_0_1_" + i), "baker must retain missile mount " + i);
    }

    private static void checkBakedModel(String folder) throws Exception {
        Gson gson = GsonUtil.CLIENT_GSON;
        Path animation = Path.of("src/main/resources/assets/frontier_aviation/animations/bedrock/" + folder + "/f16.animation.json");
        BedrockAnimationFile file = gson.fromJson(Files.readString(animation), BedrockAnimationFile.class);
        BedrockModelPOJO model = gson.fromJson(Files.readString(MODELS.resolve(folder + "/f16.geo.json")), BedrockModelPOJO.class);
        var instance = BakedBedrockModel.bake(model, BakerOptions.ofAnimationFile(file)).createInstance();
        for (String surface : SURFACES) check(instance.getBone(surface) != null, folder + " baked " + surface);
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
            check(parent.getAsJsonArray("cubes").size() == gearCubes, folder + " fixed cubes " + gear);
            JsonObject wheel = byName.get(gear + "_wheel");
            check(wheel != null && (gear + "_suspension").equals(wheel.get("parent").getAsString()), folder + " wheel parent " + gear);
            check(wheel.getAsJsonArray("cubes").size() == gearCubes, folder + " wheel cubes " + gear);
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
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
