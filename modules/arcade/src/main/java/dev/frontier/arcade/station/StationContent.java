package dev.frontier.arcade.station;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Common registration only; screen registration lives in the client event subscriber. */
public final class StationContent {
    public static final String PATH = "ground_station";
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ArcadeVehicles.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcadeVehicles.ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ArcadeVehicles.ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ArcadeVehicles.ID);

    public static final RegistryObject<Block> GROUND_STATION = BLOCKS.register(PATH, GroundStationBlock::new);
    public static final RegistryObject<Item> ITEM = ITEMS.register(PATH,
        () -> new BlockItem(GROUND_STATION.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<StationBlockEntity>> TYPE = BLOCK_ENTITIES.register(PATH,
        () -> BlockEntityType.Builder.of(StationBlockEntity::new, GROUND_STATION.get()).build(null));
    public static final RegistryObject<MenuType<StationMenu>> MENU = MENUS.register(PATH,
        () -> IForgeMenuType.create(StationMenu::new));

    private StationContent() { }

    public static void initialize(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        StationNetwork.initialize();
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(CreativeModeTabs.COMBAT)) event.accept(ITEM.get());
        });
    }
}
