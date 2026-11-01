package dev.frontier.aviation.weapons;

import java.util.Optional;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

/** Deterministic analytical and seeded property checks for {@link InterceptSolver}. */
public final class InterceptSolverTest {
    private static int checks;

    public static void main(String[] args) {
        samePointAndInvalidInputs();
        mirroredApproachingRecedingAndCrossing();
        inheritedVelocityIsCountedOnce();
        lifetimeBoundary();
        seededReconstruction();
        extremeFiniteInputs();
        variedScales();
        System.out.println("InterceptSolverTest: " + checks + " checks passed");
    }

    private static void samePointAndInvalidInputs() {
        Optional<InterceptSolver.Solution> same = solve(Vec3.ZERO, Vec3.ZERO, new Vec3(2, 0, 0),
                new Vec3(1, 0, 0), 4, 0);
        require(same.isPresent(), "same point has immediate solution");
        near(0, same.get().flightTicks(), "same point time");
        require(same.get().launchDirection().equals(Vec3.ZERO), "same point direction");
        require(solve(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), Vec3.ZERO, Vec3.ZERO, 4, 20).isEmpty(),
                "NaN rejected");
        require(solve(Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0), Vec3.ZERO, Vec3.ZERO, 4, 20).isEmpty(),
                "infinite position rejected");
        require(solve(Vec3.ZERO, Vec3.ZERO, new Vec3(0, Double.NEGATIVE_INFINITY, 0),
                Vec3.ZERO, 4, 20).isEmpty(), "infinite velocity rejected");
        require(solve(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 0, 20).isEmpty(),
                "zero airspeed rejected");
        require(solve(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Double.POSITIVE_INFINITY, 20).isEmpty(),
                "infinite airspeed rejected");
        require(solve(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 4, -1).isEmpty(),
                "negative lifetime rejected");
    }

    private static void mirroredApproachingRecedingAndCrossing() {
        InterceptSolver.Solution mirrored = requireSolution(solve(Vec3.ZERO, new Vec3(20, 0, 0),
                new Vec3(0, 3, 0), Vec3.ZERO, 5, 100), "mirrored");
        InterceptSolver.Solution reflected = requireSolution(solve(Vec3.ZERO, new Vec3(-20, 0, 0),
                new Vec3(0, 3, 0), Vec3.ZERO, 5, 100), "reflected");
        near(mirrored.flightTicks(), reflected.flightTicks(), "mirror time");
        near(-mirrored.aimPoint().x, reflected.aimPoint().x, "mirror x");
        near(mirrored.aimPoint().y, reflected.aimPoint().y, "mirror y");

        requireSolution(solve(Vec3.ZERO, new Vec3(30, 0, 0), new Vec3(-2, 0, 0),
                Vec3.ZERO, 5, 100), "approaching");
        requireSolution(solve(Vec3.ZERO, new Vec3(30, 0, 0), new Vec3(2, 0, 0),
                Vec3.ZERO, 5, 100), "receding");
        require(solve(Vec3.ZERO, new Vec3(30, 0, 0), new Vec3(10, 0, 0),
                Vec3.ZERO, 2, 100).isEmpty(), "unreachable receding target");
        requireSolution(solve(new Vec3(-10, 0, 0), new Vec3(10, 0, 0),
                new Vec3(0, 1, 0), Vec3.ZERO, 4, 100), "crossing");
    }

    private static void inheritedVelocityIsCountedOnce() {
        Vec3 inherited = new Vec3(3, 0, 0);
        InterceptSolver.Solution solution = requireSolution(solve(Vec3.ZERO, new Vec3(20, 0, 0),
                Vec3.ZERO, inherited, 2, 100), "inherited velocity");
        near(20.0 / 5.0, solution.flightTicks(), "inherited time");
        reconstruct(Vec3.ZERO, new Vec3(20, 0, 0), Vec3.ZERO, inherited, 2, solution, "inherited reconstruction");
    }

    private static void lifetimeBoundary() {
        InterceptSolver.Solution solution = requireSolution(solve(Vec3.ZERO, new Vec3(10, 0, 0),
                Vec3.ZERO, Vec3.ZERO, 2, 5), "exact lifetime");
        near(5, solution.flightTicks(), "exact lifetime accepted");
        require(solve(Vec3.ZERO, new Vec3(10, 0, 0), Vec3.ZERO, Vec3.ZERO, 2, 4.999).isEmpty(),
                "late intercept rejected");
    }

    private static void seededReconstruction() {
        Random random = new Random(0x51A7EEDL);
        for (int i = 0; i < 300; i++) {
            Vec3 shooter = randomVector(random, 100);
            Vec3 target = shooter.add(randomVector(random, 80));
            Vec3 targetVelocity = randomVector(random, 2);
            Vec3 inherited = randomVector(random, 2);
            // Keep the generated cases reachable while still varying the
            // relative-velocity geometry and flight time.
            double speed = 12 + random.nextDouble() * 12;
            InterceptSolver.Solution solution = requireSolution(solve(shooter, target, targetVelocity,
                    inherited, speed, 200), "seeded solution " + i);
            reconstruct(shooter, target, targetVelocity, inherited, speed, solution, "seeded reconstruction " + i);
        }
    }

    private static void extremeFiniteInputs() {
        InterceptSolver.Solution solution = requireSolution(solve(new Vec3(-1e150, 2e150, -3e150),
                new Vec3(1e150, -2e150, 3e150), new Vec3(1e100, -2e100, 3e100),
                new Vec3(-1e100, 2e100, -3e100), 1e150, 10), "extreme finite");
        require(Double.isFinite(solution.flightTicks()), "extreme time finite");
        require(Double.isFinite(solution.aimPoint().x) && Double.isFinite(solution.launchDirection().x),
                "extreme result finite");
        require(solve(new Vec3(Double.MAX_VALUE, 0, 0), new Vec3(-Double.MAX_VALUE, 0, 0),
                Vec3.ZERO, Vec3.ZERO, 1, 100).isEmpty(), "overflow displacement rejected");
    }

    private static void variedScales() {
        for (double scale : new double[]{1e-150, 1e-12, 1, 1e12, 1e150}) {
            Vec3 target = new Vec3(20 * scale, 0, 0);
            Vec3 velocity = new Vec3(0, 3 * scale, 0);
            Vec3 inherited = new Vec3(scale, 0, 0);
            var solution = requireSolution(solve(Vec3.ZERO, target, velocity, inherited, 5 * scale, 100), "scale " + scale);
            near(1, solution.launchDirection().length(), "unit direction at scale " + scale);
            reconstruct(Vec3.ZERO, target, velocity, inherited, 5 * scale, solution, "scale reconstruction");
        }
        var distant = requireSolution(solve(Vec3.ZERO, new Vec3(1e10, 0, 0), Vec3.ZERO, Vec3.ZERO,
            1, 1e11), "large distance relative to speed");
        near(1e10, distant.flightTicks(), "large distance time");
        var tiny = requireSolution(solve(Vec3.ZERO, new Vec3(1e-12, 0, 0), Vec3.ZERO, Vec3.ZERO,
            1, 1), "tiny noncoincident target");
        near(1, tiny.launchDirection().length(), "tiny target unit direction");
        require(solve(Vec3.ZERO, new Vec3(1e308, 0, 0), new Vec3(1e308, 0, 0),
            Vec3.ZERO, 1e308, 100).isEmpty(), "unreachable extreme target does not throw");
    }

    private static void reconstruct(Vec3 shooter, Vec3 initialTarget, Vec3 targetVelocity, Vec3 inherited, double speed,
            InterceptSolver.Solution solution, String message) {
        Vec3 projectile = shooter.add(inherited.add(solution.launchDirection().scale(speed))
                .scale(solution.flightTicks()));
        Vec3 target = initialTarget.add(targetVelocity.scale(solution.flightTicks()));
        near(target.x, solution.aimPoint().x, message + " target x");
        near(target.y, solution.aimPoint().y, message + " target y");
        near(target.z, solution.aimPoint().z, message + " target z");
        near(target.x, projectile.x, message + " x");
        near(target.y, projectile.y, message + " y");
        near(target.z, projectile.z, message + " z");
        checks++;
    }

    private static Vec3 randomVector(Random random, double limit) {
        return new Vec3((random.nextDouble() * 2 - 1) * limit,
                (random.nextDouble() * 2 - 1) * limit,
                (random.nextDouble() * 2 - 1) * limit);
    }

    private static Optional<InterceptSolver.Solution> solve(Vec3 shooter, Vec3 target, Vec3 velocity,
            Vec3 inherited, double speed, double lifetime) {
        return InterceptSolver.solve(shooter, target, velocity, inherited, speed, lifetime);
    }

    private static InterceptSolver.Solution requireSolution(Optional<InterceptSolver.Solution> value, String message) {
        require(value.isPresent(), message + " missing");
        return value.get();
    }

    private static void near(double expected, double actual, String message) {
        double tolerance = 1e-8 * Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
        require(Math.abs(expected - actual) <= tolerance, message + ": " + expected + " != " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
