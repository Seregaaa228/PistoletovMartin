package dev.frontier.flamingofix;

import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.model.FlightOrientation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Reuse DeepStrike's exact body narrow phase for entities as well as terrain. */
public final class NativeMissileSweep {
    private static final MethodHandle MOTION, CONTACT, ORIGIN, CUBES, PADDING, CENTER, X, Y, Z;
    private static final ThreadLocal<Cache> CACHE = new ThreadLocal<>();
    private static final ThreadLocal<Entity> TARGET = new ThreadLocal<>();
    private record Shape(SweptMissileObb.Box box,AABB bounds) {}
    private record Geometry(List<Shape> cubes,double padding,AABB bounds) {}
    private record Cache(GuidedMissileEntity missile, Vec3 from, Vec3 to, Object mesh,Geometry geometry) {}
    public static final double MAX_VEHICLE_REACH=48;

    static {
        try {
            Class<?> shape = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep");
            Class<?> mesh = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Mesh");
            Class<?> contact = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Contact");
            Class<?> cube = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Cuboid");
            MOTION = MethodHandles.privateLookupIn(shape, MethodHandles.lookup()).findStatic(shape, "motion",
                    MethodType.methodType(mesh, MissileKind.class, Vec3.class, Vec3.class,
                            float.class, float.class, double.class, Predicate.class))
                    .asType(MethodType.methodType(Object.class, MissileKind.class, Vec3.class, Vec3.class,
                            float.class, float.class, double.class, Predicate.class));
            CONTACT = MethodHandles.privateLookupIn(mesh, MethodHandles.lookup()).findVirtual(mesh, "contact",
                    MethodType.methodType(contact, AABB.class, Vec3.class, Vec3.class))
                    .asType(MethodType.methodType(Object.class, Object.class, AABB.class, Vec3.class, Vec3.class));
            ORIGIN = MethodHandles.privateLookupIn(contact, MethodHandles.lookup()).findVirtual(contact, "origin",
                    MethodType.methodType(Vec3.class)).asType(MethodType.methodType(Vec3.class, Object.class));
            var meshLookup=MethodHandles.privateLookupIn(mesh,MethodHandles.lookup());
            CUBES=meshLookup.findGetter(mesh,"cubes",List.class).asType(MethodType.methodType(List.class,Object.class));
            PADDING=meshLookup.findGetter(mesh,"padding",double.class).asType(MethodType.methodType(double.class,Object.class));
            var cubeLookup=MethodHandles.privateLookupIn(cube,MethodHandles.lookup());
            CENTER=cubeLookup.findVirtual(cube,"center",MethodType.methodType(Vec3.class)).asType(MethodType.methodType(Vec3.class,Object.class));
            X=cubeLookup.findVirtual(cube,"x",MethodType.methodType(Vec3.class)).asType(MethodType.methodType(Vec3.class,Object.class));
            Y=cubeLookup.findVirtual(cube,"y",MethodType.methodType(Vec3.class)).asType(MethodType.methodType(Vec3.class,Object.class));
            Z=cubeLookup.findVirtual(cube,"z",MethodType.methodType(Vec3.class)).asType(MethodType.methodType(Vec3.class,Object.class));
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public static Object prepare(MissileKind kind, Vec3 before, Vec3 movement, float yaw, float pitch,
            Predicate<String> visible) {
        try {
            return (Object) MOTION.invokeExact(kind, before, movement, yaw, pitch, 0.0, (Predicate<?>) visible);
        } catch (Throwable error) {
            throw new IllegalStateException("Pinned DeepStrike missile geometry contract failed", error);
        }
    }

    public static Vec3 contact(Object mesh, AABB obstacle, Vec3 from, Vec3 to) {
        try {
            Object contact = (Object) CONTACT.invokeExact(mesh, obstacle, from, to);
            return contact == null ? null : (Vec3) ORIGIN.invokeExact(contact);
        } catch (Throwable error) {
            throw new IllegalStateException("Pinned DeepStrike missile contact contract failed", error);
        }
    }

    public static Vec3 contact(GuidedMissileEntity missile, AABB expanded, AABB body, Vec3 from, Vec3 to) {
        Cache cached = CACHE.get();
        if (cached == null || cached.missile != missile || cached.from != from || cached.to != to) {
            Object mesh = prepare(missile.kind(), FlightOrientation.direction(missile.getYRot(), missile.getXRot()),
                    to.subtract(from), missile.getYRot(), missile.getXRot(), missile::visibleFlightBone);
            cached = new Cache(missile, from, to, mesh,null);
            CACHE.set(cached);
        }
        Entity targetEntity=TARGET.get();
        if(targetEntity instanceof OBBEntity target && !target.getOBBs().isEmpty()) {
            // Refresh native world transforms at the current server pose. No
            // generic AABB fallback may fill the space between real parts.
            if(targetEntity instanceof VehicleEntity vehicle)vehicle.updateOBB();
            if(cached.geometry==null) {
                cached=new Cache(missile,from,to,cached.mesh,geometry(cached.mesh));CACHE.set(cached);
            }
            double nearest=Double.POSITIVE_INFINITY;
            for(OBB part:target.getOBBs()) {
                double hit=fraction(cached.geometry,part,from,to);
                if(hit>=0 && hit<nearest)nearest=hit;
            }
            return Double.isFinite(nearest)?from.lerp(to,nearest):null;
        }
        // The native broad phase has already expanded the target by this body's
        // world AABB. Undo that expansion before testing the actual missile cubes.
        AABB target = targetBounds(expanded, body, from);
        return contact(cached.mesh, target, from, to);
    }

    public static AABB targetBounds(AABB expanded, AABB body, Vec3 from) {
        return new AABB(expanded.minX + body.maxX - from.x,
                expanded.minY + body.maxY - from.y, expanded.minZ + body.maxZ - from.z,
                expanded.maxX + body.minX - from.x, expanded.maxY + body.minY - from.y,
                expanded.maxZ + body.minZ - from.z);
    }

    private static Geometry geometry(Object mesh) {
        try {
            var cubes=new ArrayList<Shape>();AABB total=null;
            for(Object cube:(List<?>)CUBES.invokeExact(mesh)) {
                Vec3 center=(Vec3)CENTER.invokeExact(cube),x=(Vec3)X.invokeExact(cube),y=(Vec3)Y.invokeExact(cube),z=(Vec3)Z.invokeExact(cube);
                var box=new SweptMissileObb.Box(center.x,center.y,center.z,new double[]{x.x,x.y,x.z,y.x,y.y,y.z,z.x,z.y,z.z});
                double ex=Math.abs(x.x)+Math.abs(y.x)+Math.abs(z.x),ey=Math.abs(x.y)+Math.abs(y.y)+Math.abs(z.y),ez=Math.abs(x.z)+Math.abs(y.z)+Math.abs(z.z);
                var bounds=new AABB(center.x-ex,center.y-ey,center.z-ez,center.x+ex,center.y+ey,center.z+ez);
                cubes.add(new Shape(box,bounds));total=total==null?bounds:total.minmax(bounds);
            }
            return new Geometry(cubes,(double)PADDING.invokeExact(mesh),total);
        } catch(Throwable error) {throw new IllegalStateException("Pinned DeepStrike cuboid contract failed",error);}
    }

    public static Vec3 contact(Object mesh,OBB part,Vec3 from,Vec3 to) {
        double fraction=fraction(geometry(mesh),part,from,to);
        return fraction<0?null:from.lerp(to,fraction);
    }

    private static double fraction(Geometry geometry,OBB part,Vec3 from,Vec3 to) {
        Vec3 movement=to.subtract(from);AABB obstacle=OBB.getWorldAABB(part);
        if(geometry.bounds==null || !intersects(geometry.bounds,obstacle,from,movement,geometry.padding))return -1;
        var axes=part.getAxes();var extents=part.extents();var center=part.center;
        var target=new SweptMissileObb.Box(center.x-from.x,center.y-from.y,center.z-from.z,
                new double[]{axes[0].x*extents.x,axes[0].y*extents.x,axes[0].z*extents.x,
                        axes[1].x*extents.y,axes[1].y*extents.y,axes[1].z*extents.y,
                        axes[2].x*extents.z,axes[2].y*extents.z,axes[2].z*extents.z},
                new double[]{axes[0].x,axes[0].y,axes[0].z,axes[1].x,axes[1].y,axes[1].z,axes[2].x,axes[2].y,axes[2].z});
        double nearest=Double.POSITIVE_INFINITY;
        for(var cube:geometry.cubes) {
            if(!intersects(cube.bounds,obstacle,from,movement,geometry.padding))continue;
            double hit=SweptMissileObb.fraction(cube.box,target,movement.x,movement.y,movement.z,geometry.padding);
            if(hit>=0 && hit<nearest)nearest=hit;
        }
        return Double.isFinite(nearest)?nearest:-1;
    }

    private static boolean intersects(AABB cube,AABB target,Vec3 from,Vec3 movement,double padding) {
        return cube.minX+from.x+Math.min(0,movement.x)-padding<target.maxX
                && cube.maxX+from.x+Math.max(0,movement.x)+padding>target.minX
                && cube.minY+from.y+Math.min(0,movement.y)-padding<target.maxY
                && cube.maxY+from.y+Math.max(0,movement.y)+padding>target.minY
                && cube.minZ+from.z+Math.min(0,movement.z)-padding<target.maxZ
                && cube.maxZ+from.z+Math.max(0,movement.z)+padding>target.minZ;
    }

    public static void target(Entity entity) {TARGET.set(entity);}
    public static void clear() { CACHE.remove();TARGET.remove(); }
    private NativeMissileSweep() {}
}
