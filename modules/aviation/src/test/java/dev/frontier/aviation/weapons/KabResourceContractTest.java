package dev.frontier.aviation.weapons;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.GsonUtil;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockAnimationFile;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/** KAB-500 model/texture/animation and native Su-25 weapon data, baked with the pinned SBW baker. */
public final class KabResourceContractTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/frontier_aviation");

    public static void main(String[] args) throws Exception {
        Path geo = ASSETS.resolve("models/bedrock/projectile/kab_500.geo.json");
        Path animation = ASSETS.resolve("animations/bedrock/projectile/kab_500.animation.json");
        // SBW resolves model/texture/animation from the entity type's own namespace and path.
        JsonObject geometry = JsonParser.parseString(Files.readString(geo)).getAsJsonObject();
        check(geometry.get("format_version").getAsString().equals("1.12.0"), "Bedrock geometry format");
        JsonObject root = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject description = root.getAsJsonObject("description");
        check(description.get("identifier").getAsString().equals("geometry.kab_500"), "geometry identifier");
        int width = description.get("texture_width").getAsInt(), height = description.get("texture_height").getAsInt();
        var texture = ImageIO.read(ASSETS.resolve("textures/bedrock/projectile/kab_500.png").toFile());
        check(texture.getWidth() == width && texture.getHeight() == height, "texture matches UV space");
        double nose = Double.MAX_VALUE, tail = -Double.MAX_VALUE;
        for (JsonElement bone : root.getAsJsonArray("bones")) {
            var cubes = bone.getAsJsonObject().getAsJsonArray("cubes");
            if (cubes == null) continue;
            for (JsonElement element : cubes) {
                JsonObject cube = element.getAsJsonObject();
                double z = cube.getAsJsonArray("origin").get(2).getAsDouble();
                nose = Math.min(nose, z);
                tail = Math.max(tail, z + cube.getAsJsonArray("size").get(2).getAsDouble());
                for (Map.Entry<String, JsonElement> face : cube.getAsJsonObject("uv").entrySet()) {
                    var uv = face.getValue().getAsJsonObject();
                    double u = uv.getAsJsonArray("uv").get(0).getAsDouble(), v = uv.getAsJsonArray("uv").get(1).getAsDouble();
                    double du = uv.getAsJsonArray("uv_size").get(0).getAsDouble(), dv = uv.getAsJsonArray("uv_size").get(1).getAsDouble();
                    check(Math.min(u, u + du) >= 0 && Math.max(u, u + du) <= width
                        && Math.min(v, v + dv) >= 0 && Math.max(v, v + dv) <= height, "UV inside texture");
                }
            }
        }
        check(nose < -20 && tail > 15 && tail - nose < 64, "nose-forward (-Z) store of plausible length");
        BedrockAnimationFile file = GsonUtil.CLIENT_GSON.fromJson(Files.readString(animation), BedrockAnimationFile.class);
        BedrockModelPOJO model = GsonUtil.CLIENT_GSON.fromJson(Files.readString(geo), BedrockModelPOJO.class);
        // Same baker options as SBW's ProjectileModelReloadListener.
        var instance = BakedBedrockModel.bake(model, BakerOptions.ofAnimationFile(file)
            .withPreservedBoneRegexes(Set.of("^move_.*", "flare"))).createInstance();
        for (String wing : new String[]{"wing_left", "wing_right"})
            check(instance.getBone(wing) != null, "animated wing survives baking: " + wing);
        JsonObject start = JsonParser.parseString(Files.readString(animation)).getAsJsonObject()
            .getAsJsonObject("animations").getAsJsonObject("animation.projectile.start");
        check(start != null && start.get("loop").getAsString().equals("hold_on_last_frame"), "wings stay deployed");

        JsonObject su25 = JsonParser.parseString(Files.readString(Path.of(
            "src/main/resources/data/frontier_aviation/sbw/vehicles/su25.json"))).getAsJsonObject();
        check(su25.getAsJsonArray("Seats").get(0).getAsJsonObject().getAsJsonArray("Weapons").toString()
            .equals("[\"Cannon\",\"GuidedBomb\"]"), "pilot weapon order");
        JsonObject bomb = su25.getAsJsonObject("Weapons").getAsJsonObject("GuidedBomb");
        check(bomb.get("Projectile").getAsString().equals("frontier_aviation:kab_500"), "native projectile is the KAB");
        check(bomb.get("Magazine").getAsInt() == 2, "two heavy stores");
        check(!bomb.get("AddShooterDeltaMovement").getAsBoolean(), "KAB inherits carrier motion itself");
        check(!bomb.getAsJsonObject("SeekWeaponInfo").get("OnlyLockBlock").getAsBoolean()
            && !bomb.getAsJsonObject("SeekWeaponInfo").get("OnlyLockEntity").getAsBoolean(), "point primary, entity optional");
        check(bomb.getAsJsonObject("SeekWeaponInfo").get("SeekRange").getAsInt() == 384, "standoff designation range");
        for (String locale : new String[]{"en_us", "ru_ru"}) {
            JsonObject lang = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/" + locale + ".json"))).getAsJsonObject();
            check(lang.has(bomb.get("Name").getAsString()) && lang.has("entity.frontier_aviation.kab_500"), locale + " names");
        }
        check(KabEntity.MAX_TURN_DEGREES < 15 && KabEntity.GRAVITY > 0, "glide bomb is less agile than SBW's missile and falls");
        System.out.println("KabResourceContractTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
