package dev.frontier.arcade.airdefense.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.frontier.arcade.ArcadeVehicles;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The air defence's own light particle system: soft textured sprites (smoke puffs, fireball
 * frames, flame, spark and flash from one atlas painted by art/sam_fx_texture.py) that live,
 * drift, grow and fade. Vanilla particles cannot do this well at missile speed and range: they
 * are spawned per tick at one point (a dotted trail at 10 blocks a tick), vanish beyond 32 blocks
 * and their 16 px pixel-art turns into squares at cloud size. Here the trail is laid out along
 * the whole path flown each tick, sprites far away are fewer and larger, smoke takes the world's
 * light and fire is full bright.
 *
 * <p>Drawing: one blended pass of every sprite sorted far to near, without depth writes (so a
 * faint puff never hides what is behind it), then the flashes added on top as glow.</p>
 *
 * <p>Everything is client-side from entity spawns and burst messages; no packets. A hard cap on
 * live sprites keeps a big salvo within budget; the oldest sprites go first.</p>
 */
@Mod.EventBusSubscriber(modid = ArcadeVehicles.ID, value = Dist.CLIENT)
public final class SamFx {
    private static final int MAX_SPRITES = 5000;
    private static final RandomSource RANDOM = RandomSource.create();
    /** 1024 x 512, cells of 128: row 0 smoke 0..7, rows 1..2 fireball frames 0..15, row 3 flash, flame, spark. */
    private static final ResourceLocation ATLAS = new ResourceLocation(ArcadeVehicles.ID, "textures/effect/sam_fx.png");
    private static final int SMOKE_CELLS = 8, FIREBALL_CELL = 8, FIREBALL_FRAMES = 16, FLASH_CELL = 24, FLAME_CELL = 25, SPARK_CELL = 26;

    /** One sprite. Smoke takes the world's light; everything else is full bright. */
    private static final class Sprite {
        double x, y, z, px, py, pz, vx, vy, vz;
        float size0, size1, roll, spin, alpha0, r, g, b, drag, lift, depth, fadeIn = 3;
        int age, life, light, cell;
        Kind kind;
    }

    private enum Kind { SMOKE, FLAME, SPARK, FLASH, FIREBALL }

    private static final ArrayDeque<Sprite> SPRITES = new ArrayDeque<>();
    private static Sprite[] order = new Sprite[256];
    private static Object lastLevel;
    private static final java.util.Comparator<Sprite> FAR_FIRST = (a, b) -> Float.compare(b.depth, a.depth);
    private static final Vector3f[] CORNERS = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
    private static final float[][] CORNER = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}}, UV = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};

    private SamFx() { }

    // ------------------------------------------------------------------ emitters

    /**
     * The motor's smoke between last tick's nozzle and this tick's: puffs laid along the segment,
     * closer together near the viewer. Burning motors leave thick white smoke; a coasting
     * missile only a thin wisp.
     */
    static void trail(Vec3 from, Vec3 to, boolean burning, float scale, double viewDistance) {
        double length = from.distanceTo(to);
        if (!(length > 1e-3) || length > 64) return;
        float far = (float) Math.max(1, viewDistance / 90);
        // Puffs at most half a newborn puff apart, so the trail is a continuous ribbon; farther
        // away they are larger and so fewer. Thinned when the budget runs low.
        double spacing = 0.4 * scale * far * (burning ? 1 : 1.6) / budget();
        int count = (int) Math.min(48, Math.ceil(length / spacing));
        int light = light(to);
        for (int i = 0; i < count; i++) {
            Vec3 p = from.add(to.subtract(from).scale((i + RANDOM.nextFloat()) / count));
            Sprite s = smoke(p, light);
            s.vx = RANDOM.nextGaussian() * 0.015; s.vy = 0.004 + RANDOM.nextFloat() * 0.01; s.vz = RANDOM.nextGaussian() * 0.015;
            s.size0 = (0.35f + RANDOM.nextFloat() * 0.25f) * scale * far;
            s.size1 = (2.2f + RANDOM.nextFloat() * 1.6f) * scale * far;
            s.life = burning ? 80 + RANDOM.nextInt(40) : 30 + RANDOM.nextInt(15);
            float shade = 0.9f + RANDOM.nextFloat() * 0.08f;
            s.r = s.g = shade; s.b = shade * 0.98f;
            s.alpha0 = burning ? 0.55f : 0.2f;
            s.drag = 0.97f;
            s.lift = 0.0006f;
        }
    }

    /**
     * Share of the full emission to spend now: lower with the player's particle setting, and
     * thinning out as live sprites near the cap (so old clouds are not evicted while dense).
     */
    private static float budget() {
        float setting = switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> 1f;
            case DECREASED -> 0.5f;
            case MINIMAL -> 0.25f;
        };
        float fill = SPRITES.size() / (float) MAX_SPRITES;
        return setting * Mth.clamp(1.6f - fill * 1.5f, 0.15f, 1f);
    }

    /** The ground under a point (the top of the terrain), for clouds that roll over it. */
    private static double groundBelow(Vec3 at) {
        var level = Minecraft.getInstance().level;
        if (level == null) return at.y;
        int x = Mth.floor(at.x), z = Mth.floor(at.z);
        if (!level.hasChunk(x >> 4, z >> 4)) return at.y;
        return Math.min(at.y, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + 0.3);
    }

    /** Launch: fire at the muzzle, a cloud rolling out over the ground, a column for a vertical launch. */
    static void launch(Vec3 muzzle, Vec3 dir, boolean vertical, double viewDistance, float scale) {
        float detail = (viewDistance < 96 ? 1f : viewDistance < 224 ? 0.5f : 0.25f) * budget();
        int light = light(muzzle);
        Sprite flash = sprite(Kind.FLASH, muzzle);
        flash.size0 = 5 * scale; flash.size1 = 2 * scale; flash.life = 5; flash.alpha0 = 1; flash.r = 1; flash.g = 0.85f; flash.b = 0.6f;
        for (int i = 0; i < 10 * detail + 2; i++) {
            Sprite f = sprite(Kind.FLAME, muzzle.add(gauss(0.5)));
            Vec3 v = dir.scale(-0.35 - RANDOM.nextFloat() * 0.3).add(gauss(0.12));
            f.vx = v.x; f.vy = v.y; f.vz = v.z; f.drag = 0.85f;
            f.size0 = (1.2f + RANDOM.nextFloat()) * scale; f.size1 = 0.3f; f.life = 6 + RANDOM.nextInt(6);
            f.alpha0 = 0.95f; f.r = 1; f.g = 0.7f; f.b = 0.35f;
        }
        // Blast cloud: from below the muzzle out along the ground, dusty.
        Vec3 behind = vertical ? muzzle : muzzle.subtract(dir.scale(2));
        Vec3 ground = new Vec3(behind.x, groundBelow(behind) + 0.8, behind.z);
        for (int i = 0; i < 46 * detail + 6; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2, speed = 0.12 + RANDOM.nextDouble() * 0.35;
            // Born a little above the ground and rising as it spreads, so the ground does not cut it flat.
            Sprite s = smoke(ground.add(RANDOM.nextGaussian(), 0.6 + RANDOM.nextFloat() * 1.6, RANDOM.nextGaussian()), light);
            s.vx = Math.cos(a) * speed; s.vy = 0.03 + RANDOM.nextFloat() * 0.05; s.vz = Math.sin(a) * speed;
            s.drag = 0.93f; s.lift = 0.002f;
            s.size0 = (1.0f + RANDOM.nextFloat()) * scale; s.size1 = (3.5f + RANDOM.nextFloat() * 3) * scale;
            s.life = 110 + RANDOM.nextInt(60);
            float dust = 0.78f + RANDOM.nextFloat() * 0.1f;
            s.r = dust; s.g = dust * 0.95f; s.b = dust * 0.86f; s.alpha0 = 0.55f;
        }
        if (vertical) for (int i = 0; i < 24 * detail + 4; i++) {
            Sprite s = smoke(muzzle.add(0, -RANDOM.nextFloat() * (muzzle.y - ground.y), 0).add(gauss(0.6)), light);
            s.vx = RANDOM.nextGaussian() * 0.03; s.vy = 0.06 + RANDOM.nextFloat() * 0.12; s.vz = RANDOM.nextGaussian() * 0.03;
            s.drag = 0.95f; s.size0 = 1.4f * scale; s.size1 = (4 + RANDOM.nextFloat() * 2) * scale; s.life = 90 + RANDOM.nextInt(40);
            s.r = s.g = s.b = 0.93f; s.alpha0 = 0.6f;
        }
    }

    /** Burst: flash, an animated fireball, falling sparks and a dark cloud that grows and hangs. */
    static void burst(Vec3 at, float scale, double viewDistance) {
        float detail = (viewDistance < 128 ? 1f : viewDistance < 320 ? 0.55f : 0.3f) * budget();
        float far = (float) Math.max(1, viewDistance / 140);
        int light = light(at);
        Sprite flash = sprite(Kind.FLASH, at);
        flash.size0 = 14 * scale * far; flash.size1 = 6 * scale; flash.life = 4; flash.alpha0 = 1; flash.r = 1; flash.g = 0.92f; flash.b = 0.75f;
        for (int i = 0; i < 1 + 5 * detail; i++) {
            Sprite f = sprite(Kind.FIREBALL, at.add(gauss(i == 0 ? 0 : 1.6 * scale)));
            f.size0 = (i == 0 ? 7f : 3.5f + RANDOM.nextFloat() * 2.5f) * scale * far; f.size1 = f.size0 * 1.5f;
            f.life = 20 + RANDOM.nextInt(8); f.alpha0 = 1; f.r = 1; f.g = 0.9f; f.b = 0.8f;
            f.vx = RANDOM.nextGaussian() * 0.05; f.vy = 0.03; f.vz = RANDOM.nextGaussian() * 0.05;
        }
        for (int i = 0; i < 36 * detail + 4; i++) {
            Vec3 v = gauss(1).normalize().scale(0.15 + RANDOM.nextDouble() * 0.25);
            Sprite s = smoke(at.add(v.scale(4)), light);
            s.vx = v.x; s.vy = v.y + 0.02; s.vz = v.z; s.drag = 0.92f; s.lift = 0.0012f;
            s.size0 = (1.5f + RANDOM.nextFloat()) * scale * far; s.size1 = (6 + RANDOM.nextFloat() * 4) * scale * far;
            s.life = 160 + RANDOM.nextInt(80);
            float soot = 0.24f + RANDOM.nextFloat() * 0.12f;
            s.r = soot; s.g = soot; s.b = soot * 0.95f; s.alpha0 = 0.8f;
            s.fadeIn = 10 + RANDOM.nextInt(6);
        }
        for (int i = 0; i < 28 * detail; i++) {
            Vec3 v = gauss(1).normalize().scale(0.5 + RANDOM.nextDouble() * 0.7);
            Sprite f = sprite(Kind.SPARK, at);
            f.vx = v.x; f.vy = v.y + 0.25; f.vz = v.z; f.drag = 0.96f; f.lift = -0.035f;
            f.size0 = 0.45f * far; f.size1 = 0.15f * far; f.life = 22 + RANDOM.nextInt(18); f.alpha0 = 1; f.r = 1; f.g = 0.62f; f.b = 0.25f;
        }
    }

    private static Sprite smoke(Vec3 at, int light) {
        Sprite s = sprite(Kind.SMOKE, at);
        s.cell = RANDOM.nextInt(SMOKE_CELLS);
        s.light = light;
        return s;
    }

    private static Sprite sprite(Kind kind, Vec3 at) {
        if (SPRITES.size() >= MAX_SPRITES) SPRITES.pollFirst();
        Sprite s = new Sprite();
        s.kind = kind;
        s.cell = switch (kind) { case FLAME -> FLAME_CELL; case SPARK -> SPARK_CELL; case FLASH -> FLASH_CELL; default -> 0; };
        s.x = s.px = at.x; s.y = s.py = at.y; s.z = s.pz = at.z;
        s.roll = RANDOM.nextFloat() * Mth.TWO_PI;
        s.spin = (RANDOM.nextFloat() - 0.5f) * 0.04f;
        s.drag = 0.96f;
        s.light = LightTexture.FULL_BRIGHT;
        SPRITES.add(s);
        return s;
    }

    private static Vec3 gauss(double scale) {
        return new Vec3(RANDOM.nextGaussian() * scale, RANDOM.nextGaussian() * scale, RANDOM.nextGaussian() * scale);
    }

    /** World light where the smoke is born: smoke is dark at night, fire is not. */
    private static int light(Vec3 at) {
        var level = Minecraft.getInstance().level;
        if (level == null) return LightTexture.FULL_BRIGHT;
        BlockPos pos = BlockPos.containing(at);
        // Born inside terrain (a burst on a hill): take the light above, not the block's darkness.
        for (int up = 0; up < 3 && level.getBlockState(pos).isSolidRender(level, pos); up++) pos = pos.above();
        return LevelRenderer.getLightColor(level, pos);
    }

    // ------------------------------------------------------------------ update and draw

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var level = Minecraft.getInstance().level;
        // A new level (respawn, another dimension) keeps none of the old one's smoke.
        if (level != lastLevel) { SPRITES.clear(); lastLevel = level; }
        if (level == null) return;
        if (Minecraft.getInstance().isPaused()) return;
        SPRITES.removeIf(s -> ++s.age >= s.life);
        for (Sprite s : SPRITES) {
            s.px = s.x; s.py = s.y; s.pz = s.z;
            s.x += s.vx; s.y += s.vy; s.z += s.vz;
            s.vx *= s.drag; s.vy = s.vy * s.drag + s.lift; s.vz *= s.drag;
            s.roll += s.spin;
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || SPRITES.isEmpty()) return;
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        Vector3f look = camera.getLookVector();
        float partial = event.getPartialTick();
        Matrix4f m = event.getPoseStack().last().pose();
        normal = event.getPoseStack().last().normal();
        // Past the fog the shader paints sprites fog-coloured but keeps their alpha: fade them out.
        fogEnd = RenderSystem.getShaderFogEnd();
        Quaternionf facing = camera.rotation();
        // Visible sprites, far to near: blended without depth writes, the nearest must come last.
        if (order.length < SPRITES.size()) order = new Sprite[SPRITES.size() + 256];
        int count = 0, flashes = 0;
        for (Sprite s : SPRITES) {
            double dx = Mth.lerp(partial, s.px, s.x) - eye.x, dy = Mth.lerp(partial, s.py, s.y) - eye.y, dz = Mth.lerp(partial, s.pz, s.z) - eye.z;
            float ahead = (float) (dx * look.x() + dy * look.y() + dz * look.z());
            if (ahead < -Math.max(s.size0, s.size1) * 1.5f) continue;
            s.depth = (float) (dx * dx + dy * dy + dz * dz);
            if (s.kind == Kind.FLASH) flashes++;
            order[count++] = s;
        }
        if (count == 0) return;
        Arrays.sort(order, 0, count, FAR_FIRST);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(FxTypes.BLEND);
        for (int i = 0; i < count; i++) if (order[i].kind != Kind.FLASH) draw(out, m, facing, eye, order[i], partial, false);
        buffers.endBatch(FxTypes.BLEND);
        if (flashes > 0) {
            out = buffers.getBuffer(FxTypes.GLOW);
            for (int i = 0; i < count; i++) if (order[i].kind == Kind.FLASH) draw(out, m, facing, eye, order[i], partial, true);
            buffers.endBatch(FxTypes.GLOW);
        }
        Arrays.fill(order, 0, count, null);
    }

    private static Matrix3f normal = new Matrix3f();
    private static float fogEnd = Float.MAX_VALUE;

    private static void draw(VertexConsumer out, Matrix4f m, Quaternionf facing, Vec3 eye, Sprite s, float partial, boolean glow) {
        float age = s.age + partial, life = Math.min(1, age / s.life);
        boolean smoke = s.kind == Kind.SMOKE;
        // Smoke swells quickly then slowly, fades in over the first moments and thins out to the end;
        // fire shrinks or grows linearly and burns out.
        float grow = smoke ? 1 - (1 - life) * (1 - life) : life;
        float size = s.size0 + (s.size1 - s.size0) * grow;
        float alpha = s.alpha0 * (smoke ? Math.min(1, age / s.fadeIn) * (1 - life) * (1 - life * 0.3f)
            : s.kind == Kind.FIREBALL ? 1 - life * life : 1 - life);
        if (alpha <= 0.004f || size <= 0) return;
        int cell = s.kind == Kind.FIREBALL ? FIREBALL_CELL + Math.min(FIREBALL_FRAMES - 1, (int) (life * FIREBALL_FRAMES)) : s.cell;
        float u0 = (cell % 8) / 8f, v0 = (cell / 8) / 4f;
        float x = (float) (Mth.lerp(partial, s.px, s.x) - eye.x), y = (float) (Mth.lerp(partial, s.py, s.y) - eye.y),
            z = (float) (Mth.lerp(partial, s.pz, s.z) - eye.z);
        float distance = (float) Math.sqrt(s.depth);
        if (distance > fogEnd * 0.75f) alpha *= Mth.clamp((fogEnd - distance) / (fogEnd * 0.25f), 0, 1);
        if (alpha <= 0.004f) return;
        float cos = Mth.cos(s.roll), sin = Mth.sin(s.roll);
        // The glow pass adds colour (one, one): its fade must darken the colour, not the alpha.
        float k = glow ? alpha : 1;
        int a = (int) (alpha * 255), r = (int) (s.r * k * 255), g = (int) (s.g * k * 255), b = (int) (s.b * k * 255);
        int light = smoke ? s.light : LightTexture.FULL_BRIGHT;
        for (int i = 0; i < 4; i++) {
            float cx = CORNER[i][0] * cos - CORNER[i][1] * sin, cy = CORNER[i][0] * sin + CORNER[i][1] * cos;
            Vector3f c = CORNERS[i].set(cx * size, cy * size, 0);
            facing.transform(c);
            out.vertex(m, x + c.x, y + c.y, z + c.z).color(r, g, b, a).uv(u0 + UV[i][0] / 8f, v0 + UV[i][1] / 4f)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, 0, 1, 0).endVertex();
        }
    }

    /**
     * The two passes: the entity translucent shader (it discards only by texture alpha and takes
     * the light map) blended without depth writes, and the eyes shader added for glow. Built from
     * the protected render-state pieces, hence the subclass.
     */
    private abstract static class FxTypes extends RenderType {
        static final RenderType BLEND = create("frontier_sam_fx_blend", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            1 << 16, false, false, CompositeState.builder()
                .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                .setTextureState(new TextureStateShard(ATLAS, true, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL).setLightmapState(LIGHTMAP).setOverlayState(OVERLAY)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));
        static final RenderType GLOW = create("frontier_sam_fx_glow", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            1 << 10, false, false, CompositeState.builder()
                .setShaderState(RENDERTYPE_EYES_SHADER)
                .setTextureState(new TextureStateShard(ATLAS, true, false))
                .setTransparencyState(ADDITIVE_TRANSPARENCY)
                .setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));

        private FxTypes() { super(null, null, null, 0, false, false, null, null); }
    }

    static void clear() { SPRITES.clear(); }
}
