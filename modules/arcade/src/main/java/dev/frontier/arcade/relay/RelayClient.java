package dev.frontier.arcade.relay;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.lavafrai.uncomplicatedfpv.client.renderer.AddonDroneRenderer;

/** A relay left in an old world may reach a client for a tick before it removes itself; it still needs a renderer. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RelayClient {
    private RelayClient() { }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RelayContent.TYPE.get(), AddonDroneRenderer<RelayEntity>::new);
    }
}
