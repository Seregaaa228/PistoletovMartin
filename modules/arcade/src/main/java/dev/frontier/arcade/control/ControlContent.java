package dev.frontier.arcade.control;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Station transport is registered for network serialization, never for a creative tab. */
public final class ControlContent {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcadeVehicles.ID);
    public static final RegistryObject<Item> POCKET = ITEMS.register("pocket_controller", PocketControllerItem::new);
    public static final RegistryObject<Item> STATION_LINK = ITEMS.register("station_link", StationLinkItem::new);
    private ControlContent() { }
    public static void initialize(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(CreativeModeTabs.COMBAT)) event.accept(POCKET.get());
        });
    }
}
