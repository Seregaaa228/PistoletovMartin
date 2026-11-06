package dev.frontier.aviation.control.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class FlightDiagnosticsTest {
    public static void main(String[] args) {
        boolean enabled = args.length > 0 && args[0].equals("enabled");
        if (FlightDiagnostics.ENABLED != enabled) throw new AssertionError("property");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            for (int i = 0; i < 100_000; i++) {
                FlightDiagnostics.submitted(i % 2 == 0, 3);
                FlightDiagnostics.dequeued(i % 2 == 0);
                FlightDiagnostics.snapshot(80);
                if (i % 2 == 0) FlightDiagnostics.evictedPending();
            }
            FlightDiagnostics.dumpClient();
            FlightDiagnostics.dumpClient(); // Client reset must not reset server.
            FlightDiagnostics.dumpServer();
            FlightDiagnostics.dumpServer();
        } finally { System.setOut(original); }
        String output = bytes.toString(StandardCharsets.UTF_8);
        if (!enabled && !output.isEmpty()) throw new AssertionError("disabled output");
        if (enabled && (!output.contains("accepted=100000 merged=50000 dequeued=50000 empty=50000")
            || !output.contains("depth3=100000") || !output.contains("localPredictionSnapshots=100000 pendingSum=8000000 pendingMax=80 evictedPending=50000")))
            throw new AssertionError(output);
        if (enabled && (!output.contains("localPredictionSnapshots=0 pendingSum=0 pendingMax=0 evictedPending=0")
            || !output.contains("accepted=0 merged=0 dequeued=0 empty=0")))
            throw new AssertionError("reset: " + output);
        System.out.println("FlightDiagnosticsTest " + (enabled ? "enabled" : "disabled") + " PASS");
    }
}
