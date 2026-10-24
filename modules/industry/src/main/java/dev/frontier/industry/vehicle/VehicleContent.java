package dev.frontier.industry.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.List;

final class VehicleContent {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "frontier_industry");
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "frontier_industry");
    static final RegistryObject<EntityType<VehicleGunboat>> GUNBOAT = ENTITIES.register("vehicle_gunboat", () -> EntityType.Builder.<VehicleGunboat>of(VehicleGunboat::new, MobCategory.MISC)
        .sized(1.6F, 0.65F).clientTrackingRange(10).build("frontier_industry:vehicle_gunboat"));
    static final RegistryObject<Item> GUNBOAT_DEPLOYER = ITEMS.register("vehicle_gunboat_deployer", () -> new Deployer(new Item.Properties().stacksTo(1)));

    static void register(IEventBus bus) { ENTITIES.register(bus); ITEMS.register(bus); }

    private static final class Deployer extends Item {
        private Deployer(Properties properties) { super(properties); }
        @Override public Component getName(ItemStack stack) { return Component.literal("Спуск канонерской лодки"); }
        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.literal("Используйте на воде. На борту: ПКМ этим предметом — огонь."));
            lines.add(Component.literal("Боеприпас: железный самородок. Команды: /industryvehicle"));
        }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack held = player.getItemInHand(hand);
            if (player.getVehicle() instanceof VehicleGunboat gunboat) {
                if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    Vec3 target = player.getEyePosition().add(player.getLookAngle().scale(80));
                    String error = gunboat.fire(serverPlayer, target);
                    if (error != null) player.displayClientMessage(Component.literal(error), true);
                }
                return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
            }
            HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
            if (hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.pass(held);
            BlockPos water = BlockPos.containing(hit.getLocation().add(0, -0.05, 0));
            if (!level.getFluidState(water).is(net.minecraft.tags.FluidTags.WATER)) return InteractionResultHolder.fail(held);
            if (!level.mayInteract(player, water)) return InteractionResultHolder.fail(held);
            if (!level.isClientSide) {
                VehicleGunboat boat = GUNBOAT.get().create(level);
                if (boat == null) return InteractionResultHolder.fail(held);
                boat.setVariant(Boat.Type.OAK);
                boat.setPos(hit.getLocation().x, hit.getLocation().y + 0.15, hit.getLocation().z);
                boat.setYRot(player.getYRot());
                VehicleOperations.setOwner(boat, player.getUUID());
                if (!level.noCollision(boat, boat.getBoundingBox().deflate(0.05)) || !level.addFreshEntity(boat)) return InteractionResultHolder.fail(held);
                if (!player.getAbilities().instabuild) held.shrink(1);
            }
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
    }
}
