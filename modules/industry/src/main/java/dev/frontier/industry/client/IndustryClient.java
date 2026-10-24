package dev.frontier.industry.client;

import com.google.gson.JsonObject;
import dev.frontier.industry.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
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
    public static JsonObject state(UUID site) { return VIEWS.getOrDefault(site, new JsonObject()); }
    public static void view(UUID site, JsonObject data) {
        if (!(Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof IndustryMenus.FactoryMenu m && m.site.equals(site))) return;
        if (VIEWS.size() > 32) VIEWS.clear(); VIEWS.put(site, data);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent e) { e.enqueueWork(() -> {
        MenuScreens.register(IndustryMenus.FACTORY.get(), FactoryScreen::new);
        net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(IndustryContent.structure("glass"), net.minecraft.client.renderer.RenderType.translucent());
    }); }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(IndustryContent.WORKER.get(), c -> new MobRenderer<WorkerEntity, VillagerModel<WorkerEntity>>(c, new VillagerModel<>(c.bakeLayer(ModelLayers.VILLAGER)), .5f) {
            @Override public ResourceLocation getTextureLocation(WorkerEntity worker) { return new ResourceLocation("minecraft", "textures/entity/villager/villager.png"); }
        });
    }
}
