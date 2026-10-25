package dev.frontier.industry.client.repair;

import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.repair.RepairContent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** This subscriber is discovered only on the client; server registration has no renderer references. */
@Mod.EventBusSubscriber(modid = IndustryMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RepairClient {
    private RepairClient() {}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RepairContent.PRESS.get(), context -> new PressGeoRenderer());
    }
}
