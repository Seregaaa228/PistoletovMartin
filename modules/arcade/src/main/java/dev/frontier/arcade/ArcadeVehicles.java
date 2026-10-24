package dev.frontier.arcade;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Set;

/** Keeps SBW entities registered for saved worlds and the FPV dependency. */
@Mod(ArcadeVehicles.ID)
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ArcadeVehicles {
    public static final String ID = "frontier_arcade_vehicles";
    public ArcadeVehicles() {
        dev.frontier.arcade.control.ControlContent.initialize(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        dev.frontier.arcade.station.StationContent.initialize(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        dev.frontier.arcade.relay.RelayContent.initialize(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        dev.frontier.arcade.geranium.GeraniumContent.initialize(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        dev.frontier.arcade.uav.NativeUavCamera.initialize(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
    }
    /** Compatibility alias: paths of SBW vehicles excluded from acquisition. */
    public static final Set<String> REPLACED = VehicleAccessPolicy.BLOCKED_SBW_PATHS;

    public static boolean isBlocked(ItemStack stack) {
        return VehicleAccessPolicy.isBlocked(stack);
    }

    public static boolean isBlocked(Entity entity) {
        return VehicleAccessPolicy.isBlocked(entity);
    }

    public static boolean isReplaced(ItemStack stack) {
        return isBlocked(stack);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void creative(BuildCreativeModeTabContentsEvent event) {
        var remove = new ArrayList<ItemStack>();
        for (var entry : event.getEntries()) {
            if (isBlocked(entry.getKey())) remove.add(entry.getKey());
        }
        remove.forEach(event.getEntries()::remove);
    }
}
