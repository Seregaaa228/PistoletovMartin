package dev.frontier.aviation.mixin;

import dev.frontier.aviation.ordnance.F16Loadout;
import dev.frontier.aviation.ordnance.F16Stations;
import dev.frontier.aviation.ordnance.F16StormLoader;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Su24mEntity.class, remap = false)
public abstract class F16StormStoreLimit implements F16StormLoader {
    @Shadow @Final private ItemStack[] stores;
    @Shadow @Final private Su24mEntity.Order[] orders;
    @Shadow @Final private String[] messages;
    @Shadow @Final private List<RoutePoint>[] routes;
    @Shadow private void syncRacks() { throw new AssertionError(); }
    @Inject(method = "loadStore", at = @At("HEAD"), cancellable = true)
    private void aviation$limitStormStore(ServerPlayer player, int slot, CallbackInfoReturnable<Boolean> result) {
        if (!F16Loadout.canLoadStormShadow((Su24mEntity) (Object) this, slot)) result.setReturnValue(false);
    }

    @Override
    public void aviation$loadInventoryStores() {
        Su24mEntity plane = (Su24mEntity) (Object) this;
        if (!F16Loadout.supports(plane) || plane.level().isClientSide || !plane.parked()) return;
        var inventory = plane.getInventory();
        for (int rack = 0; rack < 2; rack++) {
            if (!stores[rack].isEmpty() || orders[rack] != null) continue;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (F16Loadout.type(stack) != F16Stations.Store.STORM_SHADOW) continue;
                MissileData data = MissileData.fromItemStack(stack);
                if (data == null || !data.isFueled() || !data.supportsWarhead()) continue;
                if (!F16Loadout.fits(plane)) return;
                ItemStack extracted = inventory.extractItem(slot, 1, false);
                if (extracted.isEmpty()) continue;
                stores[rack] = extracted;
                syncRacks();
                messages[rack] = routes[rack].isEmpty() ? "loaded" : "ready";
                break;
            }
        }
    }
}
