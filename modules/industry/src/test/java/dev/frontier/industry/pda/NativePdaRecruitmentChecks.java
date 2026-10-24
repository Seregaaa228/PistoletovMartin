package dev.frontier.industry.pda;

import com.diego.recruitsvillages.player.PlayerFactionService;
import com.diego.recruitsvillages.data.FactionData;
import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.diego.recruitsvillages.sim.Currency;
import com.diego.recruitsvillages.sim.Economy;
import com.mojang.logging.LogUtils;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.RecruitEvent;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.NativePdaRecruitment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.GameType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Copied-world native smoke: temporary country, original player records restored, flat ground nearby required. */
public final class NativePdaRecruitmentChecks {
    private NativePdaRecruitmentChecks() { }
    public static Map<String, Boolean> run(ServerPlayer player) {
        if (RecruitEvents.recruitsPlayerUnitManager == null || FactionEvents.recruitsFactionManager == null)
            throw new IllegalStateException("Native army managers must be ready");
        var scoreboard = player.server.getScoreboard();
        var oldTeam = scoreboard.getPlayersTeam(player.getScoreboardName());
        var oldMode = player.gameMode.getGameModeForPlayer();
        ListTag inventory = player.getInventory().save(new ListTag());
        int ledger = RecruitEvents.recruitsPlayerUnitManager.getRecruitCount(player.getUUID());
        String name = "pda_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        FactionData country = FactionData.create("PDA native smoke country");
        country.setStringID(name); country.setCenterPos(player.blockPosition()); country.setOwnerUuid(player.getUUID());
        var countries = LivingVillagesSavedData.get(player.server);
        try {
            countries.putFaction(country);
            var team = scoreboard.addPlayerTeam(name);
            // Match RecruitsFactionBridge.register: native countries disable same-team friendly fire.
            // AbstractRecruitEntity.isAlliedTo is defined through canHarmTeam, so this is functional state.
            team.setAllowFriendlyFire(false);
            FactionEvents.recruitsFactionManager.addTeam(name, country.getName(), player.getUUID(), player.getName().getString(), new CompoundTag(), (byte) 0, ChatFormatting.WHITE);
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
            player.setGameMode(GameType.SURVIVAL);
            // The temporary country has only fixture troops; restore the real player ledger in finally.
            RecruitEvents.recruitsPlayerUnitManager.setRecruitCount(player, 0);
            return checks(player);
        } finally {
            if (oldTeam != null) scoreboard.addPlayerToTeam(player.getScoreboardName(), oldTeam);
            else scoreboard.removePlayerFromTeam(player.getScoreboardName());
            var fixtureTeam = scoreboard.getPlayerTeam(name);
            if (fixtureTeam != null) scoreboard.removePlayerTeam(fixtureTeam);
            FactionEvents.recruitsFactionManager.removeTeam(name); countries.removeFaction(country.getId());
            RecruitEvents.recruitsPlayerUnitManager.setRecruitCount(player, ledger);
            player.setGameMode(oldMode); player.getInventory().load(inventory);
            player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        }
    }
    private static Map<String, Boolean> checks(ServerPlayer player) {
        if (PlayerFactionService.factionOfPlayer(player.serverLevel(), player) == null || player.getTeam() == null)
            throw new IllegalStateException("PDA recruitment fixture requires membership in a native country");
        var ledger = RecruitEvents.recruitsPlayerUnitManager;
        var faction = FactionEvents.recruitsFactionManager.getFactionByStringID(player.getTeam().getName());
        if (ledger == null || faction == null) throw new IllegalStateException("Native army managers must be ready");
        String type = PlayerFactionService.hireableTypes().stream().map(Economy::resolveRecruit)
            .filter(java.util.Objects::nonNull).filter(id -> Economy.recruitCost(id) > 0 && Economy.recruitCost(id) <= 256).findFirst().orElseThrow();
        int price = Economy.recruitCost(type), baseline = ledger.getRecruitCount(player.getUUID());
        int oldNpcs = faction.npcs, oldMax = faction.maxNPCs, oldPerPlayer = faction.maxNPCsPerPlayer;
        ListTag originalInventory = player.getInventory().save(new ListTag());
        Set<UUID> oldEntities = nearby(player).stream().map(AbstractRecruitEntity::getUUID).collect(Collectors.toSet());
        Map<String, Boolean> results = new LinkedHashMap<>();
        var spawnCancel = new CancelSpawn(player.getUUID()); var hireCancel = new CancelHire(player.getUUID());
        try {
            faction.maxNPCs = Math.max(oldNpcs + 32, baseline + 32); faction.maxNPCsPerPlayer = baseline + 32;
            supply(player, price * 3);
            ListTag before = player.getInventory().save(new ListTag());
            MinecraftForge.EVENT_BUS.register(spawnCancel);
            try { NativePdaRecruitment.hire(player, type, 1); } finally { MinecraftForge.EVENT_BUS.unregister(spawnCancel); }
            check(results, "cancelled native spawn was observed", spawnCancel.seen);
            check(results, "cancelled spawn preserves exact currency NBT and inventory", before.equals(player.getInventory().save(new ListTag())));
            check(results, "cancelled spawn preserves player and faction ledgers", ledger.getRecruitCount(player.getUUID()) == baseline && faction.npcs == oldNpcs);
            check(results, "cancelled spawn leaves no purchased or unowned candidate", newEntities(player, oldEntities).isEmpty());

            before = player.getInventory().save(new ListTag());
            MinecraftForge.EVENT_BUS.register(hireCancel);
            try { NativePdaRecruitment.hire(player, type, 1); } finally { MinecraftForge.EVENT_BUS.unregister(hireCancel); }
            check(results, "real native cancellable Hired event was observed", hireCancel.seen);
            check(results, "cancelled native hire refunds exact escrow stacks with NBT", before.equals(player.getInventory().save(new ListTag())));
            check(results, "cancelled native hire preserves both ledgers and removes candidate", ledger.getRecruitCount(player.getUUID()) == baseline && faction.npcs == oldNpcs && newEntities(player, oldEntities).isEmpty());

            supply(player, 0); before = player.getInventory().save(new ListTag());
            NativePdaRecruitment.hire(player, type, 1);
            check(results, "insufficient funds creates no unit or ledger credit", before.equals(player.getInventory().save(new ListTag()))
                && ledger.getRecruitCount(player.getUUID()) == baseline && faction.npcs == oldNpcs && newEntities(player, oldEntities).isEmpty());

            supply(player, price * 3);
            // Exactly one faction slot is free. A two-unit click must recheck the real cap after its first unit.
            faction.maxNPCs = oldNpcs + 1; faction.maxNPCsPerPlayer = baseline + 32;
            int currencyBefore = Currency.count(player);
            NativePdaRecruitment.hire(player, type, 2);
            var recruits = newEntities(player, oldEntities);
            check(results, "two-unit order respects last native country slot", recruits.size() == 1);
            check(results, "successful native hire charges exactly once", Currency.count(player) == currencyBefore - price);
            check(results, "successful native hire credits player ledger exactly once", ledger.getRecruitCount(player.getUUID()) == baseline + 1);
            check(results, "successful native hire credits faction ledger exactly once", faction.npcs == oldNpcs + 1);
            LogUtils.getLogger().info("PDA_HIRE_DIAGNOSTIC player={} team={} friendlyFire={} selectedType={} currency={}/{} playerLedger={}/{} factionLedger={}/{} bodies={}",
                player.getUUID(), player.getTeam().getName(), player.getTeam().isAllowFriendlyFire(), type,
                Currency.count(player), currencyBefore, ledger.getRecruitCount(player.getUUID()), baseline,
                faction.npcs, oldNpcs, recruits.stream().map(recruit -> "uuid=" + recruit.getUUID()
                    + ",type=" + net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(recruit.getType())
                    + ",owner=" + recruit.getOwnerUUID() + ",owned=" + recruit.isOwned()
                    + ",team=" + (recruit.getTeam() == null ? "null" : recruit.getTeam().getName())
                    + ",scoreboard=" + (player.server.getScoreboard().getPlayersTeam(recruit.getStringUUID()) == null ? "null"
                        : player.server.getScoreboard().getPlayersTeam(recruit.getStringUUID()).getName())
                    + ",allied=" + recruit.isAlliedTo(player) + ",pending=" + recruit.getPersistentData().hasUUID(NativePdaRecruitment.PENDING_OWNER)).toList());
            check(results, "successful unit has real owner and native country team", recruits.size() == 1
                && recruits.get(0).isOwned() && player.getUUID().equals(recruits.get(0).getOwnerUUID())
                && recruits.get(0).getTeam() != null && recruits.get(0).getTeam().equals(player.getTeam())
                && player.server.getScoreboard().getPlayersTeam(recruits.get(0).getStringUUID()) == player.getTeam()
                && recruits.get(0).isAlliedTo(player) && recruits.get(0).isAlive() && !oldEntities.contains(recruits.get(0).getUUID())
                && !recruits.get(0).getPersistentData().hasUUID(NativePdaRecruitment.PENDING_OWNER));
            before = player.getInventory().save(new ListTag());
            NativePdaRecruitment.hire(player, type, 1);
            check(results, "full native country cap blocks later request without debit", before.equals(player.getInventory().save(new ListTag()))
                && ledger.getRecruitCount(player.getUUID()) == baseline + 1 && faction.npcs == oldNpcs + 1 && newEntities(player, oldEntities).size() == 1);
            return Map.copyOf(results);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(spawnCancel); MinecraftForge.EVENT_BUS.unregister(hireCancel);
            for (var recruit : newEntities(player, oldEntities)) {
                if (recruit.getGroup() != null && RecruitEvents.recruitsGroupsManager != null)
                    RecruitEvents.recruitsGroupsManager.removeMember(recruit.getGroup(), recruit.getUUID(), player.serverLevel());
                if (recruit.getTeam() != null) FactionEvents.removeRecruitFromTeam(recruit, recruit.getTeam(), player.serverLevel());
                recruit.discard();
            }
            ledger.setRecruitCount(player, baseline);
            faction.npcs = oldNpcs; faction.maxNPCs = oldMax; faction.maxNPCsPerPlayer = oldPerPlayer;
            player.getInventory().load(originalInventory); player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        }
    }
    private static void supply(ServerPlayer player, int amount) {
        player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(IndustryContent.preferredTablet()));
        for (int slot = 1; amount > 0 && slot < 36; slot++) {
            ItemStack stack = new ItemStack(Currency.item(), Math.min(amount, Currency.item().getDefaultInstance().getMaxStackSize()));
            stack.setHoverName(Component.literal("PDA exact payment NBT sentinel"));
            amount -= stack.getCount(); player.getInventory().setItem(slot, stack);
        }
    }
    private static List<AbstractRecruitEntity> nearby(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(AbstractRecruitEntity.class, player.getBoundingBox().inflate(12), entity -> entity.isAlive());
    }
    private static List<AbstractRecruitEntity> newEntities(ServerPlayer player, Set<UUID> old) { return nearby(player).stream().filter(entity -> !old.contains(entity.getUUID())).toList(); }
    private static void check(Map<String, Boolean> results, String label, boolean passed) { results.put(label, passed); if (!passed) throw new AssertionError(label); }
    public static final class CancelSpawn {
        private final UUID player; boolean seen;
        CancelSpawn(UUID player) { this.player = player; }
        @SubscribeEvent public void cancel(EntityJoinLevelEvent event) {
            var tag = event.getEntity().getPersistentData();
            if (!event.getLevel().isClientSide && tag.hasUUID(NativePdaRecruitment.PENDING_OWNER) && player.equals(tag.getUUID(NativePdaRecruitment.PENDING_OWNER))) { seen = true; event.setCanceled(true); }
        }
    }
    public static final class CancelHire {
        private final UUID player; boolean seen;
        CancelHire(UUID player) { this.player = player; }
        @SubscribeEvent public void cancel(RecruitEvent.Hired event) {
            var tag = event.getRecruit().getPersistentData();
            if (tag.hasUUID(NativePdaRecruitment.PENDING_OWNER) && player.equals(tag.getUUID(NativePdaRecruitment.PENDING_OWNER))) { seen = true; event.setCanceled(true); }
        }
    }
}
