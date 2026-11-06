package dev.frontier.arcade.geranium;

import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Separate registry identities: the original coordinate-flown Shahed and its radio remain
 * registered (only hidden from acquisition); ours are hand-flown from the ground station.
 */
public final class GeraniumContent {
    public static final String PATH = "geranium_2_drone", SHAHED_PATH = "shahed_136_drone";
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ArcadeVehicles.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcadeVehicles.ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ArcadeVehicles.ID);
    public static final RegistryObject<EntityType<GeraniumEntity>> TYPE = ENTITIES.register(PATH, () -> type(PATH));
    public static final RegistryObject<EntityType<GeraniumEntity>> SHAHED_TYPE = ENTITIES.register(SHAHED_PATH, () -> type(SHAHED_PATH));
    public static final RegistryObject<Item> ITEM = ITEMS.register(PATH, () -> new GeraniumItem(GeraniumVariant.JET));
    public static final RegistryObject<Item> SHAHED_ITEM = ITEMS.register(SHAHED_PATH, () -> new GeraniumItem(GeraniumVariant.PROPELLER));
    public static final RegistryObject<SoundEvent> JET_ENGINE = SOUNDS.register("geranium_jet_engine",
        () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ArcadeVehicles.ID, "geranium_jet_engine")));
    private GeraniumContent() { }
    private static EntityType<GeraniumEntity> type(String path) {
        return EntityType.Builder.<GeraniumEntity>of(GeraniumEntity::new, MobCategory.MISC).sized(1.5f, .5f)
            .clientTrackingRange(16).updateInterval(1).build(ArcadeVehicles.ID + ":" + path);
    }
    public static void initialize(IEventBus bus) {
        ENTITIES.register(bus); ITEMS.register(bus); SOUNDS.register(bus); GeraniumNet.init();
        bus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey().equals(CreativeModeTabs.COMBAT)) { e.accept(ITEM.get()); e.accept(SHAHED_ITEM.get()); }
        });
    }
}
