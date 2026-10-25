package dev.frontier.industry.client.ui;

/** One transform for both drawing and pointer events; inventory slots keep their existing coordinates. */
public record UiViewport(double scale, double centerX, double centerY) {
    public static UiViewport fit(int screenWidth, int screenHeight, int contentWidth, int contentHeight) {
        double scale = Math.min(1, Math.min((screenWidth - 12.0) / contentWidth, (screenHeight - 12.0) / contentHeight));
        return new UiViewport(Math.max(0.01, scale), screenWidth / 2.0, screenHeight / 2.0);
    }

    public double screenX(double x) { return centerX + (x - centerX) * scale; }
    public double screenY(double y) { return centerY + (y - centerY) * scale; }
    public double logicalX(double x) { return centerX + (x - centerX) / scale; }
    public double logicalY(double y) { return centerY + (y - centerY) / scale; }
}
