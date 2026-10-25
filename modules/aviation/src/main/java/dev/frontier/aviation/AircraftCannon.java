package dev.frontier.aviation;

import com.atsuishio.superbwarfare.config.server.ExplosionConfig;
import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Server-only, bounded direct block damage, separate from native splash/entity damage. */
@Mod.EventBusSubscriber(modid = LightAviation.ID)
public final class AircraftCannon {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.DoubleValue SCALE, MAX_HARDNESS;
    private static final ForgeConfigSpec.IntValue BLOCKS_PER_TICK;
    private static final String MARK = "frontierAircraftCannon";
    private static final String OWNER = "frontierAircraftCannonOwner";
    private static final String RESOLVED = "frontierAircraftCannonResolved";
    private static final Map<ServerLevel, DamageCache> LEVELS = new WeakHashMap<>();
    static {
        var builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.comment("Aircraft cannons damage only their directly contacted block.").define("enabled", true);
        SCALE = builder.comment("Material work per native direct-damage unit; entity damage is unchanged.").defineInRange("blockDamageScale", .4, 0, 4);
        MAX_HARDNESS = builder.defineInRange("maximumBlockHardness", 5.0, 0, 50);
        BLOCKS_PER_TICK = builder.comment("Global terrain mutation cap per dimension and tick.").defineInRange("blocksPerTick", 16, 1, 128);
        SPEC = builder.build();
    }
    private AircraftCannon() {}

    private record Damage(BlockState state, double work, long expires) {}
    private static final class DamageCache {
        final LinkedHashMap<BlockPos, Damage> blocks = new LinkedHashMap<>();
        long tick = Long.MIN_VALUE;
        int broken;
    }

    @SubscribeEvent public static void spawned(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof SmallCannonShellEntity shell) || !ENABLED.get()) return;
        Entity owner = shell.getOwner();
        Entity vehicle = owner instanceof Su24mEntity ? owner : owner == null ? null : owner.getVehicle();
        if (!(vehicle instanceof Su24mEntity plane)) return;
        // Include native Su-24 and the three registered variants; no handheld/ground cannon changes.
        if (AircraftProfile.of(plane) == null && !net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(plane.getType()).toString().equals("deepstrike:su24m")) return;
        shell.getPersistentData().putBoolean(MARK, true);
        ServerPlayer pilot = owner instanceof ServerPlayer player ? player
                : plane.getFirstPassenger() instanceof ServerPlayer player ? player : null;
        if (pilot != null) shell.getPersistentData().putUUID(OWNER, pilot.getUUID());
        // Native contact and blast both destroy terrain otherwise, and the native
        // direct-block path also depends on the cosmetic extra-explosion toggle.
        shell.setExplosionDestroy(false);
    }

    /** False suppresses a repeated callback for an already resolved aircraft shell. */
    public static boolean contact(SmallCannonShellEntity shell, BlockHitResult hit) {
        if (!(shell.level() instanceof ServerLevel level) || !shell.getPersistentData().getBoolean(MARK)) return true;
        var tag = shell.getPersistentData();
        if (tag.getBoolean(RESOLVED)) return false;
        tag.putBoolean(RESOLVED, true);
        if (!ENABLED.get() || !ExplosionConfig.EXPLOSION_DESTROY.get() || !tag.hasUUID(OWNER)) return true;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(tag.getUUID(OWNER));
        BlockPos pos = hit.getBlockPos();
        if (player == null || player.level() != level || !player.mayBuild() || !level.hasChunkAt(pos)
                || !level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(player, pos)) return true;
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (state.isAir() || hardness < 0 || hardness > MAX_HARDNESS.get() || state.getFluidState().isSource()
                || !state.canEntityDestroy(level, pos, shell)
                || !net.minecraftforge.event.ForgeEventFactory.onEntityDestroyBlock(player, pos, state)) return true;
        DamageCache cache = LEVELS.computeIfAbsent(level, ignored -> new DamageCache());
        long now = level.getGameTime();
        if (cache.tick != now) { cache.tick = now; cache.broken = 0; }
        if (cache.broken >= BLOCKS_PER_TICK.get()) return true;
        // Respect claims before accumulating progress, so protected blocks never weaken.
        // This is a projectile impact, so hand-tool restrictions (for example a
        // creative pilot holding a sword) must not decide cannon damage. The
        // normal cancellable break event still lets claims deny the mutation.
        if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player))) return true;
        Damage previous = cache.blocks.remove(pos);
        double work = previous != null && previous.state == state && previous.expires > now ? previous.work : 0;
        work += CannonImpactRules.work(shell.getDamage(), SCALE.get());
        if (work >= CannonImpactRules.toughness(hardness, state.getBlock().getExplosionResistance())) {
            if (level.destroyBlock(pos, false, shell)) cache.broken++;
        } else {
            // At most 4096 entries; no world scan, chunk loading, ticking damaged blocks or new packets.
            while (cache.blocks.size() >= 4096) cache.blocks.remove(cache.blocks.keySet().iterator().next());
            cache.blocks.put(pos.immutable(), new Damage(state, work, now + 100));
        }
        return true;
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LEVELS.clear(); }
}
