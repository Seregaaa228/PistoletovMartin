package dev.frontier.aviation;

import com.atsuishio.superbwarfare.item.container.ContainerBlockItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * «Летательный транспорт»: every aircraft of the pack in one creative tab. Ours and
 * DeepStrike's Su-24M are deploy items; SBW's planes and helicopters are SBW vehicle
 * containers. They are taken out of the tabs they came from.
 */
public final class AircraftCreativeTab {
    /**
     * SBW vehicles whose data Type is Airplane or Helicopter (0.8.9.1). The pack's style
     * module still hides those it does not keep (StylePolicy), here as everywhere.
     */
    private static final List<String> SBW_AIRCRAFT = List.of(
        "a_10a", "ac_130h", "j_16", "kv_16", "ju_87", "tom_6", "ah_6", "mi_28");
    private static final ResourceLocation SU24M = new ResourceLocation("deepstrike", "su24m");

    private AircraftCreativeTab() {}

    static CreativeModeTab create() {
        return CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.frontier_aviation"))
            .icon(() -> new ItemStack(AircraftProfile.SU25.item.get()))
            .displayItems((parameters, output) -> stacks().forEach(output::accept))
            .build();
    }

    private static List<ItemStack> stacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (AircraftProfile profile : AircraftProfile.values()) stacks.add(new ItemStack(profile.item.get()));
        Item su24m = ForgeRegistries.ITEMS.getValue(SU24M);
        if (su24m != null && ForgeRegistries.ITEMS.getKey(su24m).equals(SU24M)) stacks.add(new ItemStack(su24m));
        for (String id : SBW_AIRCRAFT) {
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare", id));
            if (type != null && ForgeRegistries.ENTITY_TYPES.getKey(type).getPath().equals(id))
                stacks.add(ContainerBlockItem.Companion.createInstance(type));
        }
        return stacks;
    }

    /** Removes the aircraft from every other tab (SBW vehicles, DeepStrike). */
    static void moveOut(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().location().equals(new ResourceLocation(LightAviation.ID, "aircraft"))) return;
        Set<String> sbw = Set.copyOf(SBW_AIRCRAFT);
        List<ItemStack> remove = new ArrayList<>();
        for (var entry : event.getEntries()) {
            ItemStack stack = entry.getKey();
            ResourceLocation item = ForgeRegistries.ITEMS.getKey(stack.getItem());
            boolean aircraft = SU24M.equals(item);
            for (AircraftProfile profile : AircraftProfile.values()) aircraft |= stack.is(profile.item.get());
            var tag = stack.getTag() == null ? null : stack.getTag().getCompound("BlockEntityTag");
            if (tag != null && tag.contains("EntityType")) {
                ResourceLocation type = ResourceLocation.tryParse(tag.getString("EntityType"));
                aircraft |= type != null && type.getNamespace().equals("superbwarfare") && sbw.contains(type.getPath());
            }
            if (aircraft) remove.add(stack);
        }
        remove.forEach(event.getEntries()::remove);
    }
}
