package dev.frontier.industry.engine;

/** Inclusive local footprint, independent of the world and block registry. */
public record BlueprintBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public BlueprintBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Inverted blueprint bounds");
    }
    public int width() { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public int radius() { return Math.max(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.abs(minZ), Math.abs(maxZ))); }
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
