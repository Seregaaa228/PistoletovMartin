package dev.frontier.industry.client.ui;

/** Logical modal bounds, shared by every scale of the screen transform. */
public record TooltipBounds(int left, int top, int width, int height) {
    public record Point(int x, int y) { }
    public int wrapWidth() { return Math.max(1, Math.min(280, width - 12)); }
    public Point position(int mouseX, int mouseY, int tooltipWidth, int tooltipHeight) {
        int minX = left + 6, minY = top + 6;
        int maxX = Math.max(minX, left + width - 6 - tooltipWidth);
        int maxY = Math.max(minY, top + height - 6 - tooltipHeight);
        int x = mouseX + 12;
        if (x > maxX) x = mouseX - tooltipWidth - 12;
        return new Point(Math.max(minX, Math.min(x, maxX)), Math.max(minY, Math.min(mouseY - 12, maxY)));
    }
}
