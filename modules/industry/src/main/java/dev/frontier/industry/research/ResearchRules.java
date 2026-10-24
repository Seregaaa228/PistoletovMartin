package dev.frontier.industry.research;
import java.util.Set;
public final class ResearchRules {
    private ResearchRules() {}
    public static boolean available(Technology technology, Set<String> completed) {
        return technology != null && !completed.contains(technology.id) && completed.containsAll(technology.prerequisites);
    }
    public static int advance(int progress, int total, boolean working) {
        return Math.max(0, Math.min(total, progress + (working ? 1 : 0)));
    }
}
