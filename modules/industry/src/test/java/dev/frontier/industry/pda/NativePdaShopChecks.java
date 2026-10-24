package dev.frontier.industry.pda;

import com.google.gson.JsonObject;
import dev.frontier.industry.IndustryCatalog;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.SiteType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;

/** Independent acceptance through the public shop and actual DSM PDA; never spawns or ticks aircraft. */
public final class NativePdaShopChecks {
    private static final String AIRCRAFT = "superbwarfare:ah_6", AMMUNITION = "superbwarfare:small_shell_ap";
    private NativePdaShopChecks() { }

    public static Map<String, Boolean> run(ServerPlayer player) {
        if (player == null || !player.isAlive() || !player.server.isSameThread())
            throw new IllegalStateException("Run native PDA shop checks on the live server thread");
        ListTag originalInventory = inventory(player);
        GameType originalMode = player.gameMode.getGameModeForPlayer();
        int originalSelected = player.getInventory().selected;
        List<Entity> temporary = new ArrayList<>();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        try {
            player.setGameMode(GameType.SURVIVAL);
            Class<?> shop = Class.forName("dev.frontier.arcade.PdaShop");
            Class<?> catalog = Class.forName("dev.frontier.arcade.shop.NativeAviationCatalog");
            Method action = shop.getMethod("action", ServerPlayer.class, String.class, String.class, int.class);
            Method create = catalog.getMethod("createStack", ServerLevel.class, String.class);
            check(checks, "shop acceptance holds the actual native DSM PDA", "deepstrike:jagm_camera_tablet".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(IndustryContent.preferredTablet()))));
            supply(player, 1024, true);
            JsonObject snapshot = (JsonObject) shop.getMethod("snapshot", ServerPlayer.class).invoke(null, player);
            JsonObject airOffer = offer(snapshot, AIRCRAFT), ammoOffer = offer(snapshot, AMMUNITION);
            int airPrice = integer(airOffer, "cost"), ammoPrice = integer(ammoOffer, "cost"), ammoCount = integer(ammoOffer, "count");
            check(checks, "public PDA snapshot exposes usable aircraft and separate ammunition offers", snapshot.get("available").getAsBoolean()
                && airPrice > 0 && airPrice <= 2048 && ammoPrice > 0 && ammoCount > 0 && ammoCount <= 64
                && "AIRCRAFT".equals(airOffer.get("kind").getAsString()) && "AMMUNITION".equals(ammoOffer.get("kind").getAsString()));
            supply(player, airPrice + ammoPrice + 32, true);
            int balance = emeralds(player);
            String airResult = invoke(action, player, "shop", AIRCRAFT, 1);
            List<ItemStack> aircraft = aircraft(player, AIRCRAFT);
            check(checks, "public shop accepts held DSM PDA and charges aircraft price exactly once", emeralds(player) == balance - airPrice
                && aircraft.size() == 1 && aircraft.get(0).getCount() == 1);
            ItemStack purchased = aircraft.get(0).copy();
            CompoundTag purchasedBlock = blockData(purchased), purchasedData = purchasedBlock.getCompound("Entity");
            check(checks, "paid aircraft is its real native SBW container with saved initialization", "superbwarfare:container".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(purchased.getItem())))
                && AIRCRAFT.equals(purchasedBlock.getString("EntityType")) && purchasedBlock.contains("Entity", Tag.TAG_COMPOUND)
                && purchasedData.getInt("Energy") > 0 && purchasedData.getInt("Energy") < Integer.MAX_VALUE);
            balance = emeralds(player);
            String ammoResult = invoke(action, player, "shop", AMMUNITION, 1);
            check(checks, "separate ammunition purchase debits exact price and delivers only finite pack", emeralds(player) == balance - ammoPrice
                && count(player, AMMUNITION) == ammoCount && aircraft(player, AIRCRAFT).size() == 1
                && ItemStack.matches(purchased, aircraft(player, AIRCRAFT).get(0)));

            IndustryCatalog.invalidate();
            var airRecipe = IndustryCatalog.resolve(AIRCRAFT, SiteType.VEHICLE_FACTORY);
            var ammoRecipe = IndustryCatalog.resolve(AMMUNITION, SiteType.EQUIPMENT_FACTORY);
            check(checks, "factory catalog uses the same fueled native aircraft builder", airRecipe != null && IndustryCatalog.visible(airRecipe)
                && AIRCRAFT.equals(blockData(airRecipe.output()).getString("EntityType"))
                && blockData(airRecipe.output()).getCompound("Entity").getInt("Energy") == purchasedData.getInt("Energy"));
            check(checks, "factory catalog keeps ammunition a separate finite output", ammoRecipe != null && IndustryCatalog.visible(ammoRecipe)
                && AMMUNITION.equals(String.valueOf(ForgeRegistries.ITEMS.getKey(ammoRecipe.output().getItem()))) && ammoRecipe.output().getCount() == ammoCount);
            ItemStack template = airRecipe.output(), independent = airRecipe.output();
            template.getOrCreateTag().putString("AcceptanceCopyMutation", "first only");
            check(checks, "factory outputs are independent native ItemStack copies", !independent.getOrCreateTag().contains("AcceptanceCopyMutation")
                && !airRecipe.output().getOrCreateTag().contains("AcceptanceCopyMutation"));
            CompoundTag data = blockData(independent).getCompound("Entity");
            check(checks, "factory aircraft template strips packed UUID and transport state", !data.hasUUID("UUID")
                && !data.contains("Pos") && !data.contains("Motion") && !data.contains("Rotation") && !data.contains("Passengers"));
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(AIRCRAFT));
            if (type == null) throw new IllegalStateException("Native AH-6 entity type missing");
            Entity first = type.create(player.serverLevel()), second = type.create(player.serverLevel());
            if (first == null || second == null) throw new IllegalStateException("Native AH-6 construction failed");
            temporary.add(first); temporary.add(second);
            var firstUuid = first.getUUID(); var secondUuid = second.getUUID();
            first.load(data.copy()); second.load(data.copy());
            check(checks, "two copied factory outputs retain fresh distinct native deployment UUIDs", firstUuid.equals(first.getUUID())
                && secondUuid.equals(second.getUUID()) && !first.getUUID().equals(second.getUUID())
                && player.serverLevel().getEntity(first.getUUID()) == null && player.serverLevel().getEntity(second.getUUID()) == null);
            check(checks, "native aircraft copies have full finite energy after loading", fullFiniteEnergy(first) && fullFiniteEnergy(second)
                && energy(first).getEnergyStored() == data.getInt("Energy"));
            check(checks, "paid aircraft and factory outputs carry no included ammunition", purchasedData.getCompound("WeaponState").isEmpty()
                && purchasedData.getInt("DecoyCount") == 0 && data.getCompound("WeaponState").isEmpty()
                && emptyNativeGuns(first) && emptyNativeGuns(second));
            check(checks, "native copied aircraft inventories contain no hidden cargo", first instanceof Container a && a.isEmpty()
                && second instanceof Container b && b.isEmpty() && data.getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND).isEmpty());
            check(checks, "native builder rejects unknown and blocked vehicle IDs", ((ItemStack) create.invoke(null, player.serverLevel(), "frontier_acceptance:unknown")).isEmpty()
                && ((ItemStack) create.invoke(null, player.serverLevel(), "superbwarfare:t_90a")).isEmpty());

            supply(player, airPrice + 32, true);
            ListTag before = inventory(player);
            invoke(action, player, "shop", "frontier_acceptance:unknown", 1);
            invoke(action, player, "shop", "superbwarfare:t_90a", 1);
            check(checks, "public shop unknown or blocked offers preserve exact inventory NBT", before.equals(inventory(player)));
            supply(player, airPrice + 32, false); before = inventory(player);
            invoke(action, player, "shop", AIRCRAFT, 1);
            check(checks, "public shop fails closed without a held unified PDA", before.equals(inventory(player)) && aircraft(player, AIRCRAFT).isEmpty());
            supply(player, Math.max(0, ammoPrice - 1), true); before = inventory(player);
            invoke(action, player, "shop", AMMUNITION, 1);
            check(checks, "public shop insufficient funds preserves exact inventory NBT", before.equals(inventory(player)) && count(player, AMMUNITION) == 0);
            supply(player, ammoPrice + 32, true); before = inventory(player);
            invoke(action, player, "shop", AMMUNITION, 0); invoke(action, player, "shop", AMMUNITION, 2); invoke(action, player, "not_shop", AMMUNITION, 1);
            check(checks, "public shop rejects client quantity and action overrides atomically", before.equals(inventory(player)) && count(player, AMMUNITION) == 0);
            player.getInventory().clearContent(); player.getInventory().selected = 0;
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 64));
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(IndustryContent.preferredTablet()));
            before = inventory(player);
            String fullResult = invoke(action, player, "shop", AMMUNITION, 1);
            check(checks, "held offhand DSM PDA reaches full-inventory gate without debit or overflow", fullResult.contains("место")
                && before.equals(inventory(player)) && count(player, AMMUNITION) == 0);
            com.mojang.logging.LogUtils.getLogger().info("PDA_SHOP_ACCEPTANCE aircraft={} ammo={} nativeType={} energy={}/{} uniqueUUIDs={},{} results={}/{}/{}",
                AIRCRAFT, AMMUNITION, ForgeRegistries.ENTITY_TYPES.getKey(first.getType()), energy(first).getEnergyStored(), energy(first).getMaxEnergyStored(),
                first.getUUID(), second.getUUID(), airResult, ammoResult, fullResult);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native PDA shop/catalog reflection contract failed", failure);
        } finally {
            for (Entity entity : temporary) entity.invalidateCaps();
            player.setGameMode(originalMode); player.getInventory().load(originalInventory);
            player.getInventory().selected = originalSelected; player.getInventory().setChanged(); player.containerMenu.broadcastChanges();
        }
        check(checks, "shop acceptance restores original inventory mode and selected slot", originalInventory.equals(inventory(player))
            && player.gameMode.getGameModeForPlayer() == originalMode && player.getInventory().selected == originalSelected);
        return Map.copyOf(checks);
    }
    private static JsonObject offer(JsonObject snapshot, String id) {
        if (!snapshot.has("items")) throw new IllegalStateException("Native PDA shop offers absent");
        for (var entry : snapshot.getAsJsonArray("items")) if (entry.isJsonObject() && id.equals(entry.getAsJsonObject().get("id").getAsString())) return entry.getAsJsonObject();
        throw new IllegalStateException("Native PDA shop offer absent: " + id);
    }
    private static int integer(JsonObject row, String key) { return row.get(key).getAsInt(); }
    private static String invoke(Method action, ServerPlayer player, String verb, String id, int count) throws ReflectiveOperationException {
        Object result = action.invoke(null, player, verb, id, count); return result instanceof String text ? text : "";
    }
    private static CompoundTag blockData(ItemStack stack) { CompoundTag tag = BlockItem.getBlockEntityData(stack); return tag == null ? new CompoundTag() : tag; }
    private static List<ItemStack> aircraft(ServerPlayer player, String id) { return player.getInventory().items.stream().filter(stack -> !stack.isEmpty() && id.equals(blockData(stack).getString("EntityType"))).toList(); }
    private static ListTag inventory(ServerPlayer player) { return player.getInventory().save(new ListTag()); }
    private static int emeralds(ServerPlayer player) { return player.getInventory().items.stream().filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(); }
    private static int count(ServerPlayer player, String id) { return player.getInventory().items.stream().filter(stack -> id.equals(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())))).mapToInt(ItemStack::getCount).sum(); }
    private static void supply(ServerPlayer player, int emeralds, boolean heldPda) {
        player.getInventory().clearContent(); player.getInventory().selected = 0;
        if (heldPda) player.getInventory().setItem(0, new ItemStack(IndustryContent.preferredTablet()));
        for (int slot = 1; emeralds > 0 && slot < 36; slot++) {
            ItemStack payment = new ItemStack(Items.EMERALD, Math.min(64, emeralds));
            payment.setHoverName(net.minecraft.network.chat.Component.literal("PDA shop exact payment NBT sentinel"));
            player.getInventory().setItem(slot, payment); emeralds -= payment.getCount();
        }
        if (emeralds > 0) throw new IllegalStateException("PDA shop fixture price exceeds inventory capacity");
    }
    private static IEnergyStorage energy(Entity aircraft) throws ReflectiveOperationException {
        Object result = aircraft.getClass().getMethod("getEnergyStorage").invoke(aircraft);
        if (!(result instanceof IEnergyStorage storage)) throw new IllegalStateException("Native aircraft energy storage unavailable");
        return storage;
    }
    private static boolean fullFiniteEnergy(Entity aircraft) throws ReflectiveOperationException {
        IEnergyStorage energy = energy(aircraft); return energy.getMaxEnergyStored() > 0 && energy.getMaxEnergyStored() < Integer.MAX_VALUE && energy.getEnergyStored() == energy.getMaxEnergyStored();
    }
    private static boolean emptyNativeGuns(Entity aircraft) throws ReflectiveOperationException {
        Object raw = aircraft.getClass().getMethod("getGunDataMap").invoke(aircraft);
        if (!(raw instanceof Map<?, ?> guns) || guns.isEmpty()) return false;
        for (Object gun : guns.values()) for (String field : List.of("ammo", "virtualAmmo")) {
            Object nativeValue = gun.getClass().getField(field).get(gun);
            Object count = nativeValue.getClass().getMethod("get").invoke(nativeValue);
            if (!(count instanceof Number number) || number.intValue() != 0) return false;
        }
        return true;
    }
    private static void check(Map<String, Boolean> checks, String name, boolean passed) {
        checks.put(name, passed); if (!passed) throw new AssertionError(name);
    }
}
