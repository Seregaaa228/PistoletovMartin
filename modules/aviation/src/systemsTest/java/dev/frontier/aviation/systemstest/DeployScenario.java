package dev.frontier.aviation.systemstest;

import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Deploys aircraft with the real item the way a player does (right-click on the ground)
 * on flat ground and on built "normal" terrain: bumps, grass and flowers, a tree beside
 * the spot, a click one level below the player. Each must stand on the ground at the
 * clicked point (pushed forward only to clear the player).
 */
final class DeployScenario {
    private static final int CASES = 5;
    private final ServerPlayer player;
    private final JsonObject result = new JsonObject();
    private int ticks;

    DeployScenario(ServerPlayer player) {
        this.player = player;
        result.addProperty("pass", false);
    }

    boolean tick() {
        if (++ticks < 5) return false;
        var level = player.serverLevel();
        boolean pass = true;
        int baseX = player.getBlockX() + 200, z = player.getBlockZ();
        int floor = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, baseX, z);
        for (int i = 0; i < CASES; i++) {
            AircraftProfile profile = i % 2 == 0 ? AircraftProfile.SU25 : AircraftProfile.F16;
            BlockPos origin = new BlockPos(baseX + i * 80, floor, z);
            BlockPos clicked = build(level, origin, i);
            // Player stands 5 blocks south of the click, looking north at it.
            Vec3 stand = Vec3.atBottomCenterOf(origin.offset(0, i == 3 ? 3 : 0, -5));
            player.connection.teleport(stand.x, stand.y, stand.z, 0, 40);
            Item item = profile.item.get();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
            Vec3 click = Vec3.atCenterOf(clicked).add(0, .5, 0);
            var context = new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(click, Direction.UP, clicked, false));
            InteractionResult used = item.useOn(context);
            var found = level.getEntitiesOfClass(Su24mEntity.class, new AABB(origin).inflate(60));
            JsonObject row = new JsonObject();
            row.addProperty("aircraft", profile.id);
            row.addProperty("result", used.name());
            boolean ok = false;
            if (!found.isEmpty()) {
                Entity plane = (Entity) (Object) found.get(0);
                double dx = plane.getX() - click.x, dz = plane.getZ() - click.z;
                double above = plane.getY() - (clicked.getY() + 1);
                row.addProperty("fromClick", Math.round(Math.hypot(dx, dz) * 10) / 10.0);
                row.addProperty("aboveClickedGround", Math.round(above * 100) / 100.0);
                // On the ground (a small bump may lift it a block) and ahead along the look line.
                ok = Math.abs(dx) < 0.5 && dz >= -0.5 && dz < 30 && above > -0.1 && above < 2.1;
                found.forEach(e -> ((Entity) (Object) e).discard());
            }
            row.addProperty("ok", ok);
            result.add("case" + i, row);
            pass &= ok;
        }
        result.addProperty("pass", pass);
        return true;
    }

    /** Builds case {@code i} around {@code origin}; returns the block the player clicks. */
    private static BlockPos build(ServerLevel level, BlockPos origin, int i) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        var random = level.getRandom();
        for (int x = -16; x <= 16; x++)
            for (int z = -22; z <= 22; z++) {
                BlockPos column = origin.offset(x, -1, z);
                level.setBlockAndUpdate(column, grass);
                for (int y = 0; y < 12; y++) level.setBlockAndUpdate(column.above(1 + y), Blocks.AIR.defaultBlockState());
                if (i >= 1) {
                    // Natural ground: grass, flowers and occasional one-block bumps.
                    int roll = random.nextInt(10);
                    if (roll < 3) level.setBlockAndUpdate(column.above(), Blocks.GRASS.defaultBlockState());
                    else if (roll == 3) level.setBlockAndUpdate(column.above(), Blocks.DANDELION.defaultBlockState());
                    else if (roll == 4 && (Math.abs(x) > 2 || Math.abs(z) > 2)) level.setBlockAndUpdate(column.above(), grass);
                }
            }
        if (i == 2 || i == 4) {
            // A tree 7 blocks to the side of the clicked spot.
            BlockPos trunk = origin.offset(7, 0, 0);
            for (int y = 0; y < 5; y++) level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 3; y <= 5; y++)
                if (!(x == 0 && z == 0 && y < 5)) level.setBlockAndUpdate(trunk.offset(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
        }
        if (i == 3) {
            // The player stands on a 3-block ledge and clicks the ground below.
            for (int x = -3; x <= 3; x++) for (int z = -8; z <= -3; z++) for (int y = 0; y < 3; y++)
                level.setBlockAndUpdate(origin.offset(x, y, z), grass);
        }
        level.setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
        return origin.below();
    }

    JsonObject result() { return result; }
}
