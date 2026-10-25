package dev.frontier.industry.client;

import com.google.gson.JsonObject;
import dev.frontier.industry.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = IndustryMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class IndustryClient {
    private static final Map<UUID, JsonObject> VIEWS = new LinkedHashMap<>();
    private static final Map<UUID, CatalogChunkAssembly> ASSEMBLIES = new LinkedHashMap<>();
    private static final JsonObject EMPTY = new JsonObject();
    public static JsonObject state(UUID site) { return VIEWS.getOrDefault(site, EMPTY); }
    public static String catalogHash(UUID site) {
        CatalogChunkAssembly assembly = ASSEMBLIES.get(site);
        return assembly == null || assembly.completeHash() == null ? "" : assembly.completeHash();
    }
    public static void beginView(UUID site) { VIEWS.remove(site); ASSEMBLIES.remove(site); }
    public static void view(UUID site, JsonObject data, int part, int totalParts) {
        if (!(Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof IndustryMenus.FactoryMenu m && m.site.equals(site))) return;
        if (VIEWS.size() > 32) { VIEWS.clear(); ASSEMBLIES.clear(); }
        CatalogChunkAssembly assembly = ASSEMBLIES.computeIfAbsent(site, ignored -> new CatalogChunkAssembly());
        String catalogHash = data.has("catalogHash") ? data.get("catalogHash").getAsString() : "";
        if (!data.has("catalog")) {
            if (!CatalogChunks.hasMatchingHash(catalogHash, assembly.completeHash())) return;
            com.google.gson.JsonArray catalog = new com.google.gson.JsonArray();
            for (String row : assembly.completeRows()) catalog.add(com.google.gson.JsonParser.parseString(row));
            data.add("catalog", catalog);
        } else {
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (var row : data.getAsJsonArray("catalog")) rows.add(row.toString());
            rows = assembly.accept(catalogHash, part, totalParts, rows);
            if (rows == null) return;
            com.google.gson.JsonArray catalog = new com.google.gson.JsonArray();
            for (String row : rows) catalog.add(com.google.gson.JsonParser.parseString(row));
            data.add("catalog", catalog);
        }
        VIEWS.put(site, data);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent e) { e.enqueueWork(() -> {
        MenuScreens.register(IndustryMenus.FACTORY.get(), FactoryScreen::new);
        net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(IndustryContent.structure("glass"), net.minecraft.client.renderer.RenderType.translucent());
    }); }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(IndustryContent.WORKER.get(), dev.frontier.industry.client.repair.WorkerGeoRenderer::new);
    }

    @Mod.EventBusSubscriber(modid = IndustryMod.ID, value = Dist.CLIENT)
    public static final class Sessions {
        @SubscribeEvent public static void logout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            VIEWS.clear(); ASSEMBLIES.clear(); OperationsScreen.clearSession();
        }
    }
}
