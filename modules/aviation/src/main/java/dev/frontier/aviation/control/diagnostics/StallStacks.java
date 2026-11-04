package dev.frontier.aviation.control.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack samples of one stalled thread, reduced to the few places it stood. Each sample's
 * signature is its top frame plus the next frames outside the JDK, so a thread parked in
 * LockSupport still shows which game code was waiting.
 */
public final class StallStacks {
    static final int DEPTH = 8;
    private static final int SHOWN = 3;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int samples;

    public void add(StackTraceElement[] stack) {
        counts.merge(signature(stack), 1, Integer::sum);
        samples++;
    }

    public int samples() { return samples; }

    static String signature(StackTraceElement[] stack) {
        List<String> frames = new ArrayList<>();
        for (int i = 0; i < stack.length && frames.size() < DEPTH; i++) {
            String type = stack[i].getClassName();
            if (i > 0 && (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun."))) continue;
            frames.add(type.substring(type.lastIndexOf('.') + 1) + '.' + stack[i].getMethodName()
                + (stack[i].getLineNumber() > 0 ? ":" + stack[i].getLineNumber() : ""));
        }
        return frames.isEmpty() ? "(no frames)" : String.join(" < ", frames);
    }

    /** The report logged once the thread moves again: duration, then the most frequent places. */
    public String summary(String thread, long millis) {
        StringBuilder text = new StringBuilder("[frontier-stall] ").append(thread).append(" stalled ")
            .append(millis).append(" ms (").append(samples).append(" samples)");
        if (samples == 0) return text.append(": no stack captured").toString();
        counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(SHOWN)
            .forEach(entry -> text.append("\n[frontier-stall]   ").append(entry.getValue()).append("x ").append(entry.getKey()));
        return text.toString();
    }
}
