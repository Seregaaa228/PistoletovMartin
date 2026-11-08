package dev.frontier.arcade.airdefense;

import dev.frontier.arcade.ArcadeVehicles;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Air defence: the S-125, S-300, S-400, Pantsir and Patriot complexes and their missile. Sites are SBW
 * vehicles with their own data (sbw/vehicles/&lt;id&gt;.json) and models; registered like SBW's own
 * vehicles. The missile is tracked like SBW projectiles but updated only every third tick.
 */
public final class AirDefense {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ArcadeVehicles.ID);
    private static final Map<EntityType<?>, SamSystem> SYSTEMS = new IdentityHashMap<>();
    public static final RegistryObject<EntityType<SamSiteEntity>> S125 = site(SamSystem.S125, 3.0f, 3.2f);
    public static final RegistryObject<EntityType<SamSiteEntity>> S300 = site(SamSystem.S300, 3.4f, 3.6f);
    public static final RegistryObject<EntityType<SamSiteEntity>> S400 = site(SamSystem.S400, 3.4f, 3.6f);
    public static final RegistryObject<EntityType<SamSiteEntity>> PANTSIR = site(SamSystem.PANTSIR, 3.2f, 3.6f);
    public static final RegistryObject<EntityType<SamSiteEntity>> PATRIOT = site(SamSystem.PATRIOT, 3.4f, 3.6f);
    public static final List<RegistryObject<EntityType<SamSiteEntity>>> SITES = List.of(S125, S300, S400, PANTSIR, PATRIOT);
    public static final RegistryObject<EntityType<SamMissileEntity>> MISSILE = ENTITIES.register("sam_missile", () -> EntityType.Builder
        .<SamMissileEntity>of(SamMissileEntity::new, MobCategory.MISC).sized(0.5f, 0.5f)
        .setTrackingRange(32).setUpdateInterval(3).setShouldReceiveVelocityUpdates(false).fireImmune().noSave()
        .build(ArcadeVehicles.ID + ":sam_missile"));

    /** Client side of the air defence; a no-op on a dedicated server. */
    public interface Client {
        default void picture(SamNetwork.Picture picture) { }
        default void burst(SamNetwork.Burst burst) { }
        default void warning(SamNetwork.Warning warning) { }
        /** A missile new to this client; {@code fresh} when it left the rail just now (else no launch effects). */
        default void launch(SamMissileEntity missile, boolean fresh) { }
        default void trail(SamMissileEntity missile) { }
    }

    private static Client client = new Client() { };

    private AirDefense() { }

    public static void initialize(IEventBus bus) {
        ENTITIES.register(bus);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER,
            AirDefenseConfig.SPEC, "frontier-air-defense-server.toml");
        SamNetwork.initialize();
        MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> AirPicture.join(event));
        MinecraftForge.EVENT_BUS.addListener((EntityLeaveLevelEvent event) -> AirPicture.leave(event));
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> AirPicture.unload(event));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> SamSiteEntity.clearEngagements());
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.LevelTickEvent event) -> SamMissileEntity.stepFrozen(event));
        MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> LegacyLaunchers.join(event));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent event) -> LegacyLaunchers.tick(event));
    }

    public static SamSystem system(EntityType<?> type) {
        SamSystem system = SYSTEMS.get(type);
        if (system == null) throw new IllegalStateException("not an air-defence site: " + type);
        return system;
    }

    public static Client client() { return client; }
    public static Client clientEffects() { return client; }
    public static void setClient(Client side) { client = side; }

    private static RegistryObject<EntityType<SamSiteEntity>> site(SamSystem system, float width, float height) {
        return ENTITIES.register(system.id, () -> {
            EntityType<SamSiteEntity> type = EntityType.Builder.<SamSiteEntity>of(SamSiteEntity::new, MobCategory.MISC)
                .sized(width, height).setTrackingRange(512).setUpdateInterval(1).fireImmune()
                .build(ArcadeVehicles.ID + ":" + system.id);
            SYSTEMS.put(type, system);
            return type;
        });
    }
}
