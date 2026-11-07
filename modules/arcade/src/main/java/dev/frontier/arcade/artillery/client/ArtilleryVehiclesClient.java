package dev.frontier.arcade.artillery.client;

import com.atsuishio.superbwarfare.client.renderer.entity.Plz05Renderer;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.artillery.ArtilleryVehicles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** SBW's PLZ-05 renderer draws our guns from their own model data; the skeletons keep PLZ-05's bone names. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ArtilleryVehiclesClient {
    private ArtilleryVehiclesClient() { }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        for (var type : ArtilleryVehicles.ALL) event.registerEntityRenderer(type.get(), Plz05Renderer::new);
    }
}
