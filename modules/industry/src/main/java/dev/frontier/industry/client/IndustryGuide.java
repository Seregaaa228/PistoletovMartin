package dev.frontier.industry.client;

import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliAPI;

/** Client entry point kept out of common/server initialization. */
public final class IndustryGuide {
    private static final ResourceLocation BOOK = new ResourceLocation("frontier_industry", "field_guide");

    private IndustryGuide() {}

    public static void open() {
        PatchouliAPI.get().openBookGUI(BOOK);
    }
}
