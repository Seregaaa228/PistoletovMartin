package dev.frontier.aviation.aircraft;

import java.util.List;

/** Common-side model metadata; resource paths follow the stable entity ID. No renderer classes. */
public record AircraftModel(float width, float height, List<Exhaust> exhausts, float exhaustScale) {
    public record Exhaust(double x, double y, double z) {
        public Exhaust {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("nonfinite exhaust position");
        }
    }

    public AircraftModel {
        exhausts = List.copyOf(exhausts);
        if (!Float.isFinite(width) || width <= 0 || !Float.isFinite(height) || height <= 0
                || !Float.isFinite(exhaustScale) || exhaustScale <= 0)
            throw new IllegalArgumentException("invalid model dimensions or exhaust scale");
    }
}
