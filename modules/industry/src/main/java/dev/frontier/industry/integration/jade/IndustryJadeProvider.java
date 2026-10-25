package dev.frontier.industry.integration.jade;

import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.energy.ControllerBlockEntity;
import dev.frontier.industry.engine.IndustryRules;
import dev.frontier.industry.engine.IndustryState;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum IndustryJadeProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    INSTANCE;

    public static final String DATA_KEY = "FrontierIndustry";
    public static final int UPDATE_TICKS = 10;
    private static final ResourceLocation UID = new ResourceLocation(IndustryMod.ID, "industry_state");
    private final Map<ServerPlayer, Cached> cache = new WeakHashMap<>();

    @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        data.remove(DATA_KEY);
        if (!(accessor.getPlayer() instanceof ServerPlayer player)
            || !(accessor.getBlockEntity() instanceof ControllerBlockEntity)
            || !player.isAlive() || player.isSpectator()) return;
        BlockPos pos = accessor.getPosition();
        if (!player.serverLevel().hasChunkAt(pos)
            || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > (double) IndustryRules.INTERACT_RANGE * IndustryRules.INTERACT_RANGE) return;
        int now = player.server.getTickCount();
        ResourceLocation dimension = player.level().dimension().location();
        Cached previous = cache.get(player);
        if (previous != null && now >= previous.tick && now - previous.tick < UPDATE_TICKS) {
            if (previous.pos.equals(pos) && previous.dimension.equals(dimension)
                && previous.site != null && IndustryEngine.owns(player, previous.site)) data.put(DATA_KEY, previous.payload.copy());
            return;
        }
        var state = IndustryEngine.stateAt(player, pos);
        CompoundTag payload = state.map(IndustryJadeProvider::encode).orElseGet(CompoundTag::new);
        cache.put(player, new Cached(now, dimension, pos.immutable(), state.map(IndustryState::id).orElse(null), payload));
        if (state.isPresent()) data.put(DATA_KEY, payload.copy());
    }

    public static CompoundTag encode(IndustryState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", bounded(state.type().title(), 80));
        tag.putString("status", bounded(state.status(), 200));
        tag.putInt("health", state.health());
        tag.putBoolean("powered", state.powered());
        tag.putInt("energy", state.energy());
        tag.putInt("capacity", state.energyCapacity());
        tag.putString("network", bounded(state.networkStatus(), 120));
        tag.putInt("workers", state.workers());
        tag.putInt("repairWorkers", state.repairWorkers());
        tag.putBoolean("repair", state.repairRequested());
        tag.putInt("repairProgress", state.repairProgress());
        tag.putInt("repairTotal", state.repairTotal());
        if (!state.currentOrder().isEmpty()) {
            tag.putString("order", bounded(state.currentOrder(), 120));
            tag.putInt("quantity", state.quantity());
            tag.putInt("progress", state.progress());
            tag.putInt("total", state.total());
        }
        return tag;
    }

    private static String bounded(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        return count <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
    }

    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!accessor.getServerData().contains(DATA_KEY, 10)) {
            tooltip.add(Component.literal("Промышленные данные доступны владельцу"));
            return;
        }
        CompoundTag tag = accessor.getServerData().getCompound(DATA_KEY);
        tooltip.add(Component.literal(tag.getString("type") + " · целостность " + tag.getInt("health") + "%"));
        tooltip.add(Component.literal(tag.getString("status")));
        tooltip.add(Component.literal((tag.getBoolean("powered") ? "Питание: есть" : "Питание: нет")
            + " · " + tag.getInt("energy") + "/" + tag.getInt("capacity") + " FE"));
        tooltip.add(Component.literal(tag.getString("network")));
        tooltip.add(Component.literal("Рабочие рядом: " + tag.getInt("workers") + "; ремонтники: " + tag.getInt("repairWorkers")));
        if (tag.contains("order", 8)) {
            int total = tag.getInt("total");
            int percent = total <= 0 ? 0 : (int) Math.min(100, 100L * tag.getInt("progress") / total);
            tooltip.add(Component.literal("Заказ: " + tag.getString("order") + " × " + tag.getInt("quantity") + " · " + percent + "%"));
        }
        if (tag.getBoolean("repair")) {
            int remaining = Math.max(0, tag.getInt("repairTotal") - tag.getInt("repairProgress"));
            tooltip.add(Component.literal("Ремонт: ещё " + ((remaining + 19) / 20) + " с"));
        }
    }

    @Override public ResourceLocation getUid() { return UID; }

    private record Cached(int tick, ResourceLocation dimension, BlockPos pos, UUID site, CompoundTag payload) {}
}
