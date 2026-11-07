package dev.frontier.arcade.deploy;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Picks the quick-deploy item's icon from its vehicle: the item model overrides on this predicate. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VehicleDeployClient {
    private VehicleDeployClient() { }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(VehicleDeploy.ITEM.get(), new ResourceLocation(ArcadeVehicles.ID, "vehicle"),
            (stack, level, entity, seed) -> (VehicleDeploy.ORDER.indexOf(VehicleDeploy.path(stack)) + 1) / 100f));
    }
}
