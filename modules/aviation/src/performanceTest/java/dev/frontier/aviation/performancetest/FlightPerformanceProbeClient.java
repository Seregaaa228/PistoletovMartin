package dev.frontier.aviation.performancetest;

import net.minecraft.client.Minecraft;

/** Client class is linked only by the explicit Dist.CLIENT branch in the test helper. */
final class FlightPerformanceProbeClient {
    private static final String OWNER = "dev.frontier.aviation.client.FlightFramePerformance";
    private static String phase;

    static void begin(String label) {
        Minecraft.getInstance().execute(() -> {
            FlightPerformanceProbe.histogram(OWNER, "FRAME_INTERVAL").reset();
            try { FlightPerformanceProbe.field(OWNER, "hasPreviousFrame").setBoolean(null, false); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            phase = label;
        });
    }

    static void end(String label) {
        Minecraft.getInstance().execute(() -> {
            if (!label.equals(phase)) throw new IllegalStateException("client phase mismatch");
            FlightPerformanceProbe.report("client", label, FlightPerformanceProbe.histogram(OWNER, "FRAME_INTERVAL"));
            phase = null;
        });
    }
}
