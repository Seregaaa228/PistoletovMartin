package dev.frontier.aviation.systemstest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the creative tabs on the real client and checks that «Летательный транспорт»
 * holds every aircraft (ours, DeepStrike's Su-24M, SBW's planes and helicopters) and
 * that no other tab still lists one of them.
 */
final class CreativeTabScenario {
    /**
     * Su-25, F-16, J-10, DeepStrike's Su-24M, SBW's AH-6 and Mi-28. The pack's style
     * module (StylePolicy.KEPT_ENTITY_PATHS) hides SBW's other planes from creative on
     * purpose, in this tab too.
     */
    private static final int EXPECTED = 6;
    private final JsonObject result = new JsonObject();
    private int ticks;

    CreativeTabScenario() { result.addProperty("pass", false); }

    boolean tick() {
        if (++ticks < 5) return false;
        try {
            Minecraft.getInstance().submit(this::inspect).get();
        } catch (Exception error) {
            result.addProperty("error", error.toString());
        }
        return true;
    }

    private void inspect() {
        var mc = Minecraft.getInstance();
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
        CreativeModeTab ours = BuiltInRegistries.CREATIVE_MODE_TAB.get(new ResourceLocation("frontier_aviation", "aircraft"));
        JsonArray names = new JsonArray();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ItemStack stack : ours.getDisplayItems()) {
            String id = id(stack);
            ids.add(id);
            names.add(id);
        }
        result.add("aircraftTab", names);
        result.addProperty("title", ours.getDisplayName().getString());
        JsonArray leftovers = new JsonArray();
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            if (tab == ours || tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            for (ItemStack stack : tab.getDisplayItems())
                if (ids.contains(id(stack))) leftovers.add(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab) + " " + id(stack));
        }
        result.add("stillElsewhere", leftovers);
        result.addProperty("pass", names.size() == EXPECTED && leftovers.isEmpty());
    }

    /** Item id, or the vehicle a SBW container holds. */
    private static String id(ItemStack stack) {
        var tag = stack.getTag() == null ? null : stack.getTag().getCompound("BlockEntityTag");
        if (tag != null && tag.contains("EntityType")) return tag.getString("EntityType");
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    JsonObject result() { return result; }
}
