package dev.frontier.industry;

import com.diego.recruitsvillages.compat.MusketCompat;
import com.diego.recruitsvillages.materialize.RecruitMaterializer;
import com.diego.recruitsvillages.player.PlayerFactionService;
import com.diego.recruitsvillages.sim.Currency;
import com.diego.recruitsvillages.sim.Economy;
import com.mojang.logging.LogUtils;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Country prices/currency with native, cancellable Recruits hiring and its real unit ledger. */
public final class NativePdaRecruitment {
    public static final String PENDING_OWNER = "frontierPdaHireOwner";
    private NativePdaRecruitment() { }

    public static String hire(ServerPlayer player, String nativeId, int count) {
        if (!OperationsNet.hasPda(player) || !player.server.isSameThread() || nativeId == null || nativeId.length() > 128 || count < 1 || count > 7)
            return "Недопустимый запрос найма.";
        var country = PlayerFactionService.factionOfPlayer(player.serverLevel(), player);
        if (country == null || player.getTeam() == null) return "Сначала вступи в свою страну.";
        boolean offered = PlayerFactionService.hireableTypes().stream().map(Economy::resolveRecruit)
            .anyMatch(nativeId::equals);
        int price = offered ? Economy.recruitCost(nativeId) : -1;
        if (price < 0 || RecruitEvents.recruitsPlayerUnitManager == null || FactionEvents.recruitsFactionManager == null)
            return "Этот тип бойца сейчас недоступен.";
        String team = player.getTeam().getName();
        if (FactionEvents.recruitsFactionManager.getFactionByStringID(team) == null)
            return "Данные армии страны ещё не готовы.";
        RecruitsGroup group = group(player);
        int hired = 0;
        String reason = "";
        for (int index = 0; index < count; index++) {
            if (!RecruitEvents.recruitsPlayerUnitManager.canPlayerRecruit(team, player.getUUID())) { reason = "Достигнут лимит армии."; break; }
            if (!Currency.has(player, price)) { reason = "Недостаточно валюты страны."; break; }
            AbstractRecruitEntity recruit = RecruitMaterializer.createRecruit(player.serverLevel(), nativeId);
            if (recruit == null) { reason = "Этот тип бойца недоступен."; break; }
            Escrow payment = null;
            boolean nativeHired = false, factionCredited = false;
            int ledgerBefore = RecruitEvents.recruitsPlayerUnitManager.getRecruitCount(player.getUUID());
            try {
                recruit.initSpawn(); MusketCompat.arm(recruit, nativeId);
                recruit.setXpLevel(1); recruit.setShouldRest(false);
                if (recruit.isOwned() || recruit.getOwnerUUID() != null || !position(player, recruit)) { reason = "Рядом нет безопасного места для бойца."; break; }
                recruit.getPersistentData().putUUID(PENDING_OWNER, player.getUUID());
                // An event-cancelled spawn never touches payment, unit limits, groups or team ledgers.
                if (!player.serverLevel().addFreshEntity(recruit) || recruit.isRemoved()) { reason = "Появление бойца отменено."; break; }
                if (!recruit.isAlive() || recruit.isOwned() || recruit.getOwnerUUID() != null) { reason = "Сервер изменил условия появления бойца."; break; }
                payment = Escrow.reserve(player, Currency.item(), price);
                if (payment == null) { reason = "Недостаточно валюты страны."; break; }
                // This is the same native operation used by CommandEvents.handleRecruiting:
                // Hired event + native cap + owner + one player-unit credit + group + team.
                nativeHired = recruit.hire(player, group, false);
                if (!nativeHired || !recruit.isAlive() || recruit.isRemoved()) { reason = "Найм отменён правилами сервера или лимитом армии."; break; }
                // Native entity.hire does NOT credit faction.npcs; handleRecruiting does this separately.
                FactionEvents.addNPCToData(player.serverLevel(), team, 1); factionCredited = true;
                recruit.getPersistentData().remove(PENDING_OWNER);
                payment.commit(); hired++;
            } catch (RuntimeException failure) {
                reason = "Найм остановлен; оплата за несостоявшийся найм возвращена.";
                LogUtils.getLogger().warn("Native PDA recruitment failed for {}", nativeId, failure);
                break;
            } finally {
                if (payment == null || !payment.committed) {
                    // Only this freshly-created candidate is reverted. Existing country residents are untouched.
                    if (nativeHired || (recruit.isOwned() && player.getUUID().equals(recruit.getOwnerUUID())
                            && RecruitEvents.recruitsPlayerUnitManager.getRecruitCount(player.getUUID()) > ledgerBefore)) {
                        RecruitEvents.recruitsPlayerUnitManager.removeRecruits(player.getUUID(), 1);
                        if (recruit.getGroup() != null && RecruitEvents.recruitsGroupsManager != null)
                            RecruitEvents.recruitsGroupsManager.removeMember(recruit.getGroup(), recruit.getUUID(), player.serverLevel());
                        if (recruit.getTeam() != null) FactionEvents.removeRecruitFromTeam(recruit, recruit.getTeam(), player.serverLevel());
                        recruit.setIsOwned(false); recruit.setOwnerUUID(Optional.empty()); recruit.setGroupUUID(null);
                    }
                    if (factionCredited) FactionEvents.addNPCToData(player.serverLevel(), team, -1);
                    recruit.discard();
                    if (payment != null) payment.refund(player);
                }
            }
        }
        player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        RecruitEvents.recruitsPlayerUnitManager.broadCastUnitInfoToPlayer(player);
        return "Нанято: " + hired + "/" + count + "." + (reason.isBlank() ? " Приказ и снаряжение — в разделе «Бойцы»." : " " + reason);
    }
    private static RecruitsGroup group(ServerPlayer player) {
        var manager = RecruitEvents.recruitsGroupsManager;
        if (manager == null) return null;
        var named = manager.getPlayersGroupByName(player, "Отряд КПК");
        if (named != null) return named;
        var groups = manager.getPlayerGroups(player); return groups.isEmpty() ? null : groups.get(0);
    }
    private static boolean position(ServerPlayer player, AbstractRecruitEntity recruit) {
        ServerLevel level = player.serverLevel(); BlockPos center = player.blockPosition();
        for (int radius = 1; radius <= 4; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos feet = center.offset(dx, dy, dz);
                if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)
                        || !level.getBlockState(feet.below()).isSolid() || !level.getFluidState(feet).isEmpty()) continue;
                recruit.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, player.getYRot(), 0);
                if (level.noCollision(recruit, recruit.getBoundingBox())) return true;
            }
        }
        return false;
    }
    private record Part(int slot, ItemStack stack) { }
    private static final class Escrow {
        private final List<Part> parts = new ArrayList<>();
        private boolean committed;
        static Escrow reserve(ServerPlayer player, Item currency, int price) {
            if (!Currency.has(player, price)) return null;
            var result = new Escrow(); int remaining = price;
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.is(currency)) continue;
                ItemStack taken = stack.split(Math.min(remaining, stack.getCount()));
                remaining -= taken.getCount(); result.parts.add(new Part(slot, taken));
            }
            if (remaining > 0) { result.refund(player); return null; }
            player.getInventory().setChanged(); return result;
        }
        void commit() { committed = true; parts.clear(); }
        void refund(ServerPlayer player) {
            if (committed) return;
            for (Part part : parts) {
                ItemStack remaining = part.stack(), current = player.getInventory().getItem(part.slot());
                if (current.isEmpty()) { player.getInventory().setItem(part.slot(), remaining); continue; }
                if (ItemStack.isSameItemSameTags(current, remaining)) {
                    int moved = Math.min(remaining.getCount(), Math.max(0, current.getMaxStackSize() - current.getCount()));
                    current.grow(moved); remaining.shrink(moved);
                }
                if (!remaining.isEmpty()) { player.getInventory().add(remaining); if (!remaining.isEmpty()) player.drop(remaining, false); }
            }
            parts.clear(); player.getInventory().setChanged();
        }
    }
}
