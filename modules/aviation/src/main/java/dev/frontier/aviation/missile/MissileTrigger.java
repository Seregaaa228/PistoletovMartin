package dev.frontier.aviation.missile;

/**
 * Pure state machine for one physical missile-trigger press.
 *
 * <p>A release before {@link #SALVO_THRESHOLD_NANOS} emits SINGLE. Holding
 * through the threshold emits SALVO once. Context loss cancels the press and
 * requires a release before a later press can be accepted.</p>
 */
public final class MissileTrigger {
    public static final long SALVO_THRESHOLD_NANOS = 300_000_000L;

    public enum Action {
        NONE,
        SINGLE,
        SALVO
    }

    private boolean pressed;
    private boolean actionEmitted;
    private boolean cancelledUntilRelease;
    private long pressStartedNanos;

    /**
     * Advances the trigger using a caller-supplied monotonic timestamp.
     */
    public Action update(boolean isPressed, long nowNanos, boolean eligible) {
        if (!eligible) {
            if (isPressed) cancelledUntilRelease = true;
            else cancelledUntilRelease = false;
            clearPress();
            return Action.NONE;
        }

        if (!isPressed) {
            if (cancelledUntilRelease) {
                cancelledUntilRelease = false;
                clearPress();
                return Action.NONE;
            }
            if (!pressed) return Action.NONE;
            Action action = actionEmitted ? Action.NONE
                    : nowNanos >= pressStartedNanos && nowNanos - pressStartedNanos >= SALVO_THRESHOLD_NANOS
                    ? Action.SALVO : Action.SINGLE;
            clearPress();
            return action;
        }

        if (cancelledUntilRelease) return Action.NONE;

        if (!pressed) {
            pressed = true;
            actionEmitted = false;
            pressStartedNanos = nowNanos;
            return Action.NONE;
        }

        if (actionEmitted) return Action.NONE;

        // Ignore a backwards sample. This preserves the original press
        // origin and prevents a clock glitch from producing an early SALVO.
        if (nowNanos < pressStartedNanos) return Action.NONE;

        if (nowNanos - pressStartedNanos >= SALVO_THRESHOLD_NANOS) {
            actionEmitted = true;
            return Action.SALVO;
        }
        return Action.NONE;
    }

    /**
     * Cancels the current press. If it was held, release is required before a
     * later press is accepted.
     */
    public void reset() {
        cancelledUntilRelease |= pressed;
        clearPress();
    }

    private void clearPress() {
        pressed = false;
        actionEmitted = false;
        pressStartedNanos = 0L;
    }
}
