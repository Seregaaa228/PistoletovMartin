package dev.frontier.arcade;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public final class ArcadeJei implements IModPlugin {
    public static volatile int hiddenCount;
    public static volatile boolean filtered;
    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(ArcadeVehicles.ID, "legacy_vehicle_replacement");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        var manager = runtime.getIngredientManager();
        var replaced = manager.getAllIngredients(VanillaTypes.ITEM_STACK).stream()
            .filter(stack -> VehicleAccessPolicy.isBlocked(stack) || stack.getItem() instanceof dev.frontier.arcade.control.StationLinkItem).toList();
        if (!replaced.isEmpty()) manager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, replaced);
        hiddenCount = replaced.size();
        filtered = manager.getAllIngredients(VanillaTypes.ITEM_STACK).stream().noneMatch(stack -> VehicleAccessPolicy.isBlocked(stack) || stack.getItem() instanceof dev.frontier.arcade.control.StationLinkItem);
    }
}
