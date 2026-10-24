package dev.frontier.industry;

import dev.frontier.industry.combat.CombatContent;
import dev.frontier.industry.combat.CombatEvents;
import dev.frontier.industry.vehicle.VehicleOperations;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(IndustryMod.ID)
public final class IndustryMod {
    public static final String ID = "frontier_industry";
    public IndustryMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER,IndustryConfig.SPEC);
        IndustryContent.register(bus);
        dev.frontier.industry.construction.GeneratedLoot.CODECS.register(bus);
        CombatContent.register(bus);
        VehicleOperations.register(bus);
        IndustryNet.init();
        OperationsNet.init();
        IndustryCommands.init();
        CombatEvents.init();
        VehicleOperations.init();
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.OnDatapackSyncEvent event) -> IndustryCatalog.invalidate());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                IndustryEngine.tick(event.getServer());
                IndustrialEffects.tick(event.getServer());
                VehicleOperations.tick(event.getServer());
            }
        });
    }
}
