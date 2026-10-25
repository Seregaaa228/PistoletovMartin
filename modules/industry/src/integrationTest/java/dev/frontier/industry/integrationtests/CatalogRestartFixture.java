package dev.frontier.industry.integrationtests;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.WorkerEntity;
import dev.frontier.industry.engine.IndustryData.Site;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;

/** Persists a real paid custom unit, then verifies it in a separate JVM restart. */
public final class CatalogRestartFixture {
    private static final String RECEIPT = "pis1-catalog-restart-receipt.json";
    private static CatalogFixtureSupport seed, resumed;
    private static int seedStage, seedStarted, frozenProgress, resumeStage, resumedAt, completedAt;
    private static JsonObject saved;
    private CatalogRestartFixture() {}

    public static boolean prepare(MinecraftServer server, ServerPlayer player, Site factory) throws Exception {
        if (seed == null) {
            seed = new CatalogFixtureSupport(server, player, factory);
            seedStarted = server.getTickCount();
            seed.clearWork();
            var worker = seed.data.workers.values().stream().filter(candidate -> candidate.active()
                && factory.id.equals(candidate.site) && factory.owner.equals(candidate.owner)
                && IndustryEngine.find(server, candidate.id) instanceof WorkerEntity).findFirst()
                .orElseThrow(() -> new IllegalStateException("Restart seeding requires an existing physical fixture worker"));
            IndustryEngine.action(player, factory.id, "assign", worker.id.toString(), 0);
            seed.require("existing fixture worker assigned to real production for restart seed", "production".equals(worker.role));
            var entity = (WorkerEntity) IndustryEngine.find(server, worker.id);
            entity.getNavigation().stop();
            entity.moveTo(factory.origin.getX() + .5, factory.origin.getY() + 1, factory.origin.getZ() - 3.5, 0, 0);
            seed.pause(); seed.writeScript(false); seed.reload("install-restart-B");
            seedStage = 1;
        }
        if (server.getTickCount() - seedStarted > 1800) throw new IllegalStateException("Actual restart seed exceeded 1800 ticks");
        switch (seedStage) {
            case 1 -> {
                if (!seed.reloaded()) return false;
                seed.require("actual KubeJS reload publishes restart template B", seed.matches(seed.published(), false));
                seed.restore();
                IndustryEngine.action(player, factory.id, "order", seed.templateId, 1);
                seed.require("restart seed accepted through real order action", factory.orders.size() == 1 && factory.orders.peek().quantity == 1);
                seedStage = 2;
            }
            case 2 -> {
                var order = factory.orders.peek();
                if (order == null || !order.reserved || order.progress < 5) return false;
                seed.require("restart seed is a real paid incomplete B unit", order.progress < 40 && seed.matches(IndustryEngine.productionRecipe(order, factory.type), false));
                seed.require("restart seed actually charged three redstone exactly once", seed.inputs(Items.IRON_INGOT) == 20
                    && seed.inputs(Items.REDSTONE) == 17 && seed.outputs(Items.GOLD_INGOT) == 0);
                frozenProgress = order.progress;
                seed.pause(); seed.removeScript(); seed.reload("remove-template-before-save");
                seedStage = 3;
            }
            case 3 -> {
                if (!seed.reloaded()) return false;
                seed.verifyScriptRestoration();
                var order = factory.orders.peek();
                seed.require("paid seed survives actual template removal reload without progress or repayment", order != null && order.reserved
                    && order.progress == frozenProgress && order.quantity == 1 && seed.matches(IndustryEngine.productionRecipe(order, factory.type), false)
                    && seed.inputs(Items.REDSTONE) == 17 && seed.outputs(Items.GOLD_INGOT) == 0);
                saved = new JsonObject();
                saved.addProperty("runId", System.getProperty("frontier.pis1RunId"));
                saved.addProperty("templateId", seed.templateId);
                saved.addProperty("factoryId", factory.id.toString());
                saved.addProperty("distributionId", seed.distribution.id.toString());
                saved.addProperty("stationId", seed.station.id.toString());
                saved.addProperty("queueSize", factory.orders.size());
                saved.addProperty("quantity", order.quantity); saved.addProperty("progress", order.progress);
                saved.addProperty("reserved", order.reserved);
                saved.addProperty("cutPosition", seed.cutPosition().asLong());
                saved.addProperty("inventoryNbt", factory.inventory.serializeNBT().toString());
                saved.add("paidTemplate", CatalogFixtureSupport.fingerprint(IndustryEngine.productionRecipe(order, factory.type)));
                JsonArray workers = new JsonArray();
                seed.data.workers.values().stream().filter(candidate -> candidate.active()).map(candidate -> candidate.id.toString()).sorted().forEach(workers::add);
                saved.add("workerIds", workers);
                saved.addProperty("originalScriptsRestored", true);
                saved.addProperty("removedTemplateAbsent", seed.published() == null);
                Path path = FMLPaths.GAMEDIR.get().resolve(RECEIPT);
                byte[] bytes = (new GsonBuilder().setPrettyPrinting().create().toJson(saved) + "\n").getBytes(StandardCharsets.UTF_8);
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
                seed.evidence.addProperty("diskReceipt", RECEIPT);
                seed.evidence.addProperty("diskReceiptSha256", CatalogFixtureSupport.sha256(bytes));
                seed.evidence.addProperty("frozenProgress", frozenProgress);
                seed.data.setDirty();
                server.saveEverything(false, true, true);
                seed.require("real paid custom B persisted with upstream cut and source script removed", true);
                seedStage = 4;
                System.out.println("PIS1_CATALOG_RESTART_SEEDED progress=" + frozenProgress + " receipt=" + path);
            }
            case 4 -> { return true; }
            default -> throw new IllegalStateException("Unknown restart seed stage " + seedStage);
        }
        return seedStage == 4;
    }

    public static boolean tickRestart(MinecraftServer server, ServerPlayer player, Site factory) throws Exception {
        if (resumed == null) {
            resumed = new CatalogFixtureSupport(server, player, factory);
            resumedAt = server.getTickCount();
            Path path = FMLPaths.GAMEDIR.get().resolve(RECEIPT);
            if (!Files.isRegularFile(path) || Files.size(path) > 65536) throw new IllegalStateException("Missing/oversized real catalog restart receipt");
            byte[] bytes = Files.readAllBytes(path);
            saved = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            resumed.require("actual restart receipt matches this exact run and factory", System.getProperty("frontier.pis1RunId").equals(saved.get("runId").getAsString())
                && factory.id.toString().equals(saved.get("factoryId").getAsString()) && resumed.templateId.equals(saved.get("templateId").getAsString())
                && resumed.station.id.toString().equals(saved.get("stationId").getAsString()) && resumed.distribution.id.toString().equals(saved.get("distributionId").getAsString()));
            var order = factory.orders.peek();
            resumed.require("actual JVM restart retains exact reserved queue quantity and progress", order != null && order.reserved && saved.get("reserved").getAsBoolean()
                && factory.orders.size() == saved.get("queueSize").getAsInt() && order.quantity == saved.get("quantity").getAsInt() && order.progress == saved.get("progress").getAsInt());
            resumed.require("actual JVM restart retains exact paid output costs duration FE and title", order != null
                && saved.getAsJsonObject("paidTemplate").equals(CatalogFixtureSupport.fingerprint(IndustryEngine.productionRecipe(order, factory.type))));
            resumed.require("actual JVM restart retains exact inventory and paid costs without a second charge",
                factory.inventory.serializeNBT().equals(TagParser.parseTag(saved.get("inventoryNbt").getAsString()))
                    && resumed.inputs(Items.IRON_INGOT) == 20 && resumed.inputs(Items.REDSTONE) == 17 && resumed.outputs(Items.GOLD_INGOT) == 0);
            JsonArray actualWorkers = new JsonArray();
            resumed.data.workers.values().stream().filter(candidate -> candidate.active()).map(candidate -> candidate.id.toString()).sorted().forEach(actualWorkers::add);
            resumed.require("actual JVM restart retains every exact worker ledger UUID", actualWorkers.equals(saved.getAsJsonArray("workerIds")));
            resumed.require("removed custom template is absent on new JVM startup", resumed.published() == null);
            resumed.verifyScriptRestoration();
            resumed.evidence.addProperty("diskReceiptSha256", CatalogFixtureSupport.sha256(bytes));
            resumed.evidence.add("savedPaidUnit", saved.deepCopy());
            resumeStage = 1;
        }
        if (server.getTickCount() - resumedAt > 1200) throw new IllegalStateException("Actual paid restart resumption exceeded 1200 ticks");
        switch (resumeStage) {
            case 1 -> {
                // Entity/chunk reload is asynchronous. Do not mint a replacement
                // worker to make the test pass; wait for the saved original one.
                if (IndustryEngine.crew(server, resumed.data, factory, "production").isEmpty()) return false;
                resumed.restoreFromReceipt(BlockPos.of(saved.get("cutPosition").getAsLong()));
                resumeStage = 2;
            }
            case 2 -> {
                if (!factory.orders.isEmpty()) return false;
                resumed.require("restoring upstream completes the one exact paid B output after actual restart", resumed.outputs(Items.GOLD_INGOT) == 1
                    && resumed.inputs(Items.IRON_INGOT) == 20 && resumed.inputs(Items.REDSTONE) == 17);
                completedAt = server.getTickCount(); resumeStage = 3;
            }
            case 3 -> {
                if (server.getTickCount() - completedAt < 80) return false;
                resumed.require("completed paid unit never duplicates output or debit on later live ticks", factory.orders.isEmpty()
                    && resumed.outputs(Items.GOLD_INGOT) == 1 && resumed.inputs(Items.REDSTONE) == 17 && resumed.inputs(Items.IRON_INGOT) == 20);
                resumed.evidence.addProperty("completedExactlyOnce", true);
                resumed.evidence.addProperty("elapsedTicks", server.getTickCount() - resumedAt);
                resumeStage = 4;
                System.out.println("PIS1_CATALOG_RESTART_DONE checks=" + resumed.checks.size());
            }
            case 4 -> { return true; }
            default -> throw new IllegalStateException("Unknown restart resume stage " + resumeStage);
        }
        return resumeStage == 4;
    }

    public static Map<String, Boolean> checks() {
        var result = new java.util.LinkedHashMap<String, Boolean>();
        if (seed != null) seed.checks.forEach((name, value) -> result.put("seed: " + name, value));
        if (resumed != null) resumed.checks.forEach((name, value) -> result.put("resume: " + name, value));
        return Map.copyOf(result);
    }

    public static JsonObject receipt() {
        return resumed != null ? resumed.evidence.deepCopy() : seed == null ? new JsonObject() : seed.evidence.deepCopy();
    }

    public static void cleanupOnFailure(MinecraftServer server) {
        if (seed == null || !seed.ownsScript()) return;
        try { seed.removeScript(); seed.reload("restart-seed-abort-cleanup"); }
        catch (Exception failure) { System.err.println("PIS1_CATALOG_RESTART_CLEANUP_FAIL " + failure); }
    }
}
