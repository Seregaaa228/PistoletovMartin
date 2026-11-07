package dev.frontier.arcade.artillery;

import com.atsuishio.superbwarfare.entity.vehicle.base.SpArtilleryEntity;
import dev.frontier.arcade.ArcadeVehicles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A new self-propelled gun arrives ready to fight: full battery and a first load of shells (rockets for the
 * launcher) in its own inventory.
 * Granted once per vehicle; the mark travels in the vehicle's saved data, so packing and redeploying it, or a chunk
 * reload, never refills it.
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ArtillerySupply {
    private static final String SUPPLIED = "frontier_artillery_supplied";
    private static final ResourceLocation HE = new ResourceLocation("superbwarfare", "large_shell_he");
    private static final ResourceLocation AP = new ResourceLocation("superbwarfare", "large_shell_ap");
    private static final ResourceLocation ROCKET_HE = new ResourceLocation("superbwarfare", "medium_rocket_he");
    private static final int HE_SHELLS = 24, AP_SHELLS = 8;
    /** Two full 40-rocket salvos for the launcher. */
    private static final int ROCKETS = 80;

    private ArtillerySupply() { }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk() || !(event.getEntity() instanceof SpArtilleryEntity gun)) return;
        var data = gun.getPersistentData();
        if (data.getBoolean(SUPPLIED)) return;
        data.putBoolean(SUPPLIED, true);
        gun.setEnergy(gun.getMaxEnergy());
        if (ArtilleryVehicles.isGrad(gun.getType())) {
            stock(gun, 0, ROCKET_HE, ROCKETS);
            return;
        }
        int slot = 0;
        slot = stock(gun, slot, HE, HE_SHELLS);
        stock(gun, slot, AP, AP_SHELLS);
    }

    /** Puts shells into the first empty slots from {@code slot}; returns the next slot to try. */
    private static int stock(SpArtilleryEntity gun, int slot, ResourceLocation id, int count) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return slot;
        var inventory = gun.getInventory();
        while (count > 0 && slot < gun.getContainerSize()) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                var stack = new ItemStack(item, Math.min(count, item.getMaxStackSize()));
                gun.setItem(slot, stack);
                count -= stack.getCount();
            }
            slot++;
        }
        return slot;
    }
}
