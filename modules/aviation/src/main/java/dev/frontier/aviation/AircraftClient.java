package dev.frontier.aviation;

import dev.frontier.aviation.control.client.FlightClient;
import dev.frontier.aviation.control.client.FlightKeys;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = LightAviation.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AircraftClient {
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        FlightKeys.register(event);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            FlightClient.register();
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (AircraftProfile profile : AircraftProfile.values()) {
            event.registerEntityRenderer(profile.entity.get(), AircraftRenderer::new);
        }
    }
}
