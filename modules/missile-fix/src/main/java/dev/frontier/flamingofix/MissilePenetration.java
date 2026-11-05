package dev.frontier.flamingofix;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where a penetrating warhead bursts: it follows its dive (at least 45 degrees down) from the
 * contact through roofs, floors and the air between, and stops on the first solid run at least
 * {@link #GROUND_RUN} blocks thick (a foundation or the ground), on an unbreakable block, or
 * at the depth limit. The blocks it passed through are its entry channel.
 */
public final class MissilePenetration {
    public static final int GROUND_RUN = 4;
    private static final double STEP = 0.5;

    public record Result(Vec3 burst, List<BlockPos> channel) { }

    /** {@code solid}: occupies the path; {@code hard}: cannot be broken (bedrock, barriers). */
    public static Result resolve(Vec3 contact, Vec3 velocity, double depth, Predicate<BlockPos> solid, Predicate<BlockPos> hard) {
        Vec3 dive = velocity.lengthSqr() > 1e-6 ? velocity.normalize() : new Vec3(0, -1, 0);
        if (dive.y > -0.7071) {
            double flat = Math.sqrt(dive.x * dive.x + dive.z * dive.z);
            dive = flat < 1e-6 ? new Vec3(0, -1, 0) : new Vec3(dive.x / flat * 0.7071, -0.7071, dive.z / flat * 0.7071);
        }
        List<BlockPos> channel = new ArrayList<>();
        BlockPos runStart = null, last = null;
        Vec3 runEntry = contact;
        int run = 0;
        for (double travelled = 0; travelled <= depth; travelled += STEP) {
            Vec3 point = contact.add(dive.scale(travelled));
            BlockPos block = BlockPos.containing(point);
            if (block.equals(last)) continue;
            last = block;
            if (hard.test(block)) return new Result(point.subtract(dive.scale(STEP)), trim(channel, runStart));
            if (solid.test(block)) {
                if (run == 0) { runStart = block; runEntry = point.subtract(dive.scale(STEP)); }
                run++;
                channel.add(block);
                if (run >= GROUND_RUN) return new Result(runEntry, trim(channel, runStart));
            } else {
                run = 0; runStart = null;
            }
        }
        return new Result(contact.add(dive.scale(depth)), channel);
    }

    /** The solid run it stopped on is the blast's to break, not the channel's. */
    private static List<BlockPos> trim(List<BlockPos> channel, BlockPos runStart) {
        if (runStart == null) return channel;
        int index = channel.indexOf(runStart);
        return index < 0 ? channel : new ArrayList<>(channel.subList(0, index));
    }

    private MissilePenetration() {}
}
