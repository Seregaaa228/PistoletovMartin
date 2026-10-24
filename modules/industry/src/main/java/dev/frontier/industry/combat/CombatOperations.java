package dev.frontier.industry.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All orders are validated and committed on the owning server thread. */
public final class CombatOperations {
    public record LaunchResult(boolean success, String reason, List<BallisticMissileEntity> missiles) {
        static LaunchResult failure(String reason) { return new LaunchResult(false, reason, List.of()); }
    }
    private record StationSnapshot(LauncherEntity station, long revision, int ammunition, java.util.UUID operator) { }
    private CombatOperations() { }
    public static LauncherEntity nearest(ServerPlayer player, double radius) {
        return player.serverLevel().getEntitiesOfClass(LauncherEntity.class, player.getBoundingBox().inflate(radius),
                        station -> station.ownedBy(player) && station.distanceToSqr(player) <= radius * radius)
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }
    public static LaunchResult launch(ServerPlayer player, Vec3 target, int count) {
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) return LaunchResult.failure("server_thread");
        if (count < 1 || count > CombatRules.MAX_SALVO || !Double.isFinite(target.lengthSqr())) return LaunchResult.failure("count");
        BlockPos targetBlock = BlockPos.containing(target);
        if (target.y < level.getMinBuildHeight() || target.y >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(targetBlock) || !level.hasChunkAt(targetBlock)) return LaunchResult.failure("target_unloaded");
        List<LauncherEntity> ready = level.getEntitiesOfClass(LauncherEntity.class, player.getBoundingBox().inflate(32),
                        station -> station.kind() == LauncherEntity.Kind.BALLISTIC && station.ownedBy(player)
                                && station.distanceToSqr(player) <= 32D * 32D && station.ready() && station.ammunition() > 0)
                .stream().sorted(Comparator.comparingDouble(player::distanceToSqr)).limit(16).toList();
        if (ready.isEmpty()) return LaunchResult.failure("launcher_missing");
        int[] stock = ready.stream().mapToInt(LauncherEntity::ammunition).toArray();
        int[] allocation = CombatRules.allocate(stock, count);
        if (allocation == null) return LaunchResult.failure("ammo_missing");
        List<StationSnapshot> snapshots = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        List<BallisticMissileEntity> prepared = new ArrayList<>();
        for (int index = 0; index < ready.size(); index++) {
            if (allocation[index] == 0) continue;
            LauncherEntity source = ready.get(index);
            if (source.muzzle().distanceToSqr(target) > (double) CombatRules.MAX_RANGE * CombatRules.MAX_RANGE) return LaunchResult.failure("range");
            snapshots.add(new StationSnapshot(source, source.revision(), source.ammunition(), source.operatorUUID()));
            amounts.add(allocation[index]);
            for (int shot = 0; shot < allocation[index]; shot++) {
                BallisticMissileEntity missile = CombatContent.MISSILE.get().create(level);
                if (missile == null) return LaunchResult.failure("spawn_failed");
                // Three separate tubes also produce three distinct entities in a one-launcher salvo.
                Vec3 muzzle = source.muzzle().add((shot - (allocation[index] - 1) * 0.5) * 0.55, 0, 0);
                missile.configure(player.getUUID(), source.getUUID(), muzzle, target);
                for (int tick = 1; tick <= missile.flightDuration(); tick++) {
                    if (!loadedSegment(level, missile.pointAt(tick - 1), missile.pointAt(tick))) return LaunchResult.failure("route_unloaded");
                }
                prepared.add(missile);
            }
        }
        // Snapshot barrier: no ammunition is changed until the entire order is usable.
        for (StationSnapshot snapshot : snapshots) {
            LauncherEntity source = snapshot.station();
            if (level.getEntity(source.getUUID()) != source || source.revision() != snapshot.revision()
                    || source.ammunition() != snapshot.ammunition() || !java.util.Objects.equals(source.operatorUUID(), snapshot.operator())
                    || !source.ownedBy(player) || !source.ready() || source.distanceToSqr(player) > 32D * 32D)
                return LaunchResult.failure("changed");
        }
        int reserved = 0;
        for (int index = 0; index < snapshots.size(); index++) {
            if (!snapshots.get(index).station().reserve(amounts.get(index), snapshots.get(index).revision())) {
                rollback(player, snapshots, amounts, reserved, prepared); return LaunchResult.failure("changed");
            }
            reserved++;
        }
        try {
            for (BallisticMissileEntity missile : prepared) {
                if (!level.addFreshEntity(missile)) { rollback(player, snapshots, amounts, reserved, prepared); return LaunchResult.failure("spawn_failed"); }
            }
        } catch (RuntimeException failedSpawn) {
            rollback(player, snapshots, amounts, reserved, prepared); return LaunchResult.failure("spawn_failed");
        }
        for (int index = 0; index < snapshots.size(); index++) snapshots.get(index).station().fired(amounts.get(index));
        return new LaunchResult(true, "launched", List.copyOf(prepared));
    }
    private static void rollback(ServerPlayer player, List<StationSnapshot> sources, List<Integer> amounts, int reserved, List<BallisticMissileEntity> missiles) {
        missiles.forEach(Entity::discard);
        for (int index = 0; index < reserved; index++) {
            LauncherEntity source = sources.get(index).station();
            if (!source.isRemoved()) source.restoreReservation(amounts.get(index));
            else {
                ItemStack refund = new ItemStack(CombatContent.BALLISTIC_MISSILE.get(), amounts.get(index));
                if (!player.getInventory().add(refund)) player.drop(refund, false);
            }
        }
    }
    /** Checks every touched chunk in a short segment without asking the world to load one. */
    public static boolean loadedSegment(ServerLevel level, Vec3 start, Vec3 end) {
        if (!Double.isFinite(start.lengthSqr()) || !Double.isFinite(end.lengthSqr())) return false;
        int minX = net.minecraft.util.Mth.floor(Math.min(start.x, end.x)) >> 4;
        int maxX = net.minecraft.util.Mth.floor(Math.max(start.x, end.x)) >> 4;
        int minZ = net.minecraft.util.Mth.floor(Math.min(start.z, end.z)) >> 4;
        int maxZ = net.minecraft.util.Mth.floor(Math.max(start.z, end.z)) >> 4;
        if (maxX - minX > 2 || maxZ - minZ > 2) return false;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!level.getChunkSource().hasChunk(x, z)) return false;
        return true;
    }
    public static Component failureMessage(String reason) { return Component.translatable("combat.frontier_industry.failure." + reason); }
}
