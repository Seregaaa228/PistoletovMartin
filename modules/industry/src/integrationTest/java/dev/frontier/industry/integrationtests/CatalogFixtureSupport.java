package dev.frontier.industry.integrationtests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.industry.IndustryCatalog;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryRules;
import dev.frontier.industry.integration.catalog.IndustryRecipeReloadListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;

/** Test-profile side effects shared by the real reload and actual restart scenarios. */
final class CatalogFixtureSupport {
    final MinecraftServer server;
    final ServerPlayer player;
    final Site factory, distribution, station;
    final IndustryData data;
    final Path directory, script;
    final String templateId;
    final Map<String, Boolean> checks = new LinkedHashMap<>();
    final JsonObject evidence = new JsonObject();
    private String writtenDigest;
    private boolean ownsScript;
    private volatile boolean reloadComplete;
    private volatile Throwable reloadFailure;
    private int reloadStarted;
    private BlockPos cutPosition;
    private BlockState cutState;

    CatalogFixtureSupport(MinecraftServer server, ServerPlayer player, Site factory) throws IOException {
        this.server = server; this.player = player; this.factory = factory;
        authorize();
        data = IndustryData.get(server);
        distribution = data.sites.get(factory.upstream);
        station = distribution == null ? null : data.sites.get(distribution.upstream);
        if (station == null || !factory.owner.equals(player.getUUID()) || !factory.owner.equals(station.owner))
            throw new IllegalStateException("Catalog fixture requires its own complete native power chain");
        directory = FMLPaths.GAMEDIR.get().toRealPath();
        Path scripts = directory.resolve("kubejs/server_scripts");
        Files.createDirectories(scripts);
        if (Files.isSymbolicLink(scripts) || !scripts.toRealPath().startsWith(directory))
            throw new IllegalStateException("Catalog fixture refuses redirected script directories");
        script = scripts.resolve("pis1_catalog_fixture.js");
        if (Files.exists(script, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Catalog fixture script must originally be absent");
        templateId = "frontier_industry:pis1_catalog_" + System.getProperty("frontier.pis1RunId").toLowerCase(Locale.ROOT).replace('-', '_');
        evidence.addProperty("templateId", templateId);
        evidence.addProperty("temporaryScript", "kubejs/server_scripts/pis1_catalog_fixture.js");
        evidence.addProperty("originalScriptAbsent", true);
        evidence.addProperty("factoryId", factory.id.toString());
    }

    void authorize() {
        if (Boolean.getBoolean("frontier.pis1BenchmarkOnly") || !IntegrationChecksMod.authorized(server))
            throw new IllegalStateException("Catalog fixture refused a baseline or unmatched test profile");
    }

    void clearWork() {
        authorize();
        for (int n = 0; n < IndustryRules.MAX_QUEUE && !factory.orders.isEmpty(); n++)
            IndustryEngine.action(player, factory.id, "cancel", "", 1);
        require("existing fixture queue cancelled through server actions", factory.orders.isEmpty());
        if (factory.repairRequested || factory.repairKit)
            IndustryEngine.action(player, factory.id, "repair_cancel", "", 1);
        require("benchmark repair transaction cancelled before catalog probe", !factory.repairRequested && !factory.repairKit);
        for (int slot = 0; slot < IndustryRules.SLOTS; slot++) factory.inventory.setStackInSlot(slot, ItemStack.EMPTY);
        factory.inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 20));
        factory.inventory.setStackInSlot(1, new ItemStack(Items.REDSTONE, 20));
        data.setDirty();
    }

    void pause() {
        authorize();
        var critical = Blueprint.criticalBlocks(station.type, station.origin, station.blueprintVersion);
        cutPosition = critical.keySet().iterator().next();
        cutState = critical.get(cutPosition);
        require("catalog pause cuts the original physical upstream core", player.serverLevel().getBlockState(cutPosition).equals(cutState));
        player.serverLevel().setBlock(cutPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        IndustryEngine.poll(player.serverLevel(), station);
    }

    void restore() {
        authorize();
        require("catalog pause retains its own empty upstream position", cutPosition != null && player.serverLevel().getBlockState(cutPosition).isAir());
        player.serverLevel().setBlock(cutPosition, cutState, Block.UPDATE_ALL);
        IndustryEngine.poll(player.serverLevel(), station);
    }

    BlockPos cutPosition() { return cutPosition; }
    boolean ownsScript() { return ownsScript; }

    void restoreFromReceipt(BlockPos position) {
        cutPosition = position;
        cutState = Blueprint.criticalBlocks(station.type, station.origin, station.blueprintVersion).get(position);
        require("restart cut position is an exact current critical core", cutState != null);
        restore();
    }

    void writeScript(boolean versionA) throws IOException {
        authorize();
        if (ownsScript && !sha256(Files.readAllBytes(script)).equals(writtenDigest))
            throw new IllegalStateException("Temporary catalog script was changed by another actor");
        JsonObject json = new JsonObject();
        json.addProperty("title", versionA ? "PIS1 paid template A" : "PIS1 paid template B");
        json.addProperty("factory", "EQUIPMENT_FACTORY");
        JsonObject output = new JsonObject();
        output.addProperty("item", versionA ? "frontier_industry:repair_kit" : "minecraft:gold_ingot");
        output.addProperty("count", 1); json.add("output", output);
        JsonObject cost = new JsonObject();
        cost.addProperty("item", versionA ? "minecraft:iron_ingot" : "minecraft:redstone");
        cost.addProperty("count", versionA ? 2 : 3);
        JsonArray costs = new JsonArray(); costs.add(cost); json.add("cost", costs);
        json.addProperty("ticks", versionA ? 120 : 40); json.addProperty("fePerTick", versionA ? 16 : 8);
        String path = templateId.substring(templateId.indexOf(':') + 1);
        String source = "// Temporary fixture for this exact isolated run; removed before saving.\n"
            + "ServerEvents.highPriorityData(event => {\n  event.addJson('frontier_industry:frontier_industry/production/"
            + path + ".json', " + json + ");\n});\n";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (ownsScript) Files.write(script, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        else Files.write(script, bytes, StandardOpenOption.CREATE_NEW);
        writtenDigest = sha256(bytes); ownsScript = true;
        evidence.addProperty(versionA ? "scriptASha256" : "scriptBSha256", writtenDigest);
    }

    void removeScript() throws IOException {
        authorize();
        if (ownsScript) {
            if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS) || !sha256(Files.readAllBytes(script)).equals(writtenDigest))
                throw new IllegalStateException("Refusing to remove a modified or redirected catalog fixture script");
            Files.delete(script); ownsScript = false;
        }
        evidence.addProperty("temporaryScriptRemoved", !Files.exists(script, LinkOption.NOFOLLOW_LINKS));
    }

    void reload(String label) {
        authorize();
        reloadComplete = false; reloadFailure = null; reloadStarted = server.getTickCount();
        server.reloadResources(List.copyOf(server.getPackRepository().getSelectedIds())).whenComplete((unused, failure) -> {
            reloadFailure = failure;
            reloadComplete = true;
        });
        evidence.addProperty("lastReload", label);
        System.out.println("PIS1_CATALOG_RELOAD " + label);
    }

    boolean reloaded() {
        if (server.getTickCount() - reloadStarted > 1200) throw new IllegalStateException("Asynchronous catalog reload timed out");
        if (!reloadComplete) return false;
        if (reloadFailure != null) throw new IllegalStateException("Asynchronous catalog reload failed", reloadFailure);
        return true;
    }

    IndustryCatalog.Recipe published() { return IndustryRecipeReloadListener.recipes().get(templateId); }

    boolean matches(IndustryCatalog.Recipe recipe, boolean versionA) {
        if (recipe == null || !templateId.equals(recipe.id()) || recipe.output().getCount() != 1
            || !recipe.output().is(versionA ? IndustryContent.REPAIR_KIT.get() : Items.GOLD_INGOT)
            || recipe.ticks() != (versionA ? 120 : 40) || recipe.fePerTick() != (versionA ? 16 : 8)) return false;
        var cost = recipe.cost();
        return cost.size() == 1 && cost.get(0).is(versionA ? Items.IRON_INGOT : Items.REDSTONE) && cost.get(0).getCount() == (versionA ? 2 : 3);
    }

    int inputs(Item item) { return count(item, 0, IndustryRules.INPUTS); }
    int outputs(Item item) { return count(item, IndustryRules.INPUTS, IndustryRules.SLOTS); }
    private int count(Item item, int first, int end) {
        int count = 0;
        for (int slot = first; slot < end; slot++) {
            ItemStack stack = factory.inventory.getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    void require(String name, boolean ok) {
        checks.merge(name, ok, (before, after) -> before && after);
        if (!ok) throw new IllegalStateException("Catalog fixture failed: " + name);
    }

    void verifyScriptRestoration() throws IOException {
        authorize();
        JsonObject expected = JsonParser.parseString(Files.readString(directory.resolve("test-profile.json"))).getAsJsonObject().getAsJsonObject("scripts");
        for (var entry : expected.entrySet()) {
            Path path = directory.resolve(entry.getKey()).normalize();
            require("original script hash restored: " + entry.getKey(), path.startsWith(directory) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && sha256(Files.readAllBytes(path)).equals(entry.getValue().getAsString()));
        }
        Set<String> executable = Set.of("server_scripts", "client_scripts", "startup_scripts", "data", "assets");
        Map<String, String> actualFiles = new LinkedHashMap<>(), expectedFiles = new LinkedHashMap<>();
        for (var entry : expected.entrySet()) {
            Path path = Path.of(entry.getKey());
            if (path.getNameCount() > 1 && executable.contains(path.getName(1).toString())) expectedFiles.put(entry.getKey(), entry.getValue().getAsString());
        }
        for (String folder : executable) {
            Path root = directory.resolve("kubejs").resolve(folder);
            if (!Files.exists(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) throw new IllegalStateException("Symlink in catalog test script tree");
                    if (Files.isRegularFile(path)) actualFiles.put(directory.relativize(path).toString().replace('\\', '/'), sha256(Files.readAllBytes(path)));
                }
            }
        }
        require("executable script tree exactly matches original run manifest after cleanup", actualFiles.equals(expectedFiles));
        require("temporary catalog template disappears after cleanup reload", published() == null);
        evidence.addProperty("scriptManifestRestored", true);
    }

    static JsonObject fingerprint(IndustryCatalog.Recipe recipe) {
        JsonObject result = new JsonObject();
        result.addProperty("id", recipe.id()); result.addProperty("title", recipe.title());
        result.addProperty("factory", recipe.factory().name()); result.addProperty("ticks", recipe.ticks()); result.addProperty("fePerTick", recipe.fePerTick());
        result.addProperty("outputNbt", recipe.output().save(new CompoundTag()).toString());
        JsonArray costs = new JsonArray();
        for (ItemStack cost : recipe.cost()) costs.add(cost.save(new CompoundTag()).toString());
        result.add("paidCostNbt", costs);
        return result;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
