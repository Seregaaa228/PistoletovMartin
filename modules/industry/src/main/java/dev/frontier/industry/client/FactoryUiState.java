package dev.frontier.industry.client;

import java.util.List;

/** Small presentation state, independent of the game so paging/selection can be verified directly. */
public final class FactoryUiState {
    public static final int VISIBLE_ROWS = 4;
    private String selected = "";
    private int offset, quantity = 1;
    public String selected() { return selected; }
    public int offset() { return offset; }
    public int quantity() { return quantity; }
    public void quantity(int count) { quantity = Math.max(1, Math.min(64, count)); }
    public void select(String id) { selected = id; }
    public void reconcile(List<String> ids) {
        offset = Math.max(0, Math.min(offset, Math.max(0, ids.size() - VISIBLE_ROWS)));
        if (!ids.contains(selected)) selected = ids.isEmpty() ? "" : ids.get(0);
    }
    public void scroll(int delta, int size) { offset = Math.max(0, Math.min(offset + delta, Math.max(0, size - VISIBLE_ROWS))); }
    public void resetScroll() { offset = 0; }
}
