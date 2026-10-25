package dev.frontier.flamingofix;

import com.sun.management.ThreadMXBean;
import dev.sbw.deepstrike.game.MissileKind;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Exact parity against the pinned native implementation, without a game world. */
public final class DeferredMissileMotionTest {
    private static final MethodHandle MOTION, CONTACT;
    private static final Field CUBES, PADDING;
    private static final Method CENTER, X, Y, Z, BOUNDS, FRACTION, ORIGIN, SURFACE;
    private static int checks;
    private static volatile Object sink;

    static {
        try {
            Class<?> shape = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep");
            Class<?> mesh = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Mesh");
            Class<?> cube = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Cuboid");
            Class<?> contact = Class.forName("dev.sbw.deepstrike.game.MissileShapeSweep$Contact");
            MOTION = MethodHandles.privateLookupIn(shape, MethodHandles.lookup()).findStatic(shape, "motion",
                    MethodType.methodType(mesh, MissileKind.class, Vec3.class, Vec3.class, float.class, float.class,
                            double.class, Predicate.class)).asType(MethodType.methodType(Object.class,
                                    MissileKind.class, Vec3.class, Vec3.class, float.class, float.class, double.class, Predicate.class));
            CONTACT = MethodHandles.privateLookupIn(mesh, MethodHandles.lookup()).findVirtual(mesh, "contact",
                    MethodType.methodType(contact, AABB.class, Vec3.class, Vec3.class))
                    .asType(MethodType.methodType(Object.class, Object.class, AABB.class, Vec3.class, Vec3.class));
            CUBES = field(mesh, "cubes"); PADDING = field(mesh, "padding");
            CENTER = method(cube, "center"); X = method(cube, "x"); Y = method(cube, "y");
            Z = method(cube, "z"); BOUNDS = method(cube, "bounds");
            FRACTION = method(contact, "fraction"); ORIGIN = method(contact, "origin"); SURFACE = method(contact, "surface");
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> owner, String name) throws ReflectiveOperationException {
        var method = owner.getDeclaredMethod(name); method.setAccessible(true); return method;
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name); checks++;
    }
    private static boolean exact(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    private static boolean vector(Vec3 a, Vec3 b) { return exact(a.x,b.x) && exact(a.y,b.y) && exact(a.z,b.z); }
    private static boolean box(AABB a, AABB b) {
        return exact(a.minX,b.minX) && exact(a.minY,b.minY) && exact(a.minZ,b.minZ)
                && exact(a.maxX,b.maxX) && exact(a.maxY,b.maxY) && exact(a.maxZ,b.maxZ);
    }
    private static void geometry(Object eager, Object lazy) throws ReflectiveOperationException {
        check(exact(PADDING.getDouble(eager),PADDING.getDouble(lazy)), "native angular padding changed");
        var first = (List<?>) CUBES.get(eager); var second = (List<?>) CUBES.get(lazy);
        check(first.size() == second.size(), "native visible cube count changed");
        for (int i=0;i<first.size();i++) {
            Object a=first.get(i), b=second.get(i);
            check(vector((Vec3)CENTER.invoke(a),(Vec3)CENTER.invoke(b)) && vector((Vec3)X.invoke(a),(Vec3)X.invoke(b))
                    && vector((Vec3)Y.invoke(a),(Vec3)Y.invoke(b)) && vector((Vec3)Z.invoke(a),(Vec3)Z.invoke(b))
                    && box((AABB)BOUNDS.invoke(a),(AABB)BOUNDS.invoke(b)), "native cuboid bits changed at "+i);
        }
    }
    private static boolean contacts(Object a,Object b) throws ReflectiveOperationException {
        if(a == null || b == null) return a == b;
        return exact((double)FRACTION.invoke(a),(double)FRACTION.invoke(b))
                && vector((Vec3)ORIGIN.invoke(a),(Vec3)ORIGIN.invoke(b))
                && vector((Vec3)SURFACE.invoke(a),(Vec3)SURFACE.invoke(b));
    }
    public static void main(String[] args) throws Throwable {
        for(MissileKind kind:MissileKind.values()) for(int i=0;i<20;i++) {
            float yaw=i*17.25f-170, pitch=i*7.5f-70;
            Vec3 before=i==0?Vec3.ZERO:new Vec3(Math.sin(i*.3),Math.cos(i*.5)*.3,Math.cos(i*.3));
            Vec3 movement=i==1?Vec3.ZERO:new Vec3(Math.sin(i*.7)*8,Math.cos(i*.4)*2,Math.cos(i*.7)*8);
            double padding=i%3*.15;
            var visible = new AtomicBoolean(i%2==0);
            Predicate<String> predicate=name->visible.get() || !(name.equals("booster") || name.startsWith("wing_") || name.startsWith("fin_"));
            Object eager=(Object)MOTION.invokeExact(kind,before,movement,yaw,pitch,padding,(Predicate<?>)predicate);
            var lazy=new DeferredMissileMotion(kind,before,movement,yaw,pitch,padding,predicate);
            check(!lazy.isMaterialized(),"empty terrain transformed cubes");
            visible.set(!visible.get());
            Object materialized=lazy.geometry();
            geometry(eager,materialized);
            check(lazy.isMaterialized() && materialized==lazy.geometry(),"segment geometry not reused");
            Vec3 from=new Vec3(i*.125,10,30), to=from.add(movement);
            for(int obstacle=0;obstacle<20;obstacle++) {
                Vec3 center=from.add(movement.scale(obstacle/19.0));
                double miss=obstacle%2==0?0:12;
                AABB target=new AABB(center.x-.05+miss,center.y-.3,center.z-.05,center.x+.05+miss,center.y+.3,center.z+.05);
                Object expected=(Object)CONTACT.invokeExact(eager,target,from,to);
                check(contacts(expected,lazy.contact(target,from,to)),"contact/near-miss differs "+kind+" pose="+i+" target="+obstacle);
            }
        }
        var lazy=new DeferredMissileMotion(MissileKind.FP5,new Vec3(0,0,1),new Vec3(0,0,8),0,0,0,name->true);
        var pool=Executors.newFixedThreadPool(8); var start=new CountDownLatch(1);
        var futures=new ArrayList<java.util.concurrent.Future<Object>>();
        for(int i=0;i<32;i++) futures.add(pool.submit(()->{start.await();return lazy.geometry();}));
        start.countDown();Object result=futures.get(0).get(20,TimeUnit.SECONDS);
        for(var future:futures)check(future.get(20,TimeUnit.SECONDS)==result,"parallel segment materialization duplicated");
        pool.shutdown(); check(pool.awaitTermination(20,TimeUnit.SECONDS),"geometry executor did not stop");
        var independent=new DeferredMissileMotion(MissileKind.JAGM,new Vec3(0,0,1),new Vec3(1,1,8),17,9,.25,name->true);
        check(!independent.isMaterialized(),"nested independent segment was eagerly materialized");
        check(independent.geometry()!=result && lazy.geometry()==result,"nested segment overwrote first geometry");
        allocation();
        System.out.println("DEFERRED_MISSILE_MOTION_PASS checks="+checks);
    }
    private static void allocation() throws Throwable {
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported(),"JDK17 thread allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId(); Vec3 before=new Vec3(0,0,1),movement=new Vec3(.1,0,8);
        Predicate<String> visible=name->true;
        for(int i=0;i<300;i++) {
            sink=(Object)MOTION.invokeExact(MissileKind.JAGM,before,movement,0f,0f,0.0,(Predicate<?>)visible);
            sink=new DeferredMissileMotion(MissileKind.JAGM,before,movement,0,0,0,visible);
        }
        long first=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<1000;i++) sink=(Object)MOTION.invokeExact(MissileKind.JAGM,before,movement,0f,0f,0.0,(Predicate<?>)visible);
        long nativeBytes=bean.getThreadAllocatedBytes(thread)-first;
        first=bean.getThreadAllocatedBytes(thread);
        for(int i=0;i<1000;i++) sink=new DeferredMissileMotion(MissileKind.JAGM,before,movement,0,0,0,visible);
        long deferredBytes=bean.getThreadAllocatedBytes(thread)-first;
        check(deferredBytes*20<nativeBytes,"empty-air deferred geometry did not reduce allocations 20x");
        System.out.println("DEFERRED_MISSILE_ALLOCATION nativeBytes="+nativeBytes+" deferredBytes="+deferredBytes+" segments=1000");
    }
}
