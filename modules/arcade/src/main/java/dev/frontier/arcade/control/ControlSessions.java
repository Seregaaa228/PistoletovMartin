package dev.frontier.arcade.control;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.item.misc.MonitorItem;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.geranium.GeraniumEntity;
import dev.frontier.arcade.mixin.AddonControlAccess;
import dev.frontier.arcade.relay.RelayEntity;
import dev.frontier.arcade.relay.RelayNetwork;
import dev.frontier.arcade.station.StationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Owner-authorized native control sessions. A saved pair is separate from an active radio view. */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID)
public final class ControlSessions {
    public record DroneEntry(UUID id, String name, String kind, double distance, int battery, int signal, boolean online) { }
    public static final String OWNER = "frontierIndustryVehicleOwner";
    public static final String PAIR = "FrontierControlPair", PAIR_OWNER = "FrontierControlOwner", PAIR_DIMENSION = "FrontierControlDimension";
    private static final String SESSION = "FrontierStationSession", TOKEN = "FrontierStationToken", TERMINATED = "FrontierControlTerminated";
    private static final Map<AddonDroneEntity, List<ItemStack>> LOST_PAIRS = new IdentityHashMap<>();
    private static final Map<UUID, UUID> VIEWED = new HashMap<>();
    private ControlSessions() { }

    public static boolean isMonitor(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof PocketControllerItem || stack.getItem() instanceof StationLinkItem
            || stack.getItem() == ModItems.MONITOR.get());
    }
    public static boolean isStationLink(ItemStack stack) { return !stack.isEmpty() && stack.getItem() instanceof StationLinkItem; }
    /** Retained API name: the desktop now lists the two large aircraft, regardless of their payload role. */
    public static boolean isRecon(AddonDroneEntity drone) { return isStationAircraft(drone); }
    public static boolean isStationAircraft(AddonDroneEntity drone) { return drone instanceof RelayEntity || drone instanceof GeraniumEntity; }
    public static boolean isMavic(AddonDroneEntity drone) {
        String id = typeId(drone);
        return id.equals("uncomplicatedfpv:mavic_drone_no_drop") || id.equals("uncomplicatedfpv:mavic_drone_with_drop");
    }
    public static boolean isPocketAircraft(AddonDroneEntity drone) {
        return isMavic(drone) || typeId(drone).equals("uncomplicatedfpv:fpv_drone");
    }
    public static boolean isStrike(AddonDroneEntity drone) {
        return drone instanceof GeraniumEntity || typeId(drone).equals("uncomplicatedfpv:fpv_drone");
    }
    private static String typeId(Entity entity) { return String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())); }
    public static UUID ownerId(AddonDroneEntity drone) {
        if (drone instanceof RelayEntity relay) return relay.ownerId();
        if (drone instanceof GeraniumEntity geranium) return geranium.ownerId();
        if (drone.getPersistentData().hasUUID(OWNER)) return drone.getPersistentData().getUUID(OWNER);
        return parseId(drone.getEntityData().get(DroneEntity.CONTROLLER));
    }
    public static void rememberNativeOwner(AddonDroneEntity drone) {
        if (drone.level().isClientSide || (!isStationAircraft(drone) && !isPocketAircraft(drone))) return;
        UUID owner = ownerId(drone);
        if (owner != null) drone.getPersistentData().putUUID(OWNER, owner);
    }
    private static boolean owns(Player player, AddonDroneEntity drone) {
        return player != null && player.getUUID().equals(ownerId(drone));
    }
    private static boolean alive(AddonDroneEntity drone) { return !drone.isRemoved() && drone.isAlive() && drone.getHealth() > 0; }
    private static boolean unsupportedNativeBattery(AddonDroneEntity drone) {
        // Pinned UF 1.0.1 uses old vehicle-data paths. SBW's default-data sentinel cannot receive energy.
        // Preserve only these original native aircraft; never bypass either addon's finite battery.
        return isPocketAircraft(drone) && drone.getMaxEnergy() == Integer.MAX_VALUE;
    }
    public static boolean isPowered(AddonDroneEntity drone) {
        if (!alive(drone)) return false;
        if (drone instanceof RelayEntity) return drone.getEnergy() >= RelayEntity.ENERGY_PER_TICK;
        if (drone instanceof GeraniumEntity) return drone.getEnergy() > 0;
        return unsupportedNativeBattery(drone) || drone.getEnergy() > 0;
    }
    private static boolean powered(AddonDroneEntity drone) { return isPowered(drone); }
    public static int batteryPercent(AddonDroneEntity drone) {
        if (unsupportedNativeBattery(drone)) return -1;
        int battery = (int)Math.round(100d * Math.max(0, drone.getEnergy()) / Math.max(1, drone.getMaxEnergy()));
        return Math.max(0, Math.min(100, battery));
    }
    private static UUID parseId(String value) {
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }
    private static UUID pairedId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        return tag.hasUUID(PAIR) ? tag.getUUID(PAIR) : parseId(tag.getString("LinkedDrone"));
    }
    private static AddonDroneEntity find(ServerPlayer player, UUID id) {
        Entity entity = id == null ? null : player.serverLevel().getEntity(id);
        return entity instanceof AddonDroneEntity drone ? drone : null;
    }
    private static AddonDroneEntity paired(ServerPlayer player, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(PAIR_DIMENSION) && !player.level().dimension().location().toString().equals(tag.getString(PAIR_DIMENSION))) return null;
        return find(player, pairedId(stack));
    }
    private static boolean allowedTransport(ServerPlayer player, ItemStack stack, AddonDroneEntity drone) {
        if (!owns(player, drone)) return false;
        return monitorFor(player, stack, drone);
    }
    public static boolean monitorFor(Player player, ItemStack stack, AddonDroneEntity drone) {
        if (!isMonitor(stack)) return false;
        if (stack.getItem() instanceof PocketControllerItem) return isPocketAircraft(drone);
        if (isStationLink(stack)) return isStationAircraft(drone) && player instanceof ServerPlayer server && stationActive(server)
            && session(player).hasUUID("Target") && drone.getUUID().equals(session(player).getUUID("Target"));
        return !isStationAircraft(drone); // Legacy small-drone monitors remain compatible; large aircraft require the station.
    }
    private static void rememberPair(ServerPlayer player, ItemStack stack, AddonDroneEntity drone) {
        if (!owns(player, drone) || !isMonitor(stack)) return;
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(PAIR, drone.getUUID()); tag.putUUID(PAIR_OWNER, player.getUUID());
        tag.putString(PAIR_DIMENSION, player.level().dimension().location().toString());
        tag.putBoolean("Linked", true); tag.putString("LinkedDrone", drone.getStringUUID());
        MonitorItem.getDronePos(stack, drone.position()); rememberNativeOwner(drone);
    }
    private static void clearPair(ItemStack stack) {
        CompoundTag tag = stack.getTag(); if (tag == null) return;
        tag.remove(PAIR); tag.remove(PAIR_OWNER); tag.remove(PAIR_DIMENSION);
        tag.putBoolean("Using", false); tag.putBoolean("Linked", false); tag.putString("LinkedDrone", "none");
    }
    private static void message(Player player, String text) { player.displayClientMessage(Component.literal(text), true); }

    /** null leaves native interaction unchanged; custom terminals never depend on getItem identity spoofing. */
    public static InteractionResult interact(AddonDroneEntity drone, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return null;
        ItemStack held = player.getMainHandItem();
        if (!isMonitor(held)) return null;
        if (isStationLink(held)) return InteractionResult.FAIL;
        boolean pocket = held.getItem() instanceof PocketControllerItem;
        if (!pocket && isStationAircraft(drone)) {
            if (!player.level().isClientSide) message(player, "Крупные БПЛА управляются с наземной станции.");
            return InteractionResult.FAIL;
        }
        if (pocket && !isPocketAircraft(drone)) {
            if (!player.level().isClientSide) message(player, "Карманный пульт — для Mavic и FPV. Ретранслятор и Герань-2 управляются с наземной станции.");
            return InteractionResult.FAIL;
        }
        UUID owner = ownerId(drone);
        if (owner != null && !owner.equals(player.getUUID())) {
            if (!player.level().isClientSide) message(player, "Этот аппарат принадлежит другому игроку.");
            return InteractionResult.FAIL;
        }
        if (player.level().isClientSide) return pocket ? InteractionResult.SUCCESS : null;
        if (!(player instanceof ServerPlayer server) || !alive(drone) || player.distanceToSqr(drone) > 16 * 16) return InteractionResult.FAIL;
        // A physical close-range bind may claim an unowned native airframe, never somebody else's.
        if (owner == null) drone.getPersistentData().putUUID(OWNER, player.getUUID());
        if (!pocket) {
            if (player.isShiftKeyDown()) { held.getOrCreateTag().remove(PAIR); held.getOrCreateTag().remove(PAIR_OWNER); held.getOrCreateTag().remove(PAIR_DIMENSION); }
            return null;
        }
        if (player.isShiftKeyDown()) {
            pauseView(server, held, drone); clearPair(held);
            if (player.getStringUUID().equals(drone.getEntityData().get(DroneEntity.CONTROLLER))) {
                drone.getEntityData().set(DroneEntity.LINKED, false); drone.getEntityData().set(DroneEntity.CONTROLLER, "none");
            }
            message(player, "Пульт отвязан."); return InteractionResult.CONSUME;
        }
        UUID previous = pairedId(held);
        if (previous != null && !previous.equals(drone.getUUID())) {
            message(player, "Сначала отвяжи предыдущий аппарат: Shift+ПКМ по нему."); return InteractionResult.FAIL;
        }
        drone.getPersistentData().remove(TERMINATED);
        rememberPair(server, held, drone); held.getOrCreateTag().putBoolean("Using", false);
        drone.getEntityData().set(DroneEntity.CONTROLLER, player.getStringUUID()); drone.getEntityData().set(DroneEntity.LINKED, true);
        message(player, "Пульт привязан. ПКМ в воздух — включить или выключить изображение.");
        return InteractionResult.CONSUME;
    }
    public static void afterInteract(AddonDroneEntity drone, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer server) || !owns(player, drone)) return;
        ItemStack stack = player.getMainHandItem(); CompoundTag tag = stack.getTag();
        if (stack.getItem() == ModItems.MONITOR.get() && tag != null && tag.getBoolean("Linked")
            && drone.getStringUUID().equals(tag.getString("LinkedDrone")) && drone.getEntityData().get(DroneEntity.LINKED)
            && player.getStringUUID().equals(drone.getEntityData().get(DroneEntity.CONTROLLER))) rememberPair(server, stack, drone);
    }

    public static InteractionResultHolder<ItemStack> usePocket(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer server)) return InteractionResultHolder.fail(stack);
        return use(server, stack, false);
    }
    /** Preserve native small-drone monitors; release old large-aircraft pairs for reuse. */
    public static InteractionResultHolder<ItemStack> useLegacy(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer server) || stack.getItem() != ModItems.MONITOR.get()) return null;
        AddonDroneEntity drone = paired(server, stack);
        if (drone == null) return null;
        if (!owns(server, drone)) { message(player, "Этот аппарат принадлежит другому игроку."); return InteractionResultHolder.fail(stack); }
        if (isStationAircraft(drone)) {
            if (stack.getOrCreateTag().getBoolean("Using")) pauseView(server, stack, drone);
            clearPair(stack);
            message(player, "Крупные БПЛА управляются с наземной станции.");
            return InteractionResultHolder.fail(stack);
        }
        return use(server, stack, true);
    }
    private static InteractionResultHolder<ItemStack> use(ServerPlayer player, ItemStack stack, boolean legacy) {
        AddonDroneEntity drone = paired(player, stack);
        if (drone == null || (!legacy && !isPocketAircraft(drone)) || !owns(player, drone)) {
            message(player, "Сначала привяжи пульт к своему Mavic или FPV."); return InteractionResultHolder.fail(stack);
        }
        if (stack.getOrCreateTag().getBoolean("Using")) {
            pauseView(player, stack, drone); return InteractionResultHolder.consume(stack);
        }
        if (!startView(player, stack, drone)) return InteractionResultHolder.fail(stack);
        return InteractionResultHolder.consume(stack);
    }
    private static boolean startView(ServerPlayer player, ItemStack stack, AddonDroneEntity drone) {
        if (!player.isAlive() || player.isSpectator() || player.level() != drone.level() || !allowedTransport(player, stack, drone)) return false;
        Vec3 origin = player.getEyePosition();
        if (!powered(drone) || !RelayNetwork.reachable(drone, origin)) {
            rememberPair(player, stack, drone); pauseView(player, stack, drone);
            message(player, "Нет радиосвязи: приблизься к аппарату или убери помехи. Привязка сохранена."); return false;
        }
        rememberPair(player, stack, drone);
        drone.getEntityData().set(DroneEntity.CONTROLLER, player.getStringUUID()); drone.getEntityData().set(DroneEntity.LINKED, true);
        // Re-entry starts a new session at the real body, never at the old remote camera.
        drone.endRemoteControl(player);
        healSignal(drone, origin);
        stack.getOrCreateTag().putBoolean("Using", true);
        if (!drone.beginRemoteControl(player)) { stack.getOrCreateTag().putBoolean("Using", false); return false; }
        VIEWED.put(player.getUUID(), drone.getUUID()); player.getInventory().setChanged();
        return true;
    }
    private static void healSignal(AddonDroneEntity drone, Vec3 origin) {
        RelayNetwork.LinkInfo link = RelayNetwork.linkInfo(drone, origin);
        if (!link.connected() || !powered(drone)) return;
        drone.getEntityData().set(AddonControlAccess.frontier$quality(), (float)Math.max(0, Math.min(1, link.quality())));
        drone.getEntityData().set(AddonControlAccess.frontier$lost(), false);
    }
    private static void pauseView(ServerPlayer player, ItemStack stack, AddonDroneEntity drone) {
        if (drone != null && owns(player, drone)) { drone.processInput((short)0); drone.setFire(false); drone.endRemoteControl(player); }
        UUID pair = pairedId(stack);
        stack.getOrCreateTag().putBoolean("Using", false);
        MonitorItem.disLink(stack, player); // Native camera reset, followed by restoring only the durable pair.
        if (drone != null && owns(player, drone)) rememberPair(player, stack, drone);
        else if (pair != null) { stack.getOrCreateTag().putBoolean("Linked", true); stack.getOrCreateTag().putString("LinkedDrone", pair.toString()); }
        VIEWED.remove(player.getUUID()); player.getInventory().setChanged();
    }
    public static boolean authorizedInput(ServerPlayer player, AddonDroneEntity drone) {
        if (player == null || !player.isAlive() || player.isSpectator() || player.level() != drone.level() || !powered(drone)) return false;
        ItemStack held = player.getMainHandItem(); CompoundTag tag = held.getTag();
        if (!allowedTransport(player, held, drone) || tag == null || !tag.getBoolean("Using") || !tag.getBoolean("Linked")
            || !drone.getStringUUID().equals(tag.getString("LinkedDrone")) || !drone.getEntityData().get(DroneEntity.LINKED)
            || !player.getStringUUID().equals(drone.getEntityData().get(DroneEntity.CONTROLLER)) || drone.isSignalLost()) return false;
        Vec3 origin = drone.getOperatorPosition();
        return RelayNetwork.reachable(drone, origin == null ? player.getEyePosition() : origin);
    }

    public static void monitorTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(entity instanceof ServerPlayer player) || level.isClientSide) return;
        if (stack.getItem() == ModItems.MONITOR.get() && (stack.getTag() == null || !stack.getTag().hasUUID(PAIR))
            && paired(player, stack) == null) return; // A standard SBW DroneEntity keeps its native monitor lifecycle.
        if (isStationLink(stack) && !validToken(player, stack)) { stack.setCount(0); return; }
        if (!selected) {
            if (stack.getOrCreateTag().getBoolean("Using")) pauseView(player, stack, paired(player, stack));
            return;
        }
        AddonDroneEntity drone = paired(player, stack);
        if (drone != null && owns(player, drone) && isStationAircraft(drone) && stack.getItem() == ModItems.MONITOR.get()) {
            if (stack.getOrCreateTag().getBoolean("Using")) {
                pauseView(player, stack, drone);
                message(player, "Крупные БПЛА управляются с наземной станции.");
            }
            clearPair(stack);
            return;
        }
        if (drone == null || !owns(player, drone) || !allowedTransport(player, stack, drone)) {
            if (stack.getOrCreateTag().getBoolean("Using")) pauseView(player, stack, null);
            return;
        }
        rememberPair(player, stack, drone);
        if (!stack.getOrCreateTag().getBoolean("Using")) return;
        Vec3 origin = drone.getOperatorPosition(); if (origin == null) origin = player.getEyePosition();
        if (!powered(drone) || !RelayNetwork.reachable(drone, origin)) {
            if (powered(drone) && !drone.isSignalLost()) drone.handleSignalLoss(player);
            else pauseView(player, stack, drone);
            return;
        }
        if (!drone.getEntityData().get(DroneEntity.LINKED) || !player.getStringUUID().equals(drone.getEntityData().get(DroneEntity.CONTROLLER))
            || drone.isSignalLost() || drone.getOperatorPosition() == null) {
            drone.getEntityData().set(DroneEntity.CONTROLLER, player.getStringUUID()); drone.getEntityData().set(DroneEntity.LINKED, true);
            healSignal(drone, origin); drone.beginRemoteControl(player);
        }
        VIEWED.put(player.getUUID(), drone.getUUID());
    }
    public static boolean protectNativeTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!selected || stack.getTag() == null || !stack.getTag().hasUUID(PAIR)) return false;
        monitorTick(stack, level, entity, slot, true);
        return true;
    }
    public static void beforeSignalLoss(AddonDroneEntity drone) {
        rememberNativeOwner(drone);
        UUID owner = ownerId(drone); Player player = owner == null ? null : drone.level().getPlayerByUUID(owner);
        List<ItemStack> remembered = new ArrayList<>();
        if (player instanceof ServerPlayer server && alive(drone) && !drone.getPersistentData().getBoolean(TERMINATED)) {
            for (int slot = 0; slot < server.getInventory().getContainerSize(); slot++) {
                ItemStack stack = server.getInventory().getItem(slot);
                if (isMonitor(stack) && drone.getUUID().equals(pairedId(stack))) { rememberPair(server, stack, drone); remembered.add(stack); }
            }
        }
        LOST_PAIRS.put(drone, remembered);
    }
    public static void afterSignalLoss(AddonDroneEntity drone) {
        List<ItemStack> remembered = LOST_PAIRS.remove(drone);
        if (remembered == null || remembered.isEmpty() || !alive(drone) || drone.getPersistentData().getBoolean(TERMINATED)) return;
        UUID owner = ownerId(drone); Player player = owner == null ? null : drone.level().getPlayerByUUID(owner);
        if (!(player instanceof ServerPlayer server)) return;
        for (ItemStack stack : remembered) {
            rememberPair(server, stack, drone); stack.getOrCreateTag().putBoolean("Using", false);
        }
        drone.getEntityData().set(DroneEntity.LINKED, true); drone.getEntityData().set(DroneEntity.CONTROLLER, server.getStringUUID());
        VIEWED.remove(server.getUUID()); server.getInventory().setChanged();
    }
    public static void forgetPair(AddonDroneEntity drone) {
        if (drone.level().isClientSide) return;
        drone.getPersistentData().putBoolean(TERMINATED, true);
        UUID owner = ownerId(drone); Player player = owner == null ? null : drone.level().getPlayerByUUID(owner);
        if (player instanceof ServerPlayer server) {
            for (int slot = 0; slot < server.getInventory().getContainerSize(); slot++) {
                ItemStack stack = server.getInventory().getItem(slot);
                if (isMonitor(stack) && drone.getUUID().equals(pairedId(stack))) {
                    MonitorItem.disLink(stack, player); clearPair(stack);
                }
            }
            VIEWED.remove(server.getUUID());
        }
    }

    private static boolean stationValid(ServerPlayer player, BlockPos pos) {
        if (!player.isAlive() || player.isSpectator() || !player.serverLevel().hasChunkAt(pos)
            || player.distanceToSqr(Vec3.atCenterOf(pos)) > 36) return false;
        if (!"frontier_arcade_vehicles:ground_station".equals(String.valueOf(ForgeRegistries.BLOCKS.getKey(player.level().getBlockState(pos).getBlock())))) return false;
        return player.level().getBlockEntity(pos) instanceof StationBlockEntity station && player.getUUID().equals(station.ownerId());
    }
    public static List<DroneEntry> ownedRecon(ServerPlayer player, BlockPos station) {
        if (!stationValid(player, station)) return List.of();
        List<DroneEntry> result = new ArrayList<>();
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (!(entity instanceof AddonDroneEntity drone) || !isStationAircraft(drone) || !alive(drone)) continue;
            UUID owner = ownerId(drone);
            if (!player.getUUID().equals(owner)) continue;
            rememberNativeOwner(drone);
            RelayNetwork.LinkInfo link = RelayNetwork.linkInfo(drone, player.getEyePosition());
            boolean online = powered(drone) && link.connected();
            result.add(new DroneEntry(drone.getUUID(), drone.getDisplayName().getString(), drone instanceof RelayEntity ? "relay" : "geranium",
                drone.position().distanceTo(Vec3.atCenterOf(station)), batteryPercent(drone),
                (int)Math.round(Math.max(0, Math.min(1, link.quality())) * 100), online));
        }
        result.sort(Comparator.comparingDouble(DroneEntry::distance).thenComparing(DroneEntry::id));
        return List.copyOf(result);
    }
    public static boolean connectStation(ServerPlayer player, BlockPos station, UUID target) {
        if (!stationValid(player, station) || target == null) return false;
        AddonDroneEntity drone = find(player, target);
        if (drone == null || !isStationAircraft(drone) || !powered(drone)) return false;
        UUID owner = ownerId(drone);
        if (!player.getUUID().equals(owner) || !RelayNetwork.reachable(drone, player.getEyePosition())) {
            message(player, "Аппарат недоступен: проверь владельца, расстояние и радиопомехи."); return false;
        }
        if (hasSession(player)) { disconnectStation(player); if (hasSession(player)) return false; }
        player.closeContainer();
        int slot = player.getInventory().selected; ItemStack original = player.getMainHandItem().copy();
        if (isStationLink(original)) return false;
        CompoundTag ledger = new CompoundTag(); UUID token = UUID.randomUUID();
        ledger.putUUID("Token", token); ledger.putUUID("Target", target); ledger.putInt("Slot", slot);
        ledger.putString("Dimension", player.level().dimension().location().toString()); ledger.putLong("Station", station.asLong());
        ledger.put("Original", original.save(new CompoundTag()));
        persisted(player).put(SESSION, ledger); // Commit the recoverable original before replacing its only live copy.
        ItemStack link = new ItemStack(ControlContent.STATION_LINK.get()); link.getOrCreateTag().putUUID(TOKEN, token);
        rememberPair(player, link, drone); link.getOrCreateTag().putBoolean("Using", false);
        player.getInventory().setItem(slot, link);
        if (!startView(player, link, drone)) { disconnectStation(player); return false; }
        player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        return true;
    }
    private static CompoundTag persisted(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG, 10)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }
    private static boolean hasSession(Player player) { return persisted(player).contains(SESSION, 10); }
    private static CompoundTag session(Player player) { return persisted(player).getCompound(SESSION); }
    private static boolean validToken(ServerPlayer player, ItemStack stack) {
        CompoundTag tag = stack.getTag(), ledger = session(player);
        return isStationLink(stack) && tag != null && tag.hasUUID(TOKEN) && ledger.hasUUID("Token") && tag.getUUID(TOKEN).equals(ledger.getUUID("Token"));
    }
    public static BlockPos stationPosition(ServerPlayer player) { return hasSession(player) ? BlockPos.of(session(player).getLong("Station")) : null; }
    public static boolean stationActive(ServerPlayer player) {
        if (!hasSession(player)) return false;
        CompoundTag ledger = session(player);
        return !ledger.getBoolean("Recovering") && player.level().dimension().location().toString().equals(ledger.getString("Dimension"))
            && player.getInventory().selected == ledger.getInt("Slot") && validToken(player, player.getMainHandItem())
            && stationValid(player, BlockPos.of(ledger.getLong("Station")));
    }
    public static void disconnectStation(ServerPlayer player) {
        if (!hasSession(player)) { removeHidden(player); return; }
        CompoundTag ledger = session(player); ledger.putBoolean("Recovering", true);
        AddonDroneEntity drone = find(player, ledger.hasUUID("Target") ? ledger.getUUID("Target") : null);
        ItemStack held = player.getMainHandItem();
        if (isStationLink(held)) pauseView(player, held, drone);
        else if (drone != null && owns(player, drone)) drone.endRemoteControl(player);
        VIEWED.remove(player.getUUID()); removeHidden(player);
        restoreOriginal(player);
    }
    private static void removeHidden(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (isStationLink(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, ItemStack.EMPTY);
        for (var slot : player.containerMenu.slots) if (isStationLink(slot.getItem())) slot.set(ItemStack.EMPTY);
        if (player.containerMenu != player.inventoryMenu) for (var slot : player.inventoryMenu.slots) if (isStationLink(slot.getItem())) slot.set(ItemStack.EMPTY);
        if (isStationLink(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
        if (player.containerMenu != player.inventoryMenu && isStationLink(player.inventoryMenu.getCarried())) player.inventoryMenu.setCarried(ItemStack.EMPTY);
    }
    private static void restoreOriginal(ServerPlayer player) {
        if (!hasSession(player) || !player.isAlive()) return;
        CompoundTag ledger = session(player); ItemStack original = ItemStack.of(ledger.getCompound("Original"));
        if (original.isEmpty()) { persisted(player).remove(SESSION); player.getInventory().setChanged(); return; }
        int preferred = ledger.getInt("Slot"), free = -1;
        if (preferred >= 0 && preferred < 36 && player.getInventory().getItem(preferred).isEmpty()) free = preferred;
        if (free < 0) for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).isEmpty()) { free = slot; break; }
        if (free < 0) {
            // No deletion, overwrite or spontaneous drop: the original remains recoverable in the durable ledger.
            if (player.tickCount % 40 == 0) message(player, "Освободи ячейку: станция хранит твой предмет и вернёт его автоматически.");
            return;
        }
        persisted(player).remove(SESSION); // Clear once before insertion; subsequent events cannot restore a second copy.
        player.getInventory().setItem(free, original); player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
    }
    public static void disconnectAt(ServerLevel level, BlockPos station) {
        for (ServerPlayer player : level.players()) if (station.equals(stationPosition(player))) disconnectStation(player);
    }

    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !(event.player instanceof ServerPlayer player)) return;
        if (hasSession(player)) {
            if (!player.isAlive()) { session(player).putBoolean("Recovering", true); removeHidden(player); }
            else if (!stationActive(player) || !player.getMainHandItem().getOrCreateTag().getBoolean("Using")) disconnectStation(player);
        } else removeHidden(player);
        ItemStack held = player.getMainHandItem();
        if (isMonitor(held)) monitorTick(held, player.level(), player, player.getInventory().selected, true);
        UUID previous = VIEWED.get(player.getUUID());
        if (previous != null && (!isMonitor(held) || held.getTag() == null || !held.getTag().getBoolean("Using"))) {
            AddonDroneEntity drone = find(player, previous); if (drone != null && owns(player, drone)) drone.endRemoteControl(player);
            VIEWED.remove(player.getUUID());
        }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { removeHidden(player); if (hasSession(player)) { session(player).putBoolean("Recovering", true); restoreOriginal(player); } }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (hasSession(player)) disconnectStation(player);
            else if (isMonitor(player.getMainHandItem())) pauseView(player, player.getMainHandItem(), paired(player, player.getMainHandItem()));
            VIEWED.remove(player.getUUID());
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasSession(player)) {
            session(player).putBoolean("Recovering", true);
            AddonDroneEntity drone = find(player, session(player).hasUUID("Target") ? session(player).getUUID("Target") : null);
            if (drone != null && owns(player, drone)) drone.endRemoteControl(player);
            removeHidden(player); VIEWED.remove(player.getUUID());
        }
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) {
        Player old = event.getOriginal(), replacement = event.getEntity();
        if (hasSession(old)) { persisted(replacement).put(SESSION, session(old).copy()); persisted(old).remove(SESSION); }
        if (replacement instanceof ServerPlayer player) removeHidden(player);
    }
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasSession(player)) { session(player).putBoolean("Recovering", true); removeHidden(player); restoreOriginal(player); }
    }
    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasSession(player)) disconnectStation(player);
    }
    @SubscribeEvent public static void openedContainer(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && hasSession(player)) disconnectStation(player);
    }
    @SubscribeEvent public static void droppedTransport(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof ItemEntity item && isStationLink(item.getItem())) { event.setCanceled(true); item.discard(); }
    }
    @SubscribeEvent public static void legacyOwner(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide && event.getTarget() instanceof AddonDroneEntity drone && isMonitor(event.getItemStack())) rememberNativeOwner(drone);
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) { LOST_PAIRS.clear(); VIEWED.clear(); }
}
