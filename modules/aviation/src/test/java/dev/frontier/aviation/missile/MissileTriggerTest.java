package dev.frontier.aviation.missile;

public final class MissileTriggerTest {
    private static final long THRESHOLD = MissileTrigger.SALVO_THRESHOLD_NANOS;

    private MissileTriggerTest() {}

    public static void main(String[] args) {
        shortRelease();
        exactThreshold();
        releaseAfterFrameGap();
        holdOneAction();
        cancelAndReenableWhileHeld();
        resetVehicleWhilePressed();
        nonmonotonicTimestampSafe();
        System.out.println("MissileTriggerTest PASS");
    }

    private static void shortRelease() {
        MissileTrigger trigger = new MissileTrigger();
        check(trigger.update(true, 1_000L, true) == MissileTrigger.Action.NONE,
                "short press starts");
        check(trigger.update(false, 1_000L + THRESHOLD - 1, true) == MissileTrigger.Action.SINGLE,
                "short release is SINGLE");
    }

    private static void exactThreshold() {
        MissileTrigger trigger = new MissileTrigger();
        check(trigger.update(true, 2_000L, true) == MissileTrigger.Action.NONE,
                "threshold press starts");
        check(trigger.update(true, 2_000L + THRESHOLD, true) == MissileTrigger.Action.SALVO,
                "exact threshold is SALVO");
    }

    private static void holdOneAction() {
        MissileTrigger trigger = new MissileTrigger();
        check(trigger.update(true, 0L, true) == MissileTrigger.Action.NONE,
                "hold starts");
        check(trigger.update(true, THRESHOLD, true) == MissileTrigger.Action.SALVO,
                "hold emits SALVO");
        check(trigger.update(true, THRESHOLD * 2, true) == MissileTrigger.Action.NONE,
                "hold does not repeat");
        check(trigger.update(false, THRESHOLD * 2 + 1, true) == MissileTrigger.Action.NONE,
                "SALVO release does not emit SINGLE");
    }

    private static void releaseAfterFrameGap() {
        MissileTrigger trigger = new MissileTrigger();
        trigger.update(true, 0, true);
        check(trigger.update(false, THRESHOLD, true) == MissileTrigger.Action.SALVO,
                "release at threshold preserves a held gesture even with no rendered sample");
        trigger.update(true, THRESHOLD + 1, true);
        trigger.reset();
        trigger.reset();
        check(trigger.update(true, THRESHOLD * 3, true) == MissileTrigger.Action.NONE,
                "repeated context cancellation cannot rearm a held button");
        check(trigger.update(false, THRESHOLD * 3 + 1, true) == MissileTrigger.Action.NONE,
                "release after repeated cancellation remains silent");
    }

    private static void cancelAndReenableWhileHeld() {
        MissileTrigger trigger = new MissileTrigger();
        check(trigger.update(true, 10L, true) == MissileTrigger.Action.NONE,
                "cancel press starts");
        check(trigger.update(true, 20L, false) == MissileTrigger.Action.NONE,
                "ineligible held press cancels");
        check(trigger.update(true, 10L + THRESHOLD * 2, true) == MissileTrigger.Action.NONE,
                "reenable while held stays cancelled");
        check(trigger.update(false, 10L + THRESHOLD * 2 + 1, true) == MissileTrigger.Action.NONE,
                "cancelled release is silent");
        check(trigger.update(true, 10L + THRESHOLD * 2 + 2, true) == MissileTrigger.Action.NONE,
                "new press starts after release");
        check(trigger.update(false, 10L + THRESHOLD * 2 + 3, true) == MissileTrigger.Action.SINGLE,
                "new short press is accepted");
    }

    private static void resetVehicleWhilePressed() {
        MissileTrigger trigger = new MissileTrigger();
        check(trigger.update(true, 100L, true) == MissileTrigger.Action.NONE,
                "vehicle press starts");
        trigger.reset();
        check(trigger.update(true, 100L + THRESHOLD * 2, true) == MissileTrigger.Action.NONE,
                "reset vehicle stays cancelled while held");
        check(trigger.update(false, 100L + THRESHOLD * 2 + 1, true) == MissileTrigger.Action.NONE,
                "reset release is silent");
        check(trigger.update(true, 100L + THRESHOLD * 2 + 2, true) == MissileTrigger.Action.NONE,
                "post-reset new press starts");
    }

    private static void nonmonotonicTimestampSafe() {
        MissileTrigger trigger = new MissileTrigger();
        check(trigger.update(true, 1_000L, true) == MissileTrigger.Action.NONE,
                "nonmonotonic press starts");
        check(trigger.update(true, 900L, true) == MissileTrigger.Action.NONE,
                "backwards time is ignored");
        check(trigger.update(true, 1_000L + THRESHOLD - 1, true) == MissileTrigger.Action.NONE,
                "original threshold not reached early");
        check(trigger.update(true, 1_000L + THRESHOLD, true) == MissileTrigger.Action.SALVO,
                "original threshold eventually emits SALVO");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
