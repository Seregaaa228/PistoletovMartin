package dev.frontier.aviation.systemstest;

import com.google.gson.JsonObject;
import dev.frontier.aviation.weapons.GuidedOrdnance;
import dev.frontier.aviation.weapons.KabEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Measures the КАБ-500 release envelope with real bombs in the real world: for each
 * target range a batch of bombs is released level, nose on, from several heights and
 * carrier speeds, and the miss distance of each is recorded. Used to calibrate the
 * tablet's "can it reach" check; it does not judge pass/fail on its own.
 */
final class KabEnvelopeScenario {
    static final int TIMEOUT_TICKS = 5400;
    private static final int[] RANGES = {150, 300, 450, 600};
    private static final int[] HEIGHTS = {40, 80, 140, 200};
    private static final double[] SPEEDS = {1.8, 3.0};
    private static final int SPACING = 48, BATCH_TICKS = 1200, SETTLE_TICKS = 40;
    private final ServerPlayer observer;
    private final GameType previousMode;
    private final JsonObject result = new JsonObject();
    private final double originX, originZ;
    private final Map<Entity, String> bombs = new LinkedHashMap<>();
    private final Map<Entity, Vec3> targets = new LinkedHashMap<>();
    private int batch = -1, batchTicks;

    KabEnvelopeScenario(ServerPlayer observer) {
        this.observer = observer;
        previousMode = observer.gameMode.getGameModeForPlayer();
        observer.setGameMode(GameType.SPECTATOR);
        originX = observer.getBlockX() + .5;
        originZ = observer.getBlockZ() + .5 + 40;
        ImpactObservations.clear();
        result.addProperty("pass", true);
        nextBatch();
    }

    boolean tick() {
        batchTicks++;
        if (batchTicks == SETTLE_TICKS) release();
        if (batchTicks > SETTLE_TICKS) {
            boolean done = true;
            for (var entry : bombs.entrySet()) {
                String key = entry.getValue();
                if (result.has(key)) continue;
                Vec3 hit = null;
                for (var trace : ImpactObservations.owned(observer.getUUID(), observer.getUUID()))
                    if (trace.entity == entry.getKey() && trace.hit != null) hit = trace.hit;
                if (hit == null && entry.getKey().isRemoved()) hit = entry.getKey().position();
                if (hit != null) {
                    Vec3 aim = targets.get(entry.getKey());
                    var level = observer.serverLevel();
                    int below = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(hit.x), (int) Math.floor(hit.z));
                    result.addProperty(key, Math.round(Math.hypot(hit.x - aim.x, hit.z - aim.z) * 10) / 10.0);
                    // Height of the detonation above the terrain under it: > 4 is an air burst.
                    result.addProperty(key + "_agl", Math.round((hit.y - below) * 10) / 10.0);
                    result.addProperty(key + "_ticks", batchTicks - SETTLE_TICKS);
                } else done = false;
            }
            if (done || batchTicks > BATCH_TICKS) {
                for (var entry : bombs.entrySet()) if (!result.has(entry.getValue())) result.addProperty(entry.getValue(), -1);
                bombs.keySet().forEach(Entity::discard);
                forceChunks(false);
                bombs.clear();
                targets.clear();
                if (batch + 1 >= RANGES.length) {
                    observer.setGameMode(previousMode);
                    return true;
                }
                nextBatch();
            }
        }
        return false;
    }

    private void nextBatch() {
        batch++;
        batchTicks = 0;
        // Keep every bomb of the batch inside simulation distance of the observer.
        var level = observer.serverLevel();
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) originX, (int) originZ);
        observer.connection.teleport(originX, ground + 40, originZ + RANGES[batch] / 2.0, 0, 30);
        forceChunks(true);
    }

    /** The whole batch area ticks regardless of the observer's simulation distance. */
    private void forceChunks(boolean force) {
        var level = observer.serverLevel();
        double half = 4 * SPACING + 16;
        for (int cx = (int) Math.floor((originX - half) / 16); cx <= (int) Math.floor((originX + half) / 16); cx++)
            for (int cz = (int) Math.floor((originZ - 32) / 16); cz <= (int) Math.floor((originZ + RANGES[batch] + 32) / 16); cz++)
                level.setChunkForced(cx, cz, force);
    }

    private void release() {
        var level = observer.serverLevel();
        int range = RANGES[batch], slot = 0;
        for (int height : HEIGHTS) {
            for (double speed : SPEEDS) {
                double x = originX + (slot++ - 3.5) * SPACING;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(originZ));
                KabEntity bomb = GuidedOrdnance.KAB_500.get().create(level);
                if (bomb == null) throw new IllegalStateException("no KAB");
                Vec3 target = new Vec3(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(x), (int) Math.floor(originZ + range)), originZ + range);
                // Vanilla calls through Entity: the reobfuscated helper cannot resolve them on KabEntity.
                Entity body = bomb;
                body.setPos(x, ground + height, originZ);
                body.setDeltaMovement(0, 0, speed);
                // Vanilla call through Projectile: the reobfuscated helper cannot resolve it on KabEntity.
                ((net.minecraft.world.entity.projectile.Projectile) (Object) bomb).setOwner(observer);
                bomb.setGuideType(1);
                bomb.setTargetUUID("none");
                bomb.setTargetPos(target);
                if (!level.addFreshEntity(bomb)) throw new IllegalStateException("KAB rejected");
                String key = "r" + range + "_h" + height + "_v" + Math.round(speed * 72);
                bombs.put(bomb, key);
                targets.put(bomb, target);
            }
        }
    }

    JsonObject result() { return result; }
}
