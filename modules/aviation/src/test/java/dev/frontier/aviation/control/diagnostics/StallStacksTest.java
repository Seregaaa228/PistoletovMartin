package dev.frontier.aviation.control.diagnostics;

/** Pure stack reduction: no threads are stalled here. */
public final class StallStacksTest {
    private static int checks;

    public static void main(String[] args) {
        signatureSkipsJdkFramesBelowTheTop();
        signatureIsBounded();
        summaryOrdersByFrequency();
        emptySummary();
        System.out.println("StallStacksTest: " + checks + " checks passed (synthetic stacks)");
    }

    private static StackTraceElement frame(String type, String method, int line) {
        return new StackTraceElement(type, method, type + ".java", line);
    }

    private static void signatureSkipsJdkFramesBelowTheTop() {
        String signature = StallStacks.signature(new StackTraceElement[] {
            frame("jdk.internal.misc.Unsafe", "park", -2),
            frame("java.util.concurrent.locks.LockSupport", "park", 211),
            frame("net.minecraft.server.level.ServerChunkCache", "m_7587_", 155),
            frame("java.util.concurrent.CompletableFuture", "join", 2044),
            frame("dev.sbw.deepstrike.vehicle.Su24mEntity", "m_8119_", 90)});
        check(signature.equals("Unsafe.park < ServerChunkCache.m_7587_:155 < Su24mEntity.m_8119_:90"),
            "the waiting top frame stays, JDK frames below it go: " + signature);
    }

    private static void signatureIsBounded() {
        StackTraceElement[] deep = new StackTraceElement[40];
        for (int i = 0; i < deep.length; i++) deep[i] = frame("game.Frame" + i, "run", i + 1);
        check(StallStacks.signature(deep).split(" < ").length == StallStacks.DEPTH, "at most DEPTH frames");
        check(StallStacks.signature(new StackTraceElement[0]).equals("(no frames)"), "an empty stack is named");
    }

    private static void summaryOrdersByFrequency() {
        StallStacks stacks = new StallStacks();
        StackTraceElement[] rare = {frame("game.Rare", "work", 1)};
        StackTraceElement[] common = {frame("game.Common", "work", 2)};
        stacks.add(rare);
        for (int i = 0; i < 5; i++) stacks.add(common);
        String summary = stacks.summary("Server thread", 812);
        check(stacks.samples() == 6, "six samples");
        check(summary.startsWith("[frontier-stall] Server thread stalled 812 ms (6 samples)"), "header: " + summary);
        String[] lines = summary.split("\n");
        check(lines.length == 3 && lines[1].endsWith("5x Common.work:2") && lines[2].endsWith("1x Rare.work:1"),
            "most frequent place first: " + summary);
    }

    private static void emptySummary() {
        check(new StallStacks().summary("Render thread", 300).endsWith(": no stack captured"), "no samples, said so");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
