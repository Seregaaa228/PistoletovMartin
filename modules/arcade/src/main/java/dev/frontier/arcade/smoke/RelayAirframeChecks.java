package dev.frontier.arcade.smoke;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.mojang.authlib.GameProfile;
import dev.frontier.arcade.relay.RelayContent;
import dev.frontier.arcade.relay.RelayEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Isolated-world lifecycle phase. The caller owns world setup, overall reporting, inventory restore and shutdown. */
public final class RelayAirframeChecks implements AutoCloseable {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos pad;
    private final BiConsumer<String, Boolean> checks;
    private final Map<String, Object> details;
    private final Map<UUID, Integer> blasts = new HashMap<>();
    private final List<BlockPos> blastWitness = new ArrayList<>();
    private RelayEntity plane;
    private int phase, age, energyAtLaunch;
    private boolean done, activeSeen, finite = true, moved = true, weakBlast = true, stableGround = true;
    private double minRadius = Double.MAX_VALUE, maxRadius, maximumAltitude, previousX, previousZ;

    public RelayAirframeChecks(ServerPlayer player, BlockPos pad, BiConsumer<String, Boolean> checks, Map<String, Object> details) {
        this.player = player; this.level = player.serverLevel(); this.pad = pad.immutable(); this.checks = checks; this.details = details;
        MinecraftForge.EVENT_BUS.register(this);
        for (BlockPos pos : BlockPos.betweenClosed(pad.offset(-5, 0, -5), pad.offset(5, 0, 5)))
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        player.teleportTo(level, pad.getX() + .5, pad.getY() + 1, pad.getZ() - 7.5, 0, 0);
        place(new ItemStack(RelayContent.ITEM.get()));
        check("relay has independent registry identity and owner", plane.ownedBy(player)
            && "frontier_arcade_vehicles:relay_drone".equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(plane.getType()))));
        check("relay begins with finite 20-minute native battery", plane.getEnergy() == RelayEntity.BATTERY && plane.getMaxEnergy() == RelayEntity.BATTERY);
        check("relay launch is autonomous before radio activation", plane.isFlightRunning() && !plane.isRelayActive()
            && !plane.getEntityData().get(DroneEntity.LINKED));
        var visitor = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RelayVisitor"));
        visitor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(RelayContent.ITEM.get()));
        check("foreign user cannot alter owned relay", plane.interact(visitor, InteractionHand.MAIN_HAND) == InteractionResult.FAIL && plane.ownedBy(player));
        ItemStack ammo = item("superbwarfare:rpg_rocket_standard");
        if (ammo.isEmpty()) ammo = new ItemStack(net.minecraft.world.item.Items.TNT);
        player.setItemInHand(InteractionHand.MAIN_HAND, ammo); int count = ammo.getCount();
        plane.interact(player, InteractionHand.MAIN_HAND); plane.setAmmo(9);
        check("relay rejects payload loading and keeps zero native ammunition", ammo.getCount() == count && plane.getAmmo() == 0);
        var monitor = item("superbwarfare:monitor"); player.setItemInHand(InteractionHand.MAIN_HAND, monitor);
        plane.interact(player, InteractionHand.MAIN_HAND);
        check("relay does not bind a native monitor", !plane.getEntityData().get(DroneEntity.LINKED)
            && !monitor.getOrCreateTag().getBoolean("Linked"));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setShiftKeyDown(false);
        plane.interact(player, InteractionHand.MAIN_HAND);
    }

    public RelayEntity aircraft() { return plane; }
    public boolean done() { return done; }

    /** Returns true when the lifecycle phase has finished. Call once at server END per tick. */
    public boolean tick() {
        if (done) return true;
        age++;
        if(phase==0 && age>=5)stableGround &= plane.onGround();
        if (phase == 0 && age >= 11) {
            check("owner can stop relay before takeoff and it settles without blast", stableGround && plane.onGround() && !plane.isFlightRunning()
                && !plane.isRemoved() && blasts.isEmpty());
            plane.setEnergy(28731); plane.setHealth(13);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setShiftKeyDown(true);
            var result = plane.interact(player, InteractionHand.MAIN_HAND); player.setShiftKeyDown(false);
            int packs = 0; ItemStack recovered = ItemStack.EMPTY;
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                ItemStack stack = player.getInventory().items.get(slot);
                if (stack.is(RelayContent.ITEM.get()) && stack.getOrCreateTag().getInt("RelayEnergy") == 28731) {
                    packs += stack.getCount(); recovered = stack.copy(); player.getInventory().items.set(slot, ItemStack.EMPTY);
                }
            }
            check("ground recovery returns exactly one item with remaining battery and health", result.consumesAction()
                && plane.isRemoved() && packs == 1 && recovered.getOrCreateTag().getFloat("RelayHealth") == 13);
            check("duplicate interaction cannot recover a removed relay twice", plane.interact(player, InteractionHand.MAIN_HAND) == InteractionResult.FAIL);
            if (recovered.isEmpty()) recovered = new ItemStack(RelayContent.ITEM.get());
            place(recovered); energyAtLaunch = plane.getEnergy();
            check("recovered relay redeploys without refilling health or battery", energyAtLaunch == 28731 && plane.getHealth() == 13);
            previousX = plane.getX(); previousZ = plane.getZ(); phase = 1; age = 0;
        } else if (phase == 1) {
            if (plane.isRemoved()) { check("autonomous launch survives to orbit", false); close(); return true; }
            activeSeen |= plane.isRelayActive();
            finite &= Double.isFinite(plane.getX()) && Double.isFinite(plane.getY()) && Double.isFinite(plane.getZ())
                && Float.isFinite(plane.getYRot()) && Float.isFinite(plane.getXRot());
            if (age > 20) moved &= Math.hypot(plane.getX() - previousX, plane.getZ() - previousZ) > .05;
            previousX = plane.getX(); previousZ = plane.getZ(); maximumAltitude = Math.max(maximumAltitude, plane.getY());
            if (age > 550) {
                double radius = Math.hypot(plane.getX() - pad.getX() - .5, plane.getZ() - pad.getZ() - .5);
                minRadius = Math.min(minRadius, radius); maxRadius = Math.max(maxRadius, radius);
            }
            if (age >= 900) {
                check("native relay flight launches and climbs without manual control", activeSeen && maximumAltitude > pad.getY() + 36);
                check("native relay holds a finite moving orbit around deployment point", finite && moved && minRadius > 29 && maxRadius < 35);
                check("native relay consumes finite energy in flight", plane.getEnergy() > 0 && plane.getEnergy() <= energyAtLaunch - 4 * 850);
                details.put("relayAirframeOrbit", Map.of("minimumRadius", minRadius, "maximumRadius", maxRadius,
                    "maximumAltitude", maximumAltitude, "energyBefore", energyAtLaunch, "energyAfter", plane.getEnergy()));
                persistence();
                plane.setEnergy(0); phase = 2; age = 0;
            }
        } else if (phase == 2) {
            if (age == 2) check("exhausted native relay stops engine and RF service", !plane.isRelayActive() && !plane.isFlightRunning() && plane.getPower() == 0);
            if (plane.isRemoved()) {
                int explosions = blasts.getOrDefault(plane.getUUID(), 0); plane.destroy(); plane.destroy();
                check("battery-out landfall has one weak native explosion", explosions == 1 && blasts.getOrDefault(plane.getUUID(), 0) == 1 && weakBlast);
                // A stationary destruction fixture proves that even blocks directly beside the airframe survive.
                RelayEntity fixture = RelayContent.TYPE.get().create(level);
                if (fixture == null) throw new IllegalStateException("Relay blast fixture construction failed");
                fixture.setPos(pad.getX() + .5, pad.getY() + 2, pad.getZ() + .5); fixture.setOwner(player.getUUID());
                for (int dx : new int[]{-1, 1}) { BlockPos pos = pad.offset(dx, 2, 0); blastWitness.add(pos); level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState()); }
                level.addFreshEntity(fixture); fixture.destroy(); fixture.destroy();
                check("native relay fuel blast cannot destroy terrain", blastWitness.stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.STONE))
                    && fixture.isRemoved() && blasts.getOrDefault(fixture.getUUID(), 0) == 1 && weakBlast);
                close(); return true;
            }
            if (age > 240) { check("battery-out aircraft falls to landfall", false); close(); return true; }
        }
        return done;
    }

    private void persistence() {
        CompoundTag saved = plane.saveWithoutId(new CompoundTag());
        RelayEntity restored = RelayContent.TYPE.get().create(level);
        if (restored == null) throw new IllegalStateException("Relay NBT fixture construction failed");
        try {
            restored.load(saved.copy()); CompoundTag roundTrip = restored.saveWithoutId(new CompoundTag());
            check("native relay NBT preserves owner battery and autonomous orbit", restored.ownedBy(player) && restored.getEnergy() == plane.getEnergy()
                && saved.getCompound("RelayFlight").equals(roundTrip.getCompound("RelayFlight")) && restored.isFlightRunning());
            var corrupted = saved.copy(); var flight = corrupted.getCompound("RelayFlight");
            flight.putDouble("CenterX", Double.NaN); flight.putDouble("CenterY", Double.POSITIVE_INFINITY);
            flight.putFloat("Yaw", Float.NaN); flight.putFloat("Pitch", Float.POSITIVE_INFINITY); flight.putFloat("Speed", Float.NaN);
            corrupted.putInt("RelayEnergy", Integer.MAX_VALUE); restored.load(corrupted);
            var bounded = restored.saveWithoutId(new CompoundTag()).getCompound("RelayFlight");
            check("malformed native relay NBT is bounded before movement", restored.getEnergy() == RelayEntity.BATTERY
                && Double.isFinite(bounded.getDouble("CenterX")) && Double.isFinite(bounded.getDouble("CenterY"))
                && Float.isFinite(bounded.getFloat("Yaw")) && Float.isFinite(bounded.getFloat("Pitch")) && Float.isFinite(bounded.getFloat("Speed")));
        } finally { restored.invalidateCaps(); }
    }

    private void place(ItemStack stack) {
        player.setShiftKeyDown(false); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        boolean creative = player.getAbilities().instabuild; player.getAbilities().instabuild = false;
        InteractionResult result;
        try { result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pad).add(0, .5, 0), Direction.UP, pad, false))); }
        finally { player.getAbilities().instabuild = creative; }
        plane = level.getEntitiesOfClass(RelayEntity.class, new AABB(pad).inflate(5), entity -> entity.isAlive() && !entity.isRemoved())
            .stream().findFirst().orElse(null);
        check("real relay placement consumes exactly one survival item", result.consumesAction() && stack.isEmpty() && plane != null);
        if (plane == null) throw new IllegalStateException("Relay deployer did not create its entity");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.inventoryMenu.broadcastChanges();
    }

    @SubscribeEvent public void explosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() != level || !(event.getExplosion() instanceof CustomExplosion blast)) return;
        Entity source = blast.getDirectSourceEntity();
        if (!(source instanceof RelayEntity)) return;
        blasts.merge(source.getUUID(), 1, Integer::sum);
        weakBlast &= blast.getDamage() == 8 && blast.getRadius() == 2;
        details.put("relayNativeFuelBlast", Map.of("damage", blast.getDamage(), "radius", blast.getRadius(), "position", blast.getPosition().toString()));
    }

    private static ItemStack item(String id) {
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)); return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
    private void check(String name, boolean result) { checks.accept(name, result); }
    @Override public void close() {
        if (done) return; done = true; MinecraftForge.EVENT_BUS.unregister(this);
        if (plane != null && !plane.isRemoved()) plane.discard(); player.setShiftKeyDown(false);
    }
}
