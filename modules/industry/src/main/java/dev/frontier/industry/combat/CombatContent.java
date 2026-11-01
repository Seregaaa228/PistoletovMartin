package dev.frontier.industry.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;

public final class CombatContent {
    public static final String MOD_ID = "frontier_industry";
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<Item> BALLISTIC_MISSILE = ITEMS.register("ballistic_missile", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INTERCEPTOR_AMMO = ITEMS.register("interceptor_ammo", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> MISSILE_LAUNCHER = deployer("missile_launcher", LauncherEntity.Kind.BALLISTIC);
    public static final RegistryObject<Item> S300_LAUNCHER = deployer("s300_launcher", LauncherEntity.Kind.S300);
    public static final RegistryObject<Item> S400_LAUNCHER = deployer("s400_launcher", LauncherEntity.Kind.S400);
    public static final RegistryObject<Item> PATRIOT_LAUNCHER = deployer("patriot_launcher", LauncherEntity.Kind.PATRIOT);
    // Launcher doesn't move; a SAM site stays visible from the air out to the same
    // range as SBW's own aircraft (32 chunks), with the cheap interval kept as-is.
    public static final RegistryObject<EntityType<LauncherEntity>> LAUNCHER = ENTITIES.register("combat_launcher", () ->
            EntityType.Builder.<LauncherEntity>of(LauncherEntity::new, MobCategory.MISC).sized(2.8F, 2.4F)
                    .clientTrackingRange(16).updateInterval(3).build("frontier_industry:combat_launcher"));
    // Matches SBW's own projectile tracking range (64 chunks) so the server view-distance
    // is the only limit, same as native SBW missiles; the render-distance cull is disabled
    // separately in BallisticMissileEntity.
    public static final RegistryObject<EntityType<BallisticMissileEntity>> MISSILE = ENTITIES.register("combat_ballistic_missile", () ->
            EntityType.Builder.<BallisticMissileEntity>of(BallisticMissileEntity::new, MobCategory.MISC).sized(0.45F, 1.3F)
                    .clientTrackingRange(32).updateInterval(1).build("frontier_industry:combat_ballistic_missile"));
    public static final RegistryObject<EntityType<InterceptorEntity>> INTERCEPTOR = ENTITIES.register("combat_interceptor", () ->
            EntityType.Builder.<InterceptorEntity>of(InterceptorEntity::new, MobCategory.MISC).sized(0.3F, 0.7F)
                    .clientTrackingRange(32).updateInterval(1).build("frontier_industry:combat_interceptor"));
    public static final TagKey<EntityType<?>> AIR_TARGETS = TagKey.create(Registries.ENTITY_TYPE,
            new ResourceLocation(MOD_ID, "combat_air_targets"));
    private static final Set<String> CATALOG_IDS = Set.of("ballistic_missile", "interceptor_ammo", "missile_launcher", "s300_launcher", "s400_launcher", "patriot_launcher");
    private CombatContent() { }
    private static RegistryObject<Item> deployer(String id, LauncherEntity.Kind kind) {
        return ITEMS.register(id, () -> new LauncherDeployerItem(kind, new Item.Properties().stacksTo(1)));
    }
    public static void register(IEventBus bus) { ITEMS.register(bus); ENTITIES.register(bus); }
    public static boolean acceptsItem(ResourceLocation id) { return id != null && MOD_ID.equals(id.getNamespace()) && CATALOG_IDS.contains(id.getPath()); }
    public static Item deployer(LauncherEntity.Kind kind) {
        return switch (kind) {
            case BALLISTIC -> MISSILE_LAUNCHER.get();
            case S300 -> S300_LAUNCHER.get();
            case S400 -> S400_LAUNCHER.get();
            case PATRIOT -> PATRIOT_LAUNCHER.get();
        };
    }
}
