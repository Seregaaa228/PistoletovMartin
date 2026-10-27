package dev.frontier.aviation.weapons;

import net.minecraft.world.phys.Vec3;

public final class WeaponAimResolverContractTest {
    public static void main(String[] args) {
        check(WeaponAimResolver.finite(new Vec3(1, 2, 3)), "finite vector");
        check(!WeaponAimResolver.finite(new Vec3(Double.NaN, 0, 0)), "NaN vector");
        check(!WeaponAimResolver.finite(null), "null vector");
        var point = WeaponAimResult.block(new Vec3(1, 2, 3));
        check(point.entity().isEmpty(), "point has no entity");
        check(point.kind() == WeaponAimResult.Kind.BLOCK, "block hit distinct from free space");
        check(WeaponAimResult.space(new Vec3(1, 2, 3)).kind() == WeaponAimResult.Kind.SPACE, "free-flight endpoint");
        var from = Vec3.ZERO;
        var forward = new Vec3(0, 0, 1);
        check(WeaponAimResolver.canResolvePoint(from, new Vec3(0, 0, 100), forward, 256, 20), "in sector");
        check(!WeaponAimResolver.canResolvePoint(from, new Vec3(100, 0, 1), forward, 256, 20), "side proposal rejected");
        check(!WeaponAimResolver.canResolvePoint(from, new Vec3(0, 0, -1), forward, 256, 20), "rear proposal rejected");
        check(!WeaponAimResolver.canResolvePoint(from, new Vec3(0, 0, 257), forward, 256, 20), "range enforced");
        check(!WeaponAimResolver.canResolvePoint(from, from, forward, 256, 20), "empty direction rejected");
        check(!WeaponAimResolver.canResolvePoint(from, new Vec3(0, 0, 10), forward, Double.POSITIVE_INFINITY, 20), "unbounded range rejected");
        check(!WeaponAimResolver.canResolvePoint(from, new Vec3(0, 0, 10), forward, 256, Double.NaN), "invalid angle rejected");
        System.out.println("Weapon aim resolver contract passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
