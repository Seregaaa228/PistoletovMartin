package dev.frontier.aviation.client;

import dev.frontier.aviation.weapons.CannonNetwork;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * Cannon tracers: each shot packet is flown here along the same straight, gravity-bent
 * path as the server shell, drawn as always-visible sparks (visible at any range the
 * chunks are) and stopped where it meets terrain.
 */
public final class CannonTracers {
    private static final int MAX_TICKS = 400, MAX_TRACERS = 512;
    /** Sparks every this many blocks along a tick's path. */
    private static final double SPACING = 2.5;
    private static final List<Flight> FLIGHTS = new ArrayList<>();

    private static final class Flight {
        Vec3 position, velocity;
        final float gravity;
        int ticks;
        Flight(CannonNetwork.Tracer t) {
            position = new Vec3(t.x(), t.y(), t.z());
            velocity = new Vec3(t.vx(), t.vy(), t.vz());
            gravity = t.gravity();
        }
    }

    private CannonTracers() {}

    public static void register() {
        CannonNetwork.installReceiver(tracer -> {
            if (FLIGHTS.size() < MAX_TRACERS) FLIGHTS.add(new Flight(tracer));
        });
        MinecraftForge.EVENT_BUS.addListener(CannonTracers::tick);
    }

    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().isPaused()) {
            if (level == null) FLIGHTS.clear();
            return;
        }
        for (Iterator<Flight> it = FLIGHTS.iterator(); it.hasNext(); ) {
            Flight f = it.next();
            Vec3 from = f.position, to = from.add(f.velocity);
            var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
                Minecraft.getInstance().player));
            Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
            double length = from.distanceTo(end);
            for (double d = 0; d < length; d += SPACING) {
                Vec3 p = from.add(end.subtract(from).scale(d / Math.max(length, 1.0E-6)));
                level.addAlwaysVisibleParticle(ParticleTypes.ELECTRIC_SPARK, true, p.x, p.y, p.z, 0, 0, 0);
            }
            level.addAlwaysVisibleParticle(ParticleTypes.SMALL_FLAME, true, end.x, end.y, end.z, 0, 0, 0);
            if (hit.getType() != HitResult.Type.MISS || ++f.ticks > MAX_TICKS) {
                it.remove();
                continue;
            }
            f.position = to;
            f.velocity = f.velocity.add(0, -f.gravity, 0);
        }
    }
}
