package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.FlightState;

/** Native angle constraints sampled with a prediction frame, without native physics calls. */
public record FlightAttitudeLimits(float minimumPitch, float maximumPitch, float maximumRoll) {
    public static final FlightAttitudeLimits UNRESTRICTED =
        new FlightAttitudeLimits(-Float.MAX_VALUE, Float.MAX_VALUE, 180);

    public FlightAttitudeLimits {
        if (!Float.isFinite(minimumPitch) || !Float.isFinite(maximumPitch) || !Float.isFinite(maximumRoll)
            || minimumPitch > maximumPitch || maximumRoll < 0 || maximumRoll > 180) {
            throw new IllegalArgumentException("invalid native angle constraints");
        }
    }

    public FlightState constrain(FlightState state) {
        // Native capture preserves model rates at an angle limit; reversal still
        // brakes that rate through zero before starting in the opposite direction.
        return FlightPrediction.attitude(state,
            Math.max(minimumPitch, Math.min(maximumPitch, state.pitch())), state.yaw(),
            Math.max(-maximumRoll, Math.min(maximumRoll, state.roll())));
    }
}
