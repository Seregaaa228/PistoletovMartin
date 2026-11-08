package dev.frontier.arcade.airdefense;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Brings Industry's older air-defence launchers into this system. Industry's S-300, S-400 and
 * Patriot are box-model {@code frontier_industry:combat_launcher} entities that need a recruit crew
 * and fire an always-hitting interceptor. When one joins a level (placed from Industry's deployer,
 * which keeps its research gate, or loaded from an old world) it is replaced in place by the
 * matching complex here, with the same owner and facing; its interceptor stock loads the new
 * launcher (the rest goes into the hold as reloads) and its crew is released. Ballistic launchers are Industry's own and stay.
 *
 * <p>Industry's entity type and items stay registered, so old worlds load unchanged and the swap
 * is the migration. Arcade does not compile against Industry: the launcher is read reflectively.</p>
 */
public final class LegacyLaunchers {
    private static final ResourceLocation LAUNCHER = new ResourceLocation("frontier_industry", "combat_launcher");
    private static final Map<String, String> REPLACEMENT = Map.of(
        "S300", "frontier_arcade_vehicles:s300ps",
        "S400", "frontier_arcade_vehicles:s400_triumf",
        "PATRIOT", "frontier_arcade_vehicles:patriot_m901");
    /** Swaps wait for the end of the tick: no entity may be added or removed inside a join event. */
    private static final Deque<Entity> PENDING = new ArrayDeque<>();

    private LegacyLaunchers() { }

    public static void join(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !LAUNCHER.equals(ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType()))) return;
        if (replacement(event.getEntity()) != null) PENDING.add(event.getEntity());
    }

    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        while (!PENDING.isEmpty()) {
            Entity old = PENDING.poll();
            if (old.isAlive() && old.level() instanceof ServerLevel level) replace(level, old);
        }
    }

    private static EntityType<?> replacement(Entity launcher) {
        Object kind = call(launcher, "kind");
        String id = kind == null ? null : REPLACEMENT.get(kind.toString());
        ResourceLocation key = id == null ? null : ResourceLocation.tryParse(id);
        return key != null && ForgeRegistries.ENTITY_TYPES.containsKey(key) ? ForgeRegistries.ENTITY_TYPES.getValue(key) : null;
    }

    private static void replace(ServerLevel level, Entity old) {
        EntityType<?> type = replacement(old);
        if (type == null || !(type.create(level) instanceof SamSiteEntity site)) return;
        Object owner = call(old, "ownerUUID");
        Object stock = call(old, "ammunition");
        site.moveTo(old.getX(), old.getY(), old.getZ(), old.getYRot(), 0);
        if (owner instanceof UUID id) site.setOwner(id);
        call(old, "releaseOperator");
        old.discard();
        // A launcher that has fired keeps exactly its stock (first rack, the rest to the hold); one
        // just placed from Industry's deployer (never fired, empty) arrives loaded like any new site.
        Object fired = call(old, "shotsFired");
        int rounds = stock instanceof Integer n ? n : 0;
        int spare = rounds == 0 && Integer.valueOf(0).equals(fired) ? 0 : site.restock(rounds);
        if (!level.addFreshEntity(site)) return;
        var items = site.getItems();
        var item = site.system().racks.get(0).ammo().resolve();
        if (spare > 0 && !items.isEmpty() && item != null) items.set(0, new ItemStack(item, Math.min(spare, item.getMaxStackSize())));
    }

    private static Object call(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
}
