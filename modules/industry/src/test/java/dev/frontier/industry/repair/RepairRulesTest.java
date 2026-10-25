package dev.frontier.industry.repair;

/** Standalone boundary checks; no Minecraft runtime or test dependency. */
public final class RepairRulesTest {
    private static int checks;
    private static void check(String label, boolean result) {
        checks++;
        if (!result) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        check("factory repair is separate from grid", RepairRules.isRepairRole("factory_repair") && RepairRules.isRepairRole("grid_repair")
            && !RepairRules.isRepairRole("production") && !RepairRules.isRepairRole("builder"));
        check("foreign worker cannot repair at the entrance", !RepairRules.mayWork(false, true, true, true, true, 0));
        check("unloaded site pauses work", !RepairRules.mayWork(true, false, true, true, true, 0));
        check("cancelled order cannot make progress", !RepairRules.mayWork(true, true, false, true, true, 0));
        check("completed site cannot finish again", !RepairRules.mayWork(true, true, true, false, true, 0));
        check("dead or occupied worker cannot repair", !RepairRules.mayWork(true, true, true, true, false, 0));
        check("worker standing outside approach cannot finish remotely", !RepairRules.mayWork(true, true, true, true, true, 6.25001));
        check("exact approach boundary can work", RepairRules.mayWork(true, true, true, true, true, 6.25));
        check("nonfinite approach rejected", !RepairRules.mayWork(true, true, true, true, true, Double.NaN));
        check("path retries wait five seconds", !RepairRules.pathAttemptDue(100, 199) && RepairRules.pathAttemptDue(100, 200));
        check("fresh worker can request first path immediately", RepairRules.pathAttemptDue(Long.MIN_VALUE, 0));
        check("retry clock handles a saved future timestamp", RepairRules.pathAttemptDue(10000, 10));
        System.out.println("RepairRulesTest: " + checks + " checks passed");
    }
}
