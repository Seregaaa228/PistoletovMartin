package dev.frontier.arcade.uav;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Optional;
import java.util.UUID;

/** Owner-validated camera for the original installed Shahed entity. No player teleportation. */
public final class NativeUavCamera {
    public static final String MOD_ID = "frontier_arcade_vehicles";
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final RegistryObject<Item> CAMERA = ITEMS.register("uav_camera", CameraItem::new);
    public static final double MAX_RANGE = 96.0;
    private static boolean initialized;

    private NativeUavCamera() { }

    public static void initialize(IEventBus modBus) {
        if (initialized) return;
        initialized = true;
        ITEMS.register(modBus);
        MinecraftForge.EVENT_BUS.register(NativeUavCamera.class);
    }

    public static boolean isNativeShahed(Entity entity) {
        return entity != null && "uncomplicatedfpv:shahed136".equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())));
    }

    /** Reads the native OwnerUUID written by Shahed136Entity, never a guessed persistent owner. */
    public static Optional<UUID> nativeOwner(Entity entity) {
        if (!isNativeShahed(entity)) return Optional.empty();
        CompoundTag saved = entity.serializeNBT();
        return saved.hasUUID("OwnerUUID") ? Optional.of(saved.getUUID("OwnerUUID")) : Optional.empty();
    }

    public static boolean owns(ServerPlayer player, Entity entity) {
        return player != null && entity != null && entity.level() == player.level()
            && nativeOwner(entity).filter(player.getUUID()::equals).isPresent();
    }

    public static boolean isCamera(ItemStack stack) {
        var id=ForgeRegistries.ITEMS.getKey(stack.getItem());
        return !stack.isEmpty() && ((CAMERA.isPresent() && stack.is(CAMERA.get())) || (id!=null && java.util.Set.of("frontier_industry:dispatch_tablet","deepstrike:jagm_camera_tablet").contains(id.toString())));
    }

    public static boolean isUsing(ItemStack stack) {
        return isCamera(stack) && stack.hasTag() && stack.getTag().getBoolean("FrontierUavUsing");
    }

    /** Test hook and server action: the stack must be the real registered camera. */
    public static boolean bind(ServerPlayer player, ItemStack stack, Entity target) {
        if (!isCamera(stack) || !owns(player, target) || !inRange(player, target, allowedRange(player))) return false;
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID("FrontierUavLinkedUav", target.getUUID());
        tag.putUUID("FrontierUavCameraOwner", player.getUUID());
        tag.putString("FrontierUavDimension", player.level().dimension().location().toString());
        tag.putDouble("FrontierUavRange", allowedRange(player));
        tag.putBoolean("FrontierUavUsing", false);
        return true;
    }

    /** Fresh bound stack for smoke/admin integration; no ownership bypass. */
    public static ItemStack createCameraFor(ServerPlayer player, Entity target) {
        if (!CAMERA.isPresent()) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(CAMERA.get());
        return bind(player, result, target) ? result : ItemStack.EMPTY;
    }

    /** Only already loaded server entities qualify. No chunk loads, tickets or world scans. */
    public static Entity linkedTarget(ServerPlayer player, ItemStack stack) {
        if (!isCamera(stack) || !stack.hasTag()) return null;
        CompoundTag tag = stack.getTag();
        if (!tag.hasUUID("FrontierUavLinkedUav") || !tag.hasUUID("FrontierUavCameraOwner")
            || !player.getUUID().equals(tag.getUUID("FrontierUavCameraOwner"))
            || !player.level().dimension().location().toString().equals(tag.getString("FrontierUavDimension"))) return null;
        Entity target = player.serverLevel().getEntity(tag.getUUID("FrontierUavLinkedUav"));
        double range = Math.min(allowedRange(player), tag.getDouble("FrontierUavRange"));
        return owns(player, target) && inRange(player, target, range) ? target : null;
    }

    /** Called by the unified PDA; an owned loaded UAV UUID starts viewing, "off" exits. */
    public static String pdaAction(ServerPlayer player,String argument) {
        ItemStack held=player.getMainHandItem();
        if(!isCamera(held))return "Возьмите КПК в основную руку.";
        if("off".equals(argument)) {held.getOrCreateTag().putBoolean("FrontierUavUsing",false);return "Камера выключена.";}
        if(!player.isAlive()||player.isSpectator())return "Камера сейчас недоступна.";
        try {
            Entity target=player.serverLevel().getEntity(UUID.fromString(argument));
            if(!bind(player,held,target))return "Нет сигнала или БПЛА принадлежит другому игроку.";
            held.getOrCreateTag().putBoolean("FrontierUavUsing",true);player.containerMenu.broadcastChanges();
            return "Вид с Шахеда. Выход: кнопка камеры или смена предмета.";
        }catch(IllegalArgumentException invalid) {return "БПЛА не найден.";}
    }

    private static double allowedRange(ServerPlayer player) {
        return Math.min(MAX_RANGE, Math.max(16.0, (player.server.getPlayerList().getViewDistance() - 1) * 16.0));
    }

    private static boolean inRange(ServerPlayer player, Entity target, double range) {
        return target != null && target.isAlive() && !target.isRemoved() && range >= 1.0
            && player.distanceToSqr(target) <= range * range && player.level().hasChunkAt(target.blockPosition());
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getItemStack();
        if (!isCamera(stack) || event.getHand() != InteractionHand.MAIN_HAND || !isNativeShahed(event.getTarget())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof ServerPlayer player) {
            boolean linked = bind(player, stack, event.getTarget());
            player.displayClientMessage(Component.literal(linked
                ? "Камера привязана. ПКМ в воздух — вид с БПЛА; повторный ПКМ — выход."
                : "Камера доступна только владельцу БПЛА в пределах сигнала."), true);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        ItemStack held = player.getMainHandItem();
        for (ItemStack stack : player.getInventory().items) {
            if (isUsing(stack) && (stack != held || linkedTarget(player, stack) == null || !player.isAlive())) {
                stack.getOrCreateTag().putBoolean("FrontierUavUsing", false);
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (isUsing(stack)) stack.getOrCreateTag().putBoolean("FrontierUavUsing", false);
        }
    }

    private static final class CameraItem extends Item {
        CameraItem() {
            super(new Properties().stacksTo(1));
        }

        @Override
        public Component getName(ItemStack stack) {
            return Component.literal("Камера БПЛА");
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
            if (player instanceof ServerPlayer serverPlayer) {
                boolean wasUsing = isUsing(stack);
                boolean canUse = !wasUsing && linkedTarget(serverPlayer, stack) != null;
                stack.getOrCreateTag().putBoolean("FrontierUavUsing", canUse);
                serverPlayer.displayClientMessage(Component.literal(wasUsing ? "Камера выключена."
                    : canUse ? "Вид с БПЛА. ПКМ — выход; смена предмета — выход."
                    : "Нет сигнала. Привяжите камеру ПКМ к своему Шахеду рядом."), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
    }
}
