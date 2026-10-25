package dev.frontier.industry.client.ui;

/** Exercises real screen bounds and pointer hit testing at supported scaled resolutions. */
public final class UiViewportTest {
    public static void main(String[] args) {
        int[][] windows = {{960, 540}, {640, 360}, {320, 240}, {427, 240}};
        int checks = 0;
        for (int[] window : windows) for (int[] layout : new int[][] {{356, 268}, {560, 342}}) {
            int contentWidth = layout[0], contentHeight = layout[1];
            UiViewport view = UiViewport.fit(window[0], window[1], contentWidth, contentHeight);
            double left = view.screenX((window[0] - contentWidth) / 2.0);
            double top = view.screenY((window[1] - contentHeight) / 2.0);
            check(left >= 5 && top >= 5, "factory remains inside the screen");
            check(left + contentWidth * view.scale() <= window[0] - 5, "right edge visible");
            check(top + contentHeight * view.scale() <= window[1] - 5, "bottom action visible");
            // Output slot 23 and final action are deliberately near the far edges.
            for (double[] point : new double[][] {{(window[0] - contentWidth) / 2.0 + 330, (window[1] - contentHeight) / 2.0 + 101},
                    {(window[0] - contentWidth) / 2.0 + 178, (window[1] - contentHeight) / 2.0 + 251}}) {
                check(Math.abs(view.logicalX(view.screenX(point[0])) - point[0]) < 0.00001, "pointer X returns to its rendered control");
                check(Math.abs(view.logicalY(view.screenY(point[1])) - point[1]) < 0.00001, "pointer Y returns to its rendered control");
            }
            checks += 7;
        }
        UiViewport large = UiViewport.fit(960, 540, 356, 268);
        check(large.scale() == 1, "normal GUI is not enlarged");
        System.out.println("UiViewportTest: " + (checks + 1) + " checks passed");
    }
    private static void check(boolean valid, String reason) { if (!valid) throw new AssertionError(reason); }
}
