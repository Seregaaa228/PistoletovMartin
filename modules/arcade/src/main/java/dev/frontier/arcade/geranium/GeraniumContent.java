package dev.frontier.arcade.geranium;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Separate registry identity: the original Shahed and its coordinate radio remain untouched. */
public final class GeraniumContent {
    public static final String PATH = "geranium_2_drone";
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ArcadeVehicles.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcadeVehicles.ID);
    public static final RegistryObject<EntityType<GeraniumEntity>> TYPE = ENTITIES.register(PATH, () -> EntityType.Builder
        .<GeraniumEntity>of(GeraniumEntity::new, MobCategory.MISC).sized(1.5f, .5f).clientTrackingRange(16).updateInterval(1)
        .build(ArcadeVehicles.ID + ":" + PATH));
    public static final RegistryObject<Item> ITEM = ITEMS.register(PATH, GeraniumItem::new);
    private GeraniumContent() { }
    public static void initialize(IEventBus bus) {
        ENTITIES.register(bus); ITEMS.register(bus); GeraniumNet.init();
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey().equals(CreativeModeTabs.COMBAT)) e.accept(ITEM.get());
        });
    }
}
