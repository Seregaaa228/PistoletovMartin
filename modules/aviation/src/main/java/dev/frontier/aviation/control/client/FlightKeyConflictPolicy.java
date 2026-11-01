package dev.frontier.aviation.control.client;

/** Decide whether the native edit binding yields to a flight control. */
public final class FlightKeyConflictPolicy {
    private FlightKeyConflictPolicy() {}

    public static boolean suppress(boolean pilotInput, boolean assigned, boolean selectedGear, boolean selectedHover) {
        return pilotInput && assigned && (selectedGear || selectedHover);
    }
}
