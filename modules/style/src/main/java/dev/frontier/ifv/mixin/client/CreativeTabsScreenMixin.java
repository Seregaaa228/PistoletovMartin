package dev.frontier.ifv.mixin.client;

import dev.frontier.ifv.catalog.FrontierCreativeTabs;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.common.CreativeModeTabRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** Remove redundant source tabs from the visible pages, without changing their contents. */
@Mixin(value = CreativeModeInventoryScreen.class, remap = false)
public abstract class CreativeTabsScreenMixin {
    @Redirect(method = "m_7856_()V", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/common/CreativeModeTabRegistry;getSortedCreativeModeTabs()Ljava/util/List;"), remap = false)
    private List<CreativeModeTab> frontier$hideRedundantTabs() {
        return CreativeModeTabRegistry.getSortedCreativeModeTabs().stream()
                .filter(tab -> !FrontierCreativeTabs.hideRedundantTab(tab)).toList();
    }
}
