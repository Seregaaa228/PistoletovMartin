package dev.frontier.arcade.deploy;

import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.VehicleAccessPolicy;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Creative quick deploy: one item per SBW-based vehicle that puts the vehicle down where the player clicks, ready to
 * board, instead of a crate that needs a crowbar. The vehicle is a fresh native entity, exactly what an opened
 * crate would release; the stack carries only the vehicle ID, never entity data. Its icon is SBW's own crate
 * picture of that vehicle, added to the item atlas by assets/minecraft/atlases/blocks.json.
 */
public final class VehicleDeploy {
    public static final String VEHICLE = "Vehicle";
    /** Vehicles with an icon, in the order the catalog lists them; the icon predicate is (index + 1) / 100. */
    public static final List<String> ORDER = List.of(
        "superbwarfare:t_90a", "superbwarfare:m_1a_2", "superbwarfare:ztz_99a", "superbwarfare:yx_100",
        "superbwarfare:prism_tank", "superbwarfare:bmp_2", "superbwarfare:bradley", "superbwarfare:lav_25",
        "superbwarfare:plz_05", "frontier_arcade_vehicles:msta_s", "frontier_arcade_vehicles:bohdana",
        "frontier_arcade_vehicles:grad", "superbwarfare:tow", "superbwarfare:lav_ad", "superbwarfare:hpj_11",
        "superbwarfare:ah_6", "superbwarfare:mi_28", "superbwarfare:speedboat", "superbwarfare:tiny_speedboat");
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ArcadeVehicles.ID);
    public static final RegistryObject<Item> ITEM = ITEMS.register("vehicle_deploy", DeployItem::new);

    private VehicleDeploy() { }

    public static void initialize(IEventBus bus) {
        ITEMS.register(bus);
    }

    public static ItemStack stack(ResourceLocation vehicle) {
        var stack = new ItemStack(ITEM.get());
        stack.getOrCreateTag().putString(VEHICLE, vehicle.toString());
        return stack;
    }

    static EntityType<?> type(ItemStack stack) {
        var id = stack.hasTag() ? ResourceLocation.tryParse(stack.getTag().getString(VEHICLE)) : null;
        if (id == null || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) return null;
        return ForgeRegistries.ENTITY_TYPES.getValue(id);
    }

    /** The stack's vehicle ID as written in {@link #ORDER}, or empty. */
    static String path(ItemStack stack) {
        var id = stack.hasTag() ? ResourceLocation.tryParse(stack.getTag().getString(VEHICLE)) : null;
        return id == null ? "" : id.toString();
    }

    private static final class DeployItem extends Item {
        DeployItem() { super(new Item.Properties().stacksTo(1)); }

        @Override
        public Component getName(ItemStack stack) {
            var type = type(stack);
            return type == null ? super.getName(stack)
                : Component.translatable("item.frontier_arcade_vehicles.vehicle_deploy.named", type.getDescription());
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Level level = context.getLevel();
            var player = context.getPlayer();
            var type = type(context.getItemInHand());
            if (player == null || type == null) return InteractionResult.FAIL;
            if (level.isClientSide) return InteractionResult.SUCCESS;
            var entity = type.create(level);
            if (entity == null) return InteractionResult.FAIL;
            if (VehicleAccessPolicy.isBlocked(entity)) { entity.discard(); return InteractionResult.FAIL; }
            var at = context.getClickedPos().relative(context.getClickedFace());
            entity.moveTo(at.getX() + 0.5, at.getY() + 0.01, at.getZ() + 0.5, player.getYRot(), 0);
            entity.setYHeadRot(player.getYRot());
            if (!level.getWorldBorder().isWithinBounds(entity.getBoundingBox()) || !level.noCollision(entity, entity.getBoundingBox())) {
                entity.discard();
                player.displayClientMessage(Component.translatable("message.frontier_arcade_vehicles.vehicle_deploy.no_room"), true);
                return InteractionResult.FAIL;
            }
            if (!level.addFreshEntity(entity)) return InteractionResult.FAIL;
            level.gameEvent(player, GameEvent.ENTITY_PLACE, at);
            if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
            return InteractionResult.CONSUME;
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flags) {
            lines.add(Component.translatable("item.frontier_arcade_vehicles.vehicle_deploy.hint").withStyle(ChatFormatting.GRAY));
        }
    }
}
