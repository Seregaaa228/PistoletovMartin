package dev.frontier.ifv.catalog;

import dev.frontier.ifv.LowpolyIfv;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;

/** Reuses the native tabs with the curated payloads; no duplicate creative tab is added. */
@Mod.EventBusSubscriber(modid = LowpolyIfv.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class StyleCreativeFilter {
    private StyleCreativeFilter() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void filter(BuildCreativeModeTabContentsEvent event) {
        StylePolicy.creativeFilterSeen = true;
        var hidden = new ArrayList<ItemStack>();
        for (var entry : event.getEntries()) {
            if (StylePolicy.blocked(entry.getKey())) hidden.add(entry.getKey());
        }
        hidden.forEach(event.getEntries()::remove);
        StylePolicy.hiddenCreativeCount += hidden.size();
    }
}
