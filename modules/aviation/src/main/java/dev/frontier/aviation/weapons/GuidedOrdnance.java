package dev.frontier.aviation.weapons;

import dev.frontier.aviation.LightAviation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.RegistryObject;

/** Aviation-owned ordnance entity types. Model and texture follow SBW's projectile path convention. */
public final class GuidedOrdnance {
    // Same tracking reach as the industry ballistic/interceptor bodies (32 chunks); the
    // native weapon data names this ID in its "Projectile" field.
    public static final RegistryObject<EntityType<KabEntity>> KAB_500 = LightAviation.ENTITIES.register("kab_500", () ->
            EntityType.Builder.<KabEntity>of(KabEntity::new, MobCategory.MISC).sized(0.5F, 0.5F)
                    .clientTrackingRange(32).updateInterval(1).build(LightAviation.ID + ":kab_500"));

    private GuidedOrdnance() {}

    /** Forces class initialization before the deferred register is attached to the bus. */
    public static void initialize() {}
}
