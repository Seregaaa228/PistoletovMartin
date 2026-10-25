package dev.frontier.nations.map.xaero;

import dev.frontier.nations.map.geometry.MapProjection;

/** Implemented only on the exact pinned GuiMap by the client Mixin. */
public interface XaeroMapAccess {
    MapProjection frontier$projection();
    void frontier$pan(double guiDx,double guiDy);
}
