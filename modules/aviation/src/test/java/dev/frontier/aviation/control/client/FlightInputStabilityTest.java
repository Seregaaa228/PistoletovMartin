package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;

/** Deterministic raw-event delivery checks; no Minecraft, GLFW, rendering or world bootstrap. */
public final class FlightInputStabilityTest {
    private static int checks;

    public static void main(String[] args) {
        eventBatchingDoesNotChangeCommands();
        reversingEventsCancelBeforeAxisClamping();
        invalidSensitivityIsBounded();
        resetDropsOnlyPreviousSessionEvents();
        heldFramesHaveDistinctSequences();
        modeEdgesSurviveInputUpdatesUntilPhysics();
        focusAndSessionResets();
        System.out.println("FlightInputStabilityTest: " + checks + " checks passed (pure input only)");
    }

    private static void eventBatchingDoesNotChangeCommands() {
        // A polling mouse can deliver one callback or many callbacks in a 50 ms tick.
        // Keep the same tick displacement while varying callback count and direction.
        for (double sensitivity : new double[] {0, .5, 1}) {
            for (boolean inverted : new boolean[] {false, true}) {
                for (int callbacks : new int[] {1, 8, 50}) {
                    FlightMouseInput whole = new FlightMouseInput();
                    FlightMouseInput split = new FlightMouseInput();
                    for (int tick = 0; tick < 20; tick++) {
                        double dx = 22 * Math.sin(tick * .09);
                        double dy = 15 * Math.cos(tick * .13);
                        whole.add(dx, dy);
                        for (int event = 0; event < callbacks; event++) split.add(dx / callbacks, dy / callbacks);
                        var expected = whole.consume(sensitivity, inverted);
                        var actual = split.consume(sensitivity, inverted);
                        near(expected.horizontal(), actual.horizontal(), "horizontal callback partition");
                        near(expected.vertical(), actual.vertical(), "vertical callback partition");
                        var empty = split.consume(sensitivity, inverted);
                        near(0, empty.horizontal(), "no duplicate horizontal on catch-up tick");
                        near(0, empty.vertical(), "no duplicate vertical on catch-up tick");
                    }
                }
            }
        }
    }

    private static void heldFramesHaveDistinctSequences() {
        FlightCommandBuffer buffer = new FlightCommandBuffer();
        // START can run without native aircraft physics (for example during pause).
        // Repeated updates must neither reserve frame IDs nor lose a held command.
        buffer.update(intent(1, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        buffer.update(intent(-1, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        for (int frame = 1; frame <= 12; frame++) {
            PilotCommand command = buffer.next();
            check(command.sequence() == frame, "unique sequence for each actual physics frame");
            near(-1, command.rollAxis(), "held A/D survives frame consumption");
            near(.25f, command.pitchAxis(), "held mouse intent survives until next input update");
            check(command.brake() && command.secondaryBrake() && command.boost(), "held buttons survive frame consumption");
        }
    }

    private static void modeEdgesSurviveInputUpdatesUntilPhysics() {
        FlightCommandBuffer buffer = new FlightCommandBuffer();
        buffer.update(intent(1, ModeRequest.ENABLE, ModeRequest.KEEP, ModeRequest.KEEP));
        buffer.update(intent(-1, ModeRequest.KEEP, ModeRequest.DISABLE, ModeRequest.ENABLE));
        buffer.update(intent(0, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        PilotCommand pulse = buffer.next();
        check(pulse.sequence() == 1, "input updates do not consume simulation sequence");
        check(pulse.ignition() == ModeRequest.ENABLE && pulse.gear() == ModeRequest.DISABLE && pulse.hover() == ModeRequest.ENABLE,
            "KEEP updates cannot erase pending mode edges before physics");
        near(0, pulse.rollAxis(), "latest held axes win while pending edges survive");
        PilotCommand held = buffer.next();
        check(held.ignition() == ModeRequest.KEEP && held.gear() == ModeRequest.KEEP && held.hover() == ModeRequest.KEEP,
            "mode edges apply exactly once");
        buffer.update(intent(1, ModeRequest.ENABLE, ModeRequest.ENABLE, ModeRequest.DISABLE));
        buffer.update(intent(1, ModeRequest.DISABLE, ModeRequest.DISABLE, ModeRequest.ENABLE));
        PilotCommand latest = buffer.next();
        check(latest.ignition() == ModeRequest.DISABLE && latest.gear() == ModeRequest.DISABLE && latest.hover() == ModeRequest.ENABLE,
            "latest explicit mode selection wins before a physics frame");
    }

    private static void focusAndSessionResets() {
        FlightCommandBuffer buffer = new FlightCommandBuffer();
        buffer.update(intent(1, ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP));
        buffer.next();
        buffer.update(intent(1, ModeRequest.ENABLE, ModeRequest.DISABLE, ModeRequest.ENABLE));
        buffer.clearTransient();
        check(buffer.next().equals(PilotCommand.neutral(2)), "focus loss cancels held input and pending mode edges, preserving frame order");
        buffer.update(intent(-1, ModeRequest.ENABLE, ModeRequest.ENABLE, ModeRequest.DISABLE));
        buffer.reset();
        check(buffer.next().equals(PilotCommand.neutral(1)), "new session clears input and starts its own frame sequence");
    }

    private static PilotCommand intent(float roll, ModeRequest ignition, ModeRequest gear, ModeRequest hover) {
        return new PilotCommand(0, .25f, -.5f, roll, .75f, true, true, true, ignition, gear, hover);
    }

    private static void reversingEventsCancelBeforeAxisClamping() {
        FlightMouseInput mouse = new FlightMouseInput();
        // Both callbacks alone saturate an axis; their sum must still cancel.
        mouse.add(256, -256);
        mouse.add(-256, 256);
        var cancelled = mouse.consume(.5, false);
        near(0, cancelled.horizontal(), "opposing horizontal events cancel before normalization");
        near(0, cancelled.vertical(), "opposing vertical events cancel before normalization");
        mouse.add(16, -8);
        var positive = mouse.consume(.5, false);
        mouse.add(-16, 8);
        var negative = mouse.consume(.5, false);
        near(-positive.horizontal(), negative.horizontal(), "reversal has no stale horizontal tail");
        near(-positive.vertical(), negative.vertical(), "reversal has no stale vertical tail");
    }

    private static void invalidSensitivityIsBounded() {
        FlightMouseInput mouse = new FlightMouseInput();
        mouse.add(4, 4);
        var fallback = mouse.consume(Double.NaN, false);
        mouse.add(4, 4);
        var low = mouse.consume(-3, false);
        mouse.add(4, 4);
        var high = mouse.consume(9, false);
        near(.008f, low.horizontal(), "negative sensitivity clamps to zero");
        near(.512f, high.horizontal(), "oversized sensitivity clamps to one");
        near(.125f, fallback.horizontal(), "non-finite sensitivity uses the documented midpoint default");
        check(Float.isFinite(fallback.horizontal()), "invalid sensitivity cannot produce NaN input");
    }

    private static void resetDropsOnlyPreviousSessionEvents() {
        FlightMouseInput mouse = new FlightMouseInput();
        for (int transition = 0; transition < 40; transition++) {
            mouse.add(800, -800);
            mouse.clear(); // Focus, screen, free-camera, vehicle or lease transition.
            mouse.add(4, 8);
            var fresh = mouse.consume(.5, (transition & 1) == 1);
            near(.125f, fresh.horizontal(), "first fresh horizontal event survives reset");
            near((transition & 1) == 1 ? -.25f : .25f, fresh.vertical(), "inversion applied exactly once after reset");
        }
    }

    private static void near(float expected, float actual, String label) {
        check(Float.isFinite(actual) && Math.abs(expected - actual) <= .000001f,
            label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
