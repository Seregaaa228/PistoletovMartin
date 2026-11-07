package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.vehicle.Plz05Entity;
import dev.frontier.arcade.ArcadeVehicles;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Our own self-propelled guns and rocket launcher on SBW's PLZ-05 entity class and renderer: the same native
 * gun, seats, aiming and fire-mission tablet, with their own vehicle data (sbw/vehicles/&lt;id&gt;.json) and model.
 * Registered like SBW's own vehicles: tracked to 512 blocks, updated every tick, fire immune.
 */
public final class ArtilleryVehicles {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ArcadeVehicles.ID);
    /** 2S19-style tracked 152 mm howitzer. */
    public static final RegistryObject<EntityType<Plz05Entity>> MSTA_S = register("msta_s", 4.8f, 3.4f);
    /** 2S22-style wheeled 155 mm howitzer. */
    public static final RegistryObject<EntityType<Plz05Entity>> BOHDANA = register("bohdana", 3.6f, 3.4f);
    /** BM-21-style 40-tube 122 mm rocket launcher. */
    public static final RegistryObject<EntityType<Plz05Entity>> GRAD = register("grad", 3.2f, 3.3f);
    public static final List<RegistryObject<EntityType<Plz05Entity>>> ALL = List.of(MSTA_S, BOHDANA, GRAD);

    private ArtilleryVehicles() { }

    public static void initialize(IEventBus bus) {
        ENTITIES.register(bus);
    }

    public static boolean isGrad(EntityType<?> type) {
        return type == GRAD.get();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(ArcadeVehicles.ID, path);
    }

    @SuppressWarnings("unchecked")
    private static RegistryObject<EntityType<Plz05Entity>> register(String path, float width, float height) {
        return ENTITIES.register(path, () -> EntityType.Builder
            .<Plz05Entity>of((type, level) -> new Plz05Entity((EntityType<Plz05Entity>) (EntityType<?>) type, level), MobCategory.MISC)
            .sized(width, height).setTrackingRange(512).setUpdateInterval(1).fireImmune()
            .build(ArcadeVehicles.ID + ":" + path));
    }
}
