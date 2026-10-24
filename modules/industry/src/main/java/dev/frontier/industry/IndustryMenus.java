package dev.frontier.industry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.*;
import java.util.UUID;

public final class IndustryMenus {
    public static final DeferredRegister<MenuType<?>> TYPES = DeferredRegister.create(ForgeRegistries.MENU_TYPES, IndustryMod.ID);
    public static final RegistryObject<MenuType<FactoryMenu>> FACTORY = TYPES.register("factory", () -> IForgeMenuType.create(FactoryMenu::new));
    public static void open(ServerPlayer player, UUID site) {
        if (!IndustryEngine.owns(player, site)) return;
        SiteType type = siteType(player.getInventory(), site);
        ItemStackHandler handler = IndustryEngine.inventory(player.serverLevel(), site);
        if (handler == null) return;
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inv, p) ->
            new FactoryMenu(id, inv, site, handler, type), Component.literal("Промышленность")), buf -> {
                buf.writeUUID(site); buf.writeEnum(type);
            });
        IndustryNet.sendView(player, site);
    }
    private static SiteType siteType(Inventory inventory, UUID site) {
        if (inventory.player instanceof ServerPlayer player) {
            var view = IndustryEngine.snapshot(player, site);
            if (view.has("type")) try { return SiteType.valueOf(view.get("type").getAsString()); } catch (IllegalArgumentException ignored) { }
        }
        return SiteType.EQUIPMENT_FACTORY;
    }
    public static final class FactoryMenu extends AbstractContainerMenu {
        public final UUID site;
        public final SiteType type;
        private boolean storageVisible;
        public FactoryMenu(int id, Inventory inv, FriendlyByteBuf data) {
            this(id, inv, data.readUUID(), new ItemStackHandler(24), data.readEnum(SiteType.class));
        }
        FactoryMenu(int id, Inventory inv, UUID site, ItemStackHandler handler) { this(id, inv, site, handler, siteType(inv, site)); }
        FactoryMenu(int id, Inventory inv, UUID site, ItemStackHandler handler, SiteType type) {
            super(FACTORY.get(), id); this.site = site; this.type = type;
            for (int i = 0; i < 24; i++) {
                final boolean output = i >= 12;
                addSlot(new SlotItemHandler(handler, i, 16 + (i % 6) * 18 + (output ? 216 : 0), 83 + ((i % 12) / 6) * 18) {
                    @Override public boolean mayPlace(ItemStack stack) { return !output && super.mayPlace(stack); }
                    @Override public boolean isActive() { return storageVisible; }
                });
            }
            for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9, 97 + col * 18, 143 + row * 18) {
                    @Override public boolean isActive() { return storageVisible; }
                });
            }
            for (int col = 0; col < 9; col++) addSlot(new Slot(inv, col, 97 + col * 18, 201) {
                @Override public boolean isActive() { return storageVisible; }
            });
        }
        /** Presentation only: server slot permissions and indices are identical on every page. */
        public void showStorage(boolean visible) { storageVisible = visible; }
        @Override public boolean stillValid(Player p) {
            if (!(p instanceof ServerPlayer server)) return true;
            return IndustryEngine.canAccess(server, site);
        }
        @Override public ItemStack quickMoveStack(Player player, int index) {
            if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
            Slot slot = slots.get(index);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            ItemStack stack = slot.getItem(), original = stack.copy();
            if (index < 24) { if (!moveItemStackTo(stack, 24, slots.size(), true)) return ItemStack.EMPTY; }
            else if (!moveItemStackTo(stack, 0, 12, false)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
            slot.onTake(player, stack); return original;
        }
    }
}
