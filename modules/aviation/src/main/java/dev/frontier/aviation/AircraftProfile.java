package dev.frontier.aviation;

import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import dev.sbw.deepstrike.game.VehicleDeployItem;
import net.minecraftforge.registries.RegistryObject;

/** Stable aircraft identities. The native Su-24 class retains authoritative systems. */
public enum AircraftProfile {
    SU25("su25", new double[][] {{1.03, 2.35, -6.96}, {-1.03, 2.35, -6.96}}),
    F16("f16", new double[][] {{0, 2.46, -7.18}}),
    J10("j10", new double[][] {{0, 2.47, -7.36}});

    public final String id;
    private final double[][] nozzles;
    public final RegistryObject<EntityType<Su24mEntity>> entity;
    public final RegistryObject<Item> item;

    AircraftProfile(String id, double[][] nozzles) {
        this.id = id;
        this.nozzles = nozzles;
        entity = LightAviation.ENTITIES.register(id, () -> EntityType.Builder
                .<Su24mEntity>of(Su24mEntity::new, MobCategory.MISC)
                .sized(8, 6.5F).clientTrackingRange(32).updateInterval(1)
                .build(LightAviation.ID + ":" + id));
        item = LightAviation.ITEMS.register(id, () -> new VehicleDeployItem(entity::get,
                new Item.Properties().stacksTo(1)));
    }

    public double[][] nozzles() { return nozzles; }

    public static AircraftProfile of(Entity plane) {
        for (AircraftProfile profile : values()) {
            if (profile.entity.isPresent() && plane.getType() == profile.entity.get()) return profile;
        }
        return null;
    }
}
