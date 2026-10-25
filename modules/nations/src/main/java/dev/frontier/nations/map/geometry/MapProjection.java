package dev.frontier.nations.map.geometry;

import dev.frontier.nations.map.geometry.BorderPolygon.Point;

/** Xaero's zoom is in framebuffer pixels, while Forge mouse events use GUI pixels. */
public record MapProjection(double cameraX, double cameraZ, double scale, double centerX, double centerY) {
    public MapProjection {
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ) || !Double.isFinite(scale) || scale <= 0)
            throw new IllegalArgumentException("Некорректный масштаб карты");
    }
    public static MapProjection physical(double x,double z,double zoom,double guiScale,int width,int height) {
        if (!Double.isFinite(guiScale) || guiScale <= 0) throw new IllegalArgumentException("Некорректный масштаб интерфейса");
        return new MapProjection(x,z,zoom/guiScale,(width/2)/guiScale,(height/2)/guiScale);
    }
    public Point screen(Point world) {return new Point(centerX+(world.x()-cameraX)*scale, centerY+(world.z()-cameraZ)*scale);}
    public Point world(Point screen) {return new Point(cameraX+(screen.x()-centerX)/scale, cameraZ+(screen.z()-centerY)/scale);}
}
