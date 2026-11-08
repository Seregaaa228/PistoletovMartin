package dev.frontier.arcade.airdefense.client;

import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.airdefense.AirDefense;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Renderers for the sites and their missile, and the client half of the air defence. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SamClient {
    private SamClient() { }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        for (var type : AirDefense.SITES) event.registerEntityRenderer(type.get(), SamSiteRenderer::new);
        event.registerEntityRenderer(AirDefense.MISSILE.get(), SamMissileRenderer::new);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        AirDefense.setClient(new SamClientSide());
    }
}
