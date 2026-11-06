package dev.frontier.aviation.control.client;

import dev.frontier.aviation.control.FlightControlModel;

/** Native render getters use linear interpolation, so previous angles need the nearest equivalent. */
public final class FlightRenderAngles {
    private FlightRenderAngles() {}
    public static float previous(float previous, float current) {
        return current - FlightControlModel.wrapDegrees(current - previous);
    }
}
