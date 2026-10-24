package dev.frontier.ifv;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

/** Lightweight resource replacement: native vehicle identities and mechanics stay authoritative. */
@Mod(LowpolyIfv.ID)
@Mod.EventBusSubscriber(modid = LowpolyIfv.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LowpolyIfv {
    public static final String ID = "frontier_ifv";

    /** Keep the curated replacements together above Forge's merged mod resource pack. */
    @SubscribeEvent
    public static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        var root = ModList.get().getModFileById(ID).getFile().findResource("resourcepacks", ID);
        event.addRepositorySource(consumer -> {
            Pack.ResourcesSupplier resources = name -> new PathPackResources(name, root, true);
            Pack.Info info = Pack.readPackInfo(ID, resources);
            if (info == null) throw new IllegalStateException("Missing Frontier IFV resource-pack metadata");
            Pack pack = Pack.create(ID, Component.literal("Frontier · Единый аркадный стиль"), true,
                    resources, info, PackType.CLIENT_RESOURCES, Pack.Position.TOP, true, PackSource.BUILT_IN);
            consumer.accept(pack);
        });
    }
}
