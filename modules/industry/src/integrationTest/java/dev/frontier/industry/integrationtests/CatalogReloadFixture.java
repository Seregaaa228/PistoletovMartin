package dev.frontier.industry.integrationtests;

import com.google.gson.JsonObject;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.engine.IndustryData.Site;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Runs real KubeJS resource reloads against a real paid factory queue. */
public final class CatalogReloadFixture {
    private static CatalogFixtureSupport session;
    private static int stage, started, paidProgress;
    private CatalogReloadFixture() {}

    public static boolean tick(MinecraftServer server, ServerPlayer player, Site factory) throws Exception {
        if (session == null) {
            session = new CatalogFixtureSupport(server, player, factory);
            started = server.getTickCount();
            session.clearWork(); session.pause(); session.writeScript(true); session.reload("install-A");
            stage = 1;
        }
        if (server.getTickCount() - started > 2400) throw new IllegalStateException("Real catalog A/B fixture exceeded 2400 ticks");
        switch (stage) {
            case 1 -> {
                if (!session.reloaded()) return false;
                session.require("actual KubeJS reload publishes template A", session.matches(session.published(), true));
                session.restore();
                IndustryEngine.action(player, factory.id, "order", session.templateId, 2);
                session.require("real server action enqueues two custom units", factory.orders.size() == 1 && factory.orders.peek().quantity == 2);
                stage = 2;
            }
            case 2 -> {
                var order = factory.orders.peek();
                if (order == null || !order.reserved || order.progress == 0) return false;
                session.require("first custom unit is paid with template A", session.matches(IndustryEngine.productionRecipe(order, factory.type), true));
                session.require("actual first payment consumes exactly two iron and no redstone", session.inputs(Items.IRON_INGOT) == 18 && session.inputs(Items.REDSTONE) == 20);
                paidProgress = order.progress;
                session.evidence.addProperty("paidAProgressAtReload", paidProgress);
                session.evidence.add("paidTemplateA", CatalogFixtureSupport.fingerprint(IndustryEngine.productionRecipe(order, factory.type)));
                session.pause(); session.writeScript(false); session.reload("replace-A-with-B");
                stage = 3;
            }
            case 3 -> {
                if (!session.reloaded()) return false;
                var order = factory.orders.peek();
                session.require("actual KubeJS reload replaces catalog with template B", session.matches(session.published(), false));
                session.require("paid A output cost duration FE and progress survive real reload", order != null && order.reserved && order.quantity == 2
                    && order.progress == paidProgress && session.matches(IndustryEngine.productionRecipe(order, factory.type), true));
                session.restore(); stage = 4;
            }
            case 4 -> {
                if (session.outputs(IndustryContent.REPAIR_KIT.get()) == 0) return false;
                session.require("first physical output uses original paid template A exactly once", session.outputs(IndustryContent.REPAIR_KIT.get()) == 1
                    && session.outputs(Items.GOLD_INGOT) == 0 && factory.orders.peek() != null && factory.orders.peek().quantity == 1);
                session.require("original paid cost is not charged twice after reload", session.inputs(Items.IRON_INGOT) == 18);
                stage = 5;
            }
            case 5 -> {
                var order = factory.orders.peek();
                if (order == null || !order.reserved) return false;
                session.require("next real unit takes current B output cost duration and FE", order.quantity == 1
                    && session.matches(IndustryEngine.productionRecipe(order, factory.type), false));
                session.require("actual next payment consumes exactly three redstone", session.inputs(Items.IRON_INGOT) == 18 && session.inputs(Items.REDSTONE) == 17);
                session.evidence.add("paidTemplateB", CatalogFixtureSupport.fingerprint(IndustryEngine.productionRecipe(order, factory.type)));
                stage = 6;
            }
            case 6 -> {
                if (!factory.orders.isEmpty()) return false;
                session.require("real A/B queue produces exactly one repair kit and one gold ingot", session.outputs(IndustryContent.REPAIR_KIT.get()) == 1
                    && session.outputs(Items.GOLD_INGOT) == 1 && session.inputs(Items.IRON_INGOT) == 18 && session.inputs(Items.REDSTONE) == 17);
                session.evidence.addProperty("remainingIron", session.inputs(Items.IRON_INGOT));
                session.evidence.addProperty("remainingRedstone", session.inputs(Items.REDSTONE));
                session.evidence.addProperty("repairKitOutput", session.outputs(IndustryContent.REPAIR_KIT.get()));
                session.evidence.addProperty("goldOutput", session.outputs(Items.GOLD_INGOT));
                session.removeScript(); session.reload("cleanup-A-B"); stage = 7;
            }
            case 7 -> {
                if (!session.reloaded()) return false;
                session.verifyScriptRestoration();
                session.evidence.addProperty("elapsedTicks", server.getTickCount() - started);
                stage = 8;
                System.out.println("PIS1_CATALOG_RELOAD_DONE checks=" + session.checks.size());
            }
            case 8 -> { return true; }
            default -> throw new IllegalStateException("Unknown catalog reload stage " + stage);
        }
        return stage == 8;
    }

    public static Map<String, Boolean> checks() { return session == null ? Map.of() : Map.copyOf(session.checks); }
    public static JsonObject receipt() { return session == null ? new JsonObject() : session.evidence.deepCopy(); }

    public static void cleanupOnFailure(MinecraftServer server) {
        if (session == null || !session.ownsScript()) return;
        try { session.removeScript(); session.reload("abort-cleanup"); }
        catch (Exception failure) { System.err.println("PIS1_CATALOG_CLEANUP_FAIL " + failure); }
    }
}
