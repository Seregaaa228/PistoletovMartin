package dev.frontier.aviation;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.core.registries.Registries;

@Mod(LightAviation.ID)
public final class LightAviation {
    public static final String ID = "frontier_aviation";
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    public LightAviation() {
        FlightControls.initialize();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AircraftCannon.SPEC,
                "frontier-aircraft-combat-server.toml");
        AircraftProfile.values();
        TABS.register("aircraft", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.frontier_aviation"))
                .icon(() -> new ItemStack(AircraftProfile.SU25.item.get()))
                .displayItems((parameters, output) -> {
                    for (AircraftProfile profile : AircraftProfile.values()) output.accept(profile.item.get());
                }).build());
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
    }
}
