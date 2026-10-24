package dev.frontier.industry.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.UUID;

/** A physical, persistent emplacement. Its ammunition is server-owned stock, never a client counter. */
public final class LauncherEntity extends Entity {
    public enum Kind { BALLISTIC, S300, S400, PATRIOT }
    private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> AMMO = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ACTIVE = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> HEALTH = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);
    private UUID owner;
    private UUID operator;
    private long readyAt;
    private long revision;
    private int shotsFired;
    private int interceptions;

    public LauncherEntity(EntityType<? extends LauncherEntity> type, Level level) { super(type, level); setNoGravity(true); }
    @Override protected void defineSynchedData() { entityData.define(KIND, 0); entityData.define(AMMO, 0); entityData.define(ACTIVE, true); entityData.define(HEALTH, 40F); }
    public void configure(Kind kind, UUID owner) { this.owner = owner; entityData.set(KIND, kind.ordinal()); revision++; }
    public Kind kind() { return Kind.values()[Math.max(0, Math.min(3, entityData.get(KIND)))]; }
    @Nullable public UUID ownerUUID() { return owner; }
    @Nullable public UUID operatorUUID() { return operator; }
    public int ammunition() { return entityData.get(AMMO); }
    public boolean active() { return entityData.get(ACTIVE); }
    public long revision() { return revision; }
    public long readyAt() { return readyAt; }
    public int shotsFired() { return shotsFired; }
    public int interceptions() { return interceptions; }
    public Item ammunitionItem() { return kind() == Kind.BALLISTIC ? CombatContent.BALLISTIC_MISSILE.get() : CombatContent.INTERCEPTOR_AMMO.get(); }
    public boolean ownedBy(ServerPlayer player) { return owner != null && owner.equals(player.getUUID()) && player.level() == level(); }
    public void setActive(boolean active) { entityData.set(ACTIVE, active); revision++; }
    public Vec3 muzzle() { return position().add(0, 2.75, 0); }
    public int defenseRange() { return kind() == Kind.S300 ? 128 : kind() == Kind.S400 ? 176 : 192; }
    @Override public boolean isPickable() { return isAlive(); }
    @Override public boolean canBeCollidedWith() { return isAlive(); }
    @Override public boolean isPushable() { return false; }
    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengers().isEmpty() && passenger instanceof LivingEntity && !(passenger instanceof Player); }
    @Override public double getPassengersRidingOffset() { return 0.9; }
    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        if (hasPassenger(passenger)) move.accept(passenger, getX() + 0.65, getY() + 0.85 + passenger.getMyRidingOffset(), getZ());
    }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }

    @Nullable public LivingEntity operator() {
        if (!(level() instanceof ServerLevel server) || operator == null) return null;
        Entity found = server.getEntity(operator);
        if (!(found instanceof LivingEntity living) || !living.isAlive() || living.getVehicle() != this
                || living.distanceToSqr(this) > 16 || !CombatOwnership.eligibleOperator(living, owner, getUUID())) return null;
        // Restores a marker after passenger-first chunk loading.
        living.getPersistentData().putString(CombatOwnership.STATION, getUUID().toString());
        return living;
    }
    public boolean ready() { return isAlive() && level().hasChunkAt(blockPosition()) && owner != null && operator() != null && level().getGameTime() >= readyAt; }
    public boolean assign(ServerPlayer player, LivingEntity crew) {
        if (!ownedBy(player) || distanceToSqr(player) > 64 || !CombatOwnership.eligibleOperator(crew, owner, getUUID())
                || crew.level() != level() || crew.distanceToSqr(this) > 64 || operator() != null) return false;
        if (!crew.startRiding(this, true)) return false;
        operator = crew.getUUID();
        crew.getPersistentData().putString(CombatOwnership.STATION, getUUID().toString());
        CombatOwnership.stopNavigation(crew);
        revision++;
        return true;
    }
    public boolean assignNearest(ServerPlayer player) {
        if (!(level() instanceof ServerLevel server)) return false;
        return server.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(6),
                        living -> CombatOwnership.eligibleOperator(living, owner, getUUID()))
                .stream().sorted(Comparator.comparingDouble(this::distanceToSqr)).anyMatch(living -> assign(player, living));
    }
    public void releaseOperator() {
        if (level() instanceof ServerLevel server && operator != null) {
            Entity found = server.getEntity(operator);
            if (found instanceof LivingEntity living) {
                if (getUUID().toString().equals(living.getPersistentData().getString(CombatOwnership.STATION)))
                    living.getPersistentData().remove(CombatOwnership.STATION);
                if (living.getVehicle() == this) living.stopRiding();
                CombatOwnership.stopNavigation(living);
            }
        }
        operator = null;
        revision++;
    }
    public int load(ItemStack stack) {
        if (level().isClientSide || !stack.is(ammunitionItem())) return 0;
        int amount = Math.min(CombatRules.MAX_AMMO - ammunition(), stack.getCount());
        if (amount > 0) { entityData.set(AMMO, ammunition() + amount); stack.shrink(amount); revision++; }
        return amount;
    }
    boolean reserve(int amount, long expectedRevision) {
        if (amount <= 0 || amount > ammunition() || revision != expectedRevision || !ready()) return false;
        entityData.set(AMMO, ammunition() - amount); revision++; return true;
    }
    void restoreReservation(int amount) { entityData.set(AMMO, Math.min(CombatRules.MAX_AMMO, ammunition() + amount)); revision++; }
    void fired(int count) { readyAt = level().getGameTime() + CombatRules.LAUNCH_COOLDOWN; shotsFired += count; revision++; }
    void recordInterception() { interceptions++; }
    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !ownedBy(serverPlayer) || player.distanceToSqr(this) > 64) {
            player.displayClientMessage(Component.translatable("combat.frontier_industry.not_owner"), true); return InteractionResult.FAIL;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.is(ammunitionItem())) {
            int added = load(held);
            player.displayClientMessage(Component.translatable("combat.frontier_industry.loaded", added, ammunition()), true);
        } else if (held.isEmpty() && player.isShiftKeyDown()) {
            releaseOperator(); player.displayClientMessage(Component.translatable("combat.frontier_industry.operator_released"), true);
        } else if (held.isEmpty() && operator() == null) {
            player.displayClientMessage(Component.translatable(assignNearest(serverPlayer) ? "combat.frontier_industry.operator_assigned" : "combat.frontier_industry.operator_missing"), false);
        } else {
            player.displayClientMessage(status(), false);
        }
        return InteractionResult.CONSUME;
    }
    public Component status() {
        return Component.translatable("combat.frontier_industry.status", Component.translatable("combat.frontier_industry.kind." + kind().name().toLowerCase(java.util.Locale.ROOT)),
                ammunition(), operator() != null ? "✓" : "—", active() ? "AUTO" : "HOLD", Math.max(0, readyAt - level().getGameTime()));
    }
    @Override public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isInvulnerableTo(source) || !isAlive() || amount <= 0) return false;
        markHurt();
        entityData.set(HEALTH, entityData.get(HEALTH) - Math.min(40, amount));
        if (entityData.get(HEALTH) <= 0) {
            releaseOperator();
            spawnAtLocation(new ItemStack(CombatContent.deployer(kind())));
            if (ammunition() > 0) spawnAtLocation(new ItemStack(ammunitionItem(), ammunition()));
            discard();
        }
        return true;
    }
    public boolean packUp(ServerPlayer player) {
        if (!ownedBy(player) || distanceToSqr(player) > 64) return false;
        releaseOperator();
        give(player, new ItemStack(CombatContent.deployer(kind())));
        if (ammunition() > 0) give(player, new ItemStack(ammunitionItem(), ammunition()));
        entityData.set(AMMO, 0); discard(); return true;
    }
    private static void give(ServerPlayer player, ItemStack stack) { if (!player.getInventory().add(stack)) player.drop(stack, false); }
    @Override public void tick() {
        super.tick(); setDeltaMovement(Vec3.ZERO);
        if (level().isClientSide || !(level() instanceof ServerLevel server)) return;
        if (operator != null && server.getEntity(operator) instanceof LivingEntity living && (living.getVehicle() != this || !living.isAlive())) releaseOperator();
        if (kind() == Kind.BALLISTIC || !active() || tickCount % 5 != 0 || ammunition() <= 0 || !ready()) return;
        Entity target = server.getEntities(this, getBoundingBox().inflate(defenseRange()), this::canTarget)
                .stream().filter(candidate -> !alreadyEngaged(server, candidate)).min(Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        if (target == null) return;
        InterceptorEntity interceptor = CombatContent.INTERCEPTOR.get().create(server);
        if (interceptor == null) return;
        interceptor.configure(this, target); interceptor.setPos(muzzle());
        long snapshot = revision;
        if (!reserve(1, snapshot)) return;
        if (!server.addFreshEntity(interceptor)) { restoreReservation(1); return; }
        readyAt = level().getGameTime() + (kind() == Kind.PATRIOT ? 30 : 45); shotsFired++; revision++;
    }
    public boolean canTarget(Entity candidate) {
        if (!(level() instanceof ServerLevel server) || !candidate.isAlive() || !server.hasChunkAt(candidate.blockPosition())
                || candidate.distanceToSqr(this) > (double) defenseRange() * defenseRange()) return false;
        if (candidate instanceof BallisticMissileEntity) return kind() == Kind.PATRIOT && CombatOwnership.hostile(server, owner, candidate);
        // S300/S400 and Patriot all handle ordinary tagged aircraft. No vanilla arrow, ground vehicle or player targeting.
        return kind() != Kind.BALLISTIC && candidate.getType().is(CombatContent.AIR_TARGETS)
                && !candidate.onGround() && CombatOwnership.hostile(server, owner, candidate);
    }
    private boolean alreadyEngaged(ServerLevel server, Entity target) {
        return !server.getEntitiesOfClass(InterceptorEntity.class, getBoundingBox().inflate(defenseRange()),
                shot -> owner != null && owner.equals(shot.ownerUUID()) && target.getUUID().equals(shot.targetUUID())).isEmpty();
    }
    @Override public void remove(RemovalReason reason) { if (reason.shouldDestroy() && !level().isClientSide) releaseOperator(); super.remove(reason); }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        owner = CombatOwnership.parse(tag.getString("Owner")); operator = CombatOwnership.parse(tag.getString("Operator"));
        entityData.set(KIND, Math.max(0, Math.min(3, tag.getInt("Kind"))));
        entityData.set(AMMO, Math.max(0, Math.min(CombatRules.MAX_AMMO, tag.getInt("Ammo"))));
        entityData.set(ACTIVE, !tag.contains("Active") || tag.getBoolean("Active"));
        entityData.set(HEALTH, tag.contains("Health") ? Math.max(1, Math.min(40, tag.getFloat("Health"))) : 40F);
        readyAt = Math.max(0, Math.min(level().getGameTime() + 120, tag.getLong("ReadyAt")));
        shotsFired = Math.max(0, tag.getInt("ShotsFired")); interceptions = Math.max(0, tag.getInt("Interceptions")); revision++;
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (owner != null) tag.putString("Owner", owner.toString()); if (operator != null) tag.putString("Operator", operator.toString());
        tag.putInt("Kind", kind().ordinal()); tag.putInt("Ammo", ammunition()); tag.putBoolean("Active", active());
        tag.putFloat("Health", entityData.get(HEALTH)); tag.putLong("ReadyAt", readyAt); tag.putInt("ShotsFired", shotsFired); tag.putInt("Interceptions", interceptions);
    }
}
