package dev.frontier.arcade.relay;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Dedicated support aircraft identity; its wings do not enlarge the collision box. */
public final class RelayContent {
    public static final String PATH = "relay_drone";
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ArcadeVehicles.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcadeVehicles.ID);

    public static final RegistryObject<EntityType<RelayEntity>> TYPE = ENTITIES.register(PATH, () -> EntityType.Builder
        .<RelayEntity>of(RelayEntity::new, MobCategory.MISC).sized(3f, .8f).clientTrackingRange(16).updateInterval(1)
        .build(ArcadeVehicles.ID + ":" + PATH));
    public static final RegistryObject<Item> ITEM = ITEMS.register(PATH, RelayItem::new);

    private RelayContent() { }

    public static void initialize(IEventBus bus) {
        ENTITIES.register(bus);
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(CreativeModeTabs.COMBAT)) event.accept(ITEM.get());
        });
    }
}
