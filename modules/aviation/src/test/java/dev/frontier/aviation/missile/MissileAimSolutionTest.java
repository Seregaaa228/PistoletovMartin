package dev.frontier.aviation.missile;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public final class MissileAimSolutionTest {
    public static void main(String[] args) {
        var solution = MissileAimSolution.resolve(new Vec3(10, 20, 30), new Vec3(10, 20, 35))
                .orElseThrow();
        check(solution.direction().equals(new Vec3(0, 0, 1)), "muzzle direction");
        UUID target = UUID.randomUUID();
        check(solution.withTargetEntity(target).targetEntity().orElseThrow().equals(target),
                "entity identity");
        check(MissileAimSolution.resolve(new Vec3(0, 0, 0), new Vec3(0, 0, 0)).isEmpty(),
                "zero range");
        check(MissileAimSolution.resolve(new Vec3(Double.NaN, 0, 0), new Vec3(0, 0, 1)).isEmpty(),
                "non-finite muzzle");
        System.out.println("Missile aim solution passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
