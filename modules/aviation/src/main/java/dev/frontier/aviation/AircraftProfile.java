package dev.frontier.aviation;

import dev.sbw.deepstrike.vehicle.Su24mEntity;
import dev.frontier.aviation.aircraft.AircraftCatalog;
import dev.frontier.aviation.aircraft.AircraftDefinition;
import dev.frontier.aviation.aircraft.AircraftModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;
import dev.sbw.deepstrike.game.VehicleDeployItem;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;

/** Registers every owned model from the catalog; existing entity/item IDs remain unchanged. */
public final class AircraftProfile {
    private static final Map<ResourceLocation, AircraftProfile> BY_TYPE = new LinkedHashMap<>();
    private static final List<AircraftProfile> VALUES = AircraftCatalog.models().stream()
        .map(AircraftProfile::new).toList();
    // Source compatibility for existing model-specific visuals and fixtures.
    public static final AircraftProfile SU25 = BY_TYPE.get(new ResourceLocation("frontier_aviation:su25"));
    public static final AircraftProfile F16 = BY_TYPE.get(new ResourceLocation("frontier_aviation:f16"));
    public static final AircraftProfile J10 = BY_TYPE.get(new ResourceLocation("frontier_aviation:j10"));

    public final String id;
    private final AircraftDefinition definition;
    public final RegistryObject<EntityType<Su24mEntity>> entity;
    public final RegistryObject<Item> item;

    private AircraftProfile(AircraftDefinition definition) {
        this.definition = definition;
        if (!definition.typeId().startsWith(LightAviation.ID + ":"))
            throw new IllegalArgumentException("owned aircraft must use the mod namespace");
        this.id = definition.typeId().substring(LightAviation.ID.length() + 1);
        var model = definition.model();
        entity = LightAviation.ENTITIES.register(id, () -> EntityType.Builder
                .<Su24mEntity>of(Su24mEntity::new, MobCategory.MISC)
                .sized(model.width(), model.height()).clientTrackingRange(32).updateInterval(1)
                .build(LightAviation.ID + ":" + id));
        item = LightAviation.ITEMS.register(id, () -> new VehicleDeployItem(entity::get,
                new Item.Properties().stacksTo(1)));
        BY_TYPE.put(new ResourceLocation(definition.typeId()), this);
    }

    public AircraftDefinition definition() { return definition; }
    public List<AircraftModel.Exhaust> nozzles() { return definition.model().exhausts(); }
    public static List<AircraftProfile> values() { return VALUES; }

    public static AircraftProfile of(Entity plane) {
        if (plane == null) return null;
        var type = ForgeRegistries.ENTITY_TYPES.getKey(plane.getType());
        return type == null ? null : BY_TYPE.get(type);
    }
}
