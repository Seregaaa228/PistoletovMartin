package dev.frontier.aviation.client;

public final class F16ControlSurfacesTest {
    public static void main(String[] args) {
        F16ControlSurfaces local = new F16ControlSurfaces();
        var command = new F16ControlSurfaces.Axes(1, -1, 0.5f);
        local.tick(command, new F16ControlSurfaces.Axes(-1, 1, -1), 0, 0, 0);
        near(0, local.at(0).roll(), "previous tick pose");
        near(0.225f, local.at(0.5f).roll(), "partial tick interpolation");
        near(0.45f, local.at(1).roll(), "local input overrides remote rates");
        near(-0.45f, local.at(1).pitch(), "pitch input");
        near(0.225f, local.at(1).yaw(), "yaw input");
        near(local.at(0).roll(), local.at(Float.NaN).roll(), "invalid partial");
        near(local.at(1).roll(), local.at(2).roll(), "bounded partial");
        for (int i = 0; i < 30; i++) local.tick(new F16ControlSurfaces.Axes(0, 0, 0), null, 0, 0, 0);
        near(0, local.at(1).roll(), "returns to neutral");

        F16ControlSurfaces impulse = new F16ControlSurfaces();
        impulse.tick(new F16ControlSurfaces.Axes(0, 1, 0), new F16ControlSurfaces.Axes(0, 0, 0), 0, 0, 0);
        impulse.tick(new F16ControlSurfaces.Axes(0, 0, 0), new F16ControlSurfaces.Axes(0, 0.8f, 0), 0, 0, 0);
        check(impulse.at(1).pitch() > 0.45f, "surface follows sustained motion after mouse impulse");
        impulse.tick(new F16ControlSurfaces.Axes(0, -1, 0), new F16ControlSurfaces.Axes(0, 0.8f, 0), 0, 0, 0);
        check(impulse.at(1).pitch() < impulse.at(0).pitch(), "opposing pilot input overrides motion");

        F16ControlSurfaces remote = new F16ControlSurfaces();
        remote.tick(null, new F16ControlSurfaces.Axes(1.5f, -0.8f, 0.4f), 0, 0, 0);
        near(0.45f, remote.at(1).roll(), "remote synchronized rates");
        near(-0.45f, remote.at(1).pitch(), "remote pitch");
        F16ControlSurfaces bot = new F16ControlSurfaces();
        bot.tick(null, null, 179.8f, 0, 0);
        near(0, bot.at(1).roll(), "no spawn motion spike");
        bot.tick(null, null, -179.8f, 0, 0);
        near(0.12f, bot.at(1).roll(), "short angular delta across wrap");
        F16ControlSurfaces independent = new F16ControlSurfaces();
        near(0, independent.at(1).roll(), "separate aircraft starts neutral");
        var held = bot.at(0.5f);
        for (int i = 0; i < 100; i++) bot.at(i / 100f);
        near(held.roll(), bot.at(0.5f).roll(), "render frames do not advance state");
        bot.tick(new F16ControlSurfaces.Axes(Float.NaN, Float.POSITIVE_INFINITY, -20), null, 0, 0, 0);
        check(Float.isFinite(bot.at(1).roll()) && bot.at(1).yaw() >= -1, "invalid and extreme inputs bounded");
        System.out.println("F16ControlSurfacesTest passed");
    }
    private static void near(float expected, float actual, String message) {
        check(Float.isFinite(actual) && Math.abs(expected - actual) < 0.0001f, message + ": " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
