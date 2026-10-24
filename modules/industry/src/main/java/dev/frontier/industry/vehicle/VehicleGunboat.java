package dev.frontier.industry.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

/** A normal two-seat boat with a bow cannon. Uses vanilla collision, buoyancy and manual paddles. */
public final class VehicleGunboat extends Boat {
    private long nextShot;

    public VehicleGunboat(EntityType<? extends Boat> type, Level level) { super(type, level); }
    @Override public Item getDropItem() { return VehicleContent.GUNBOAT_DEPLOYER.get(); }
    @Override public Component getName() { return hasCustomName() ? getCustomName() : Component.literal("Канонерская лодка"); }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }

    /** Owner pays real inventory ammunition. Projectile and hit resolution are entirely server-side. */
    public String fire(ServerPlayer owner, Vec3 target) {
        if (!owner.getUUID().equals(VehicleOperations.owner(this))) return "Эта лодка принадлежит другому игроку.";
        if (owner.distanceToSqr(this) > 64 * 64) return "Для стрельбы подойдите к лодке ближе 64 блоков.";
        if (!isInWater()) return "Для стрельбы спустите лодку на воду.";
        if (level().getGameTime() < nextShot) return "Пушка перезаряжается.";
        Vec3 muzzle = position().add(-Math.sin(Math.toRadians(getYRot())) * 1.25, 0.9, Math.cos(Math.toRadians(getYRot())) * 1.25);
        Vec3 direction = target.subtract(muzzle);
        if (direction.lengthSqr() < 4 || direction.lengthSqr() > 96 * 96) return "Цель пушки должна быть в 2–96 блоках.";
        int slot = -1;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
            if (owner.getInventory().getItem(i).is(Items.IRON_NUGGET)) { slot = i; break; }
        }
        if (slot < 0) return "Для выстрела нужен железный самородок в инвентаре.";
        Arrow shell = new Arrow(level(), owner);
        shell.setPos(muzzle.x, muzzle.y, muzzle.z);
        shell.setBaseDamage(5);
        shell.setKnockback(1);
        shell.pickup = AbstractArrow.Pickup.DISALLOWED;
        shell.shoot(direction.x, direction.y + direction.horizontalDistance() * 0.025, direction.z, 3.0F, 0.8F);
        if (!level().addFreshEntity(shell)) return "Не удалось создать снаряд.";
        if (!owner.getAbilities().instabuild) owner.getInventory().getItem(slot).shrink(1);
        nextShot = level().getGameTime() + 25;
        level().playSound(null, blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.2F, 0.65F);
        return null;
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) { super.addAdditionalSaveData(tag); tag.putLong("VehicleNextShot", nextShot); }
    @Override protected void readAdditionalSaveData(CompoundTag tag) { super.readAdditionalSaveData(tag); nextShot = tag.getLong("VehicleNextShot"); }
}
