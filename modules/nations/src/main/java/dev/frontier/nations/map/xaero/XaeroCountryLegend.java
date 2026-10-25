package dev.frontier.nations.map.xaero;

/** Legacy source compatibility. The country list now belongs to the Xaero panel. */
@Deprecated
public final class XaeroCountryLegend {
    private XaeroCountryLegend(){}
    private static boolean collapsed;
    public static boolean isCollapsed(){return collapsed;}
    public static boolean toggleCollapsed(){return collapsed=!collapsed;}
}
