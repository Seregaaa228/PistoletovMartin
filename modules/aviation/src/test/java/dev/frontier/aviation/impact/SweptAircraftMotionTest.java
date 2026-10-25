package dev.frontier.aviation.impact;

public final class SweptAircraftMotionTest {
    private static final double[] AXES = {1,0,0, 0,1,0, 0,0,1};
    private static int checks;
    public static void main(String[] args) {
        double[][] walls = {{1.5,-10,-10, 2.5,10,10}, {-10,-10,2.5, 10,10,3.5}};
        var wing = new SweptAircraftBox.Box(0,0,0,.5,.15,.5,AXES);
        var first = first(wing, walls, 3,0,3);
        double[] old = SweptAircraftBox.clipMotion(first.time(), first.nx(), first.ny(), first.nz(),3,0,3);
        check(old[0] < 1 && old[2] == 3, "One-contact clipping tunnels through the second perpendicular wall");
        var corner = resolve(walls,3,0,3);
        check(corner.x() < 1 && corner.x() > .99 && corner.z() < 2 && corner.z() > 1.99,
                "Re-swept wing stops before both perpendicular walls");
        check(corner.contacts().size() == 2 && corner.sweeps() <= 4,
                "Corner resolves two actual contacts within bounded work");
        double[] velocity = corner.clipVelocity(3,0,3);
        check(velocity[0] == 0 && velocity[2] == 0, "Both inward velocity components are removed");
        var single = resolve(new double[][] {walls[0]},3,0,3);
        check(single.x() < 1 && Math.abs(single.z() - 3) < 1e-8, "Single-wall tangent stays complete");
        var near = resolve(new double[][] {{1.5,1,-10,2.5,2,10}},3,0,3);
        check(near.x() == 3 && near.z() == 3 && near.contacts().isEmpty(), "Separated wing near miss moves unchanged");
        var harmless = resolve(new double[][] {{.6,-10,-10,1.6,10,10}},.3,0,3);
        check(harmless.x() < .1 && Math.abs(harmless.z() - 3) < 1e-8
                && AircraftImpactRules.damage(Math.hypot(.3,3), harmless.contacts().get(0).normalSpeed(),0,0) == 0,
                "Harmless shallow contact still clips while preserving full tangent");
        var three = resolve(new double[][] {walls[0], walls[1], {-10,1.5,-10,10,2.5,10}},3,3,3);
        check(three.x() < 1 && three.y() < 1.35 && three.z() < 2 && three.contacts().size() == 3,
                "Three surfaces constrain all inward movement");
        var glide = resolve(new double[][] {{.5,-10,-10,1.5,10,10}},0,0,3);
        check(glide.z() == 3 && glide.contacts().isEmpty(), "Touching parallel slide stays safe");
        var blocked = SweptAircraftMotion.resolve(3,0,3, (ox,oy,oz,mx,my,mz) ->
                new SweptAircraftBox.Hit(0,-1,0,0));
        check(blocked.sweeps() <= 4 && blocked.x() == 0 && blocked.z() == 0,
                "Repeated pathological contact never appends untested displacement");
        System.out.println("SweptAircraftMotionTest: " + checks + " bounded sliding checks passed");
    }
    private static SweptAircraftMotion.Result resolve(double[][] walls, double x, double y, double z) {
        return SweptAircraftMotion.resolve(x,y,z,(ox,oy,oz,mx,my,mz) ->
                first(new SweptAircraftBox.Box(ox,oy,oz,.5,.15,.5,AXES), walls,mx,my,mz));
    }
    private static SweptAircraftBox.Hit first(SweptAircraftBox.Box box, double[][] walls, double x, double y, double z) {
        SweptAircraftBox.Hit earliest = null;
        for (double[] wall : walls) {
            var hit = SweptAircraftBox.contact(box,wall[0],wall[1],wall[2],wall[3],wall[4],wall[5],x,y,z);
            if (hit != null && (earliest == null || hit.time() < earliest.time())) earliest = hit;
        }
        return earliest;
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
