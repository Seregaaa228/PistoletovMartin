#!/usr/bin/env python3
"""Paint the air defence's effect atlas (smoke puffs, fireball frames, flash, flame, spark).

    python3 modules/arcade/art/sam_fx_texture.py [--preview DIR]

One 1024x512 texture of 128 px cells, drawn by SamFx.java with linear filtering:
  row 0      smoke puffs 0..7: soft lumpy clouds, grey shading lit from above (tinted per sprite)
  rows 1..2  fireball frames 0..15: white-hot core, orange, red, then dark smoke, growing ragged
  row 3      0 flash (round glow), 1 flame (motor fire tongue), 2 spark (hot dot)
Smoke, fireball, flame and spark are straight alpha (drawn blended, sorted far to near); the flash
is premultiplied (black where transparent) because it is drawn additive.
Every cell keeps a transparent border so linear filtering never bleeds into its neighbour.
The generator is seeded: the same script always writes the same image.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / "modules/arcade/src/main/resources/assets/frontier_arcade_vehicles/textures/effect/sam_fx.png"
CELL = 128
RNG = np.random.default_rng(1957)


def noise(size: int, octaves: int = 5, base: int = 4) -> np.ndarray:
    """Smooth fractal noise in [0, 1]: random grids upsampled bicubically and summed."""
    out = np.zeros((size, size), np.float32)
    amplitude, total = 1.0, 0.0
    for o in range(octaves):
        grid = base << o
        layer = RNG.random((grid, grid)).astype(np.float32)
        img = Image.fromarray((layer * 255).astype(np.uint8)).resize((size, size), Image.BICUBIC)
        out += amplitude * np.asarray(img, np.float32) / 255
        total += amplitude
        amplitude *= 0.5
    out /= total
    return (out - out.min()) / (out.max() - out.min() + 1e-6)


def polar(size: int):
    c = (size - 1) / 2
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    dx, dy = (x - c) / c, (y - c) / c
    return np.hypot(dx, dy), np.arctan2(dy, dx), dx, dy


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def lumpy_radius(theta: np.ndarray, lumps: int, depth: float) -> np.ndarray:
    """A ragged outline: a few random harmonics around the circle."""
    r = np.ones_like(theta)
    for k in range(2, 2 + lumps):
        r += depth / k * np.cos(k * theta + RNG.random() * 6.283) * (0.5 + RNG.random())
    return r


def smoke_cell() -> np.ndarray:
    r, theta, dx, dy = polar(CELL)
    n, warp = noise(CELL, 6, 3), noise(CELL, 4, 3)
    # Billowy outline: the radius pushed in and out by the noise, not a polygon.
    rr = r + 0.28 * (warp - 0.5)
    edge = 0.97 * lumpy_radius(theta, 4, 0.06)
    density = smoothstep(edge, edge * 0.4, rr) * (0.5 + 0.7 * n)
    alpha = np.clip(density * 1.35, 0, 1) * smoothstep(0.99, 0.88, r)
    # Lit from above: the top of the puff a little brighter, billows shaded by the noise.
    shade = np.clip(0.80 + 0.14 * (-dy) + 0.16 * (n - 0.5) * 2, 0.62, 1.0)
    rgba = np.zeros((CELL, CELL, 4), np.float32)
    rgba[..., 0] = rgba[..., 1] = rgba[..., 2] = shade
    rgba[..., 3] = alpha
    return rgba


def fire_colour(heat: np.ndarray) -> np.ndarray:
    """Blackbody-ish ramp: 0 soot, 0.35 deep red, 0.6 orange, 0.85 yellow, 1 white."""
    stops = np.array([0.0, 0.3, 0.55, 0.8, 1.0])
    cols = np.array([[0.10, 0.08, 0.07], [0.55, 0.10, 0.03], [1.0, 0.42, 0.08], [1.0, 0.80, 0.35], [1.0, 0.98, 0.9]])
    out = np.empty(heat.shape + (3,), np.float32)
    for ch in range(3):
        out[..., ch] = np.interp(heat, stops, cols[:, ch])
    return out


def fireball_frames(count: int = 16) -> list[np.ndarray]:
    r, theta, _, dy = polar(CELL)
    n1, n2, warp = noise(CELL, 6, 3), noise(CELL, 5, 5), noise(CELL, 4, 3)
    outline = lumpy_radius(theta, 5, 0.06)
    frames = []
    for f in range(count):
        t = f / (count - 1)
        radius = (0.7 + 0.26 * (1 - (1 - t) ** 2)) * outline
        turbulence = 0.5 * n1 + 0.5 * np.roll(n2, int(t * 18), axis=0)
        rr = r + (0.22 + 0.25 * t) * (warp - 0.5) + 0.2 * (turbulence - 0.5) * (0.4 + t)
        body = smoothstep(radius, radius * (0.2 + 0.3 * t), rr)
        heat = np.clip(body * (1.15 - 1.25 * t) + (turbulence - 0.5) * 0.35 + (1 - r) * 0.25 * (1 - t), 0, 1)
        alpha = np.clip(body * (1.1 - 0.35 * t), 0, 1) * smoothstep(0.99, 0.9, r)
        # No dark rim on the young fireball: its cool edge is thin glowing gas, not soot.
        alpha *= 1 - (1 - smoothstep(0.04, 0.3, heat)) * (1 - t) * 0.85
        # Burnt-out frames: the fire collapses into holes and soot.
        if t > 0.55:
            alpha *= np.clip(1 - (t - 0.55) * 1.6 * (1 - turbulence), 0, 1)
        frames.append(np.concatenate([fire_colour(heat), alpha[..., None]], axis=-1))
    return frames


def flash_cell() -> np.ndarray:
    r, theta, _, _ = polar(CELL)
    a = np.clip(np.exp(-(r / 0.22) ** 2) + 0.45 * np.exp(-(r / 0.55) ** 2), 0, 1) * smoothstep(1.0, 0.8, r)
    rgb = fire_colour(np.clip(0.75 + 0.25 * a, 0, 1))
    return np.concatenate([rgb * a[..., None], a[..., None]], axis=-1)


def flame_cell() -> np.ndarray:
    _, _, dx, dy = polar(CELL)
    # A tongue: round head at the top, tapering downwards (sprites are camera-facing and rolled).
    n = noise(CELL, 4, 4)
    width = 0.5 * np.clip(1 - (dy + 0.45) / 1.35, 0.03, 1) ** 0.8 * (0.85 + 0.3 * n)
    body = smoothstep(width, 0, np.abs(dx)) * smoothstep(0.95, 0.45, dy) * smoothstep(-0.92, -0.5, dy)
    heat = np.clip(body * (0.7 + 0.5 * n), 0, 1)
    a = np.clip(body * 1.5, 0, 1)
    return np.concatenate([fire_colour(heat), a[..., None]], axis=-1)


def spark_cell() -> np.ndarray:
    r, _, _, _ = polar(CELL)
    a = np.clip(np.exp(-(r / 0.16) ** 2) * 1.4 + 0.25 * np.exp(-(r / 0.45) ** 2), 0, 1) * smoothstep(1.0, 0.8, r)
    return np.concatenate([fire_colour(np.clip(0.7 + 0.3 * a, 0, 1)), a[..., None]], axis=-1)


def build() -> Image.Image:
    atlas = np.zeros((CELL * 4, CELL * 8, 4), np.float32)

    def put(col: int, row: int, cell: np.ndarray):
        cell = cell.copy()
        cell[:2, :] = cell[-2:, :] = cell[:, :2] = cell[:, -2:] = 0
        atlas[row * CELL:(row + 1) * CELL, col * CELL:(col + 1) * CELL] = cell

    for i in range(8):
        put(i, 0, smoke_cell())
    for i, frame in enumerate(fireball_frames()):
        put(i % 8, 1 + i // 8, frame)
    put(0, 3, flash_cell())
    put(1, 3, flame_cell())
    put(2, 3, spark_cell())
    return Image.fromarray((np.clip(atlas, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


def main(argv: list[str]) -> int:
    image = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT, optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size} bytes)")
    if "--preview" in argv:
        where = Path(argv[argv.index("--preview") + 1])
        where.mkdir(parents=True, exist_ok=True)
        for name, bg in (("dark", (40, 46, 60)), ("sky", (120, 165, 220))):
            back = Image.new("RGBA", image.size, bg + (255,))
            straight = np.asarray(image, np.float32) / 255
            flash = straight[3 * CELL:, :CELL]
            flash[..., :3] /= np.maximum(flash[..., 3:], 1e-3)
            shown = Image.fromarray((np.clip(straight, 0, 1) * 255).astype(np.uint8), "RGBA")
            Image.alpha_composite(back, shown).convert("RGB").save(where / f"sam_fx_{name}.png")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
