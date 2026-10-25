package dev.frontier.industry.client.ui;

/** A safe half-open slice after server refresh, filtering or a viewport change. */
public record UiPage(int index, int pages, int from, int to) {
    public static UiPage of(int requested, int itemCount, int pageSize) {
        int count = Math.max(0, itemCount), size = Math.max(1, pageSize);
        int pages = count == 0 ? 1 : 1 + (count - 1) / size;
        int index = Math.max(0, Math.min(requested, pages - 1));
        int from = (int)Math.min(count, (long)index * size);
        int to = (int)Math.min(count, (long)from + size);
        return new UiPage(index, pages, from, to);
    }
}
