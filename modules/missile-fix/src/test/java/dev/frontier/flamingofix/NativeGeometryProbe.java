package dev.frontier.flamingofix;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.game.LauncherBlock;
import dev.sbw.deepstrike.model.MissileGeometry;
import dev.sbw.deepstrike.model.LauncherLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import dev.sbw.deepstrike.game.FlightCollision;
import java.util.Set;
import com.atsuishio.superbwarfare.tools.OBB;
import org.joml.Vector3d;
import org.joml.Quaterniond;
public final class NativeGeometryProbe {
 private static int checks;
 private static void check(boolean condition, String name) {
  if (!condition) throw new AssertionError(name);
  checks++;
 }
 public static void main(String[] args) throws ReflectiveOperationException {
  for (MissileKind kind : MissileKind.values()) System.out.println(kind+" bounds="+MissileGeometry.canonicalBounds(kind)+" clearance="+MissileGeometry.clearance(kind));
  for (MissileKind kind : MissileKind.values()) {
   Vec3 movement = new Vec3(0,0,80);
   Object mesh = NativeMissileSweep.prepare(kind,new Vec3(0,0,1),movement,0,0,bone -> true);
   for (int shot=0;shot<20;shot++) {
    double z=10+shot*3;
    check(NativeMissileSweep.contact(mesh,new AABB(-.2,-.2,z,.2,.2,z+.05),Vec3.ZERO,movement)!=null,
      kind+" tunnels through thin target "+shot);
   check(NativeMissileSweep.contact(mesh,new AABB(12,-.2,z,12.1,.2,z+.05),Vec3.ZERO,movement)==null,
      kind+" false near miss "+shot);
    AABB obstacle=new AABB(-.2,-.2,z,.2,.2,z+.05);
    var part=new OBB(new Vector3d(0,0,z+.025),new Vector3d(.2,.2,.025),new Quaterniond(),OBB.Part.EMPTY);
    Vec3 original=NativeMissileSweep.contact(mesh,obstacle,Vec3.ZERO,movement);
    Vec3 exact=NativeMissileSweep.contact(mesh,part,Vec3.ZERO,movement);
    check(original!=null && exact!=null && original.distanceToSqr(exact)<1e-10,kind+" OBB SAT must match native axis-aligned contact "+shot);
   }
  }
  Vec3 direction=new Vec3(-Math.sqrt(.5),0,Math.sqrt(.5));
  Vec3 movement=direction.scale(3);
  AABB body=MissileGeometry.worldBounds(MissileKind.FP5,Vec3.ZERO,direction,45,0);
  Object diagonal=NativeMissileSweep.prepare(MissileKind.FP5,direction,movement,45,0,bone -> true);
  int corners=0;
  for(double x=body.minX+.25;x<body.maxX-.25;x+=.25) for(double z=body.minZ+.25;z<body.maxZ-.25;z+=.25) {
   AABB target=new AABB(x,-.05,z,x+.1,.05,z+.1);
   if (target.intersects(body) && NativeMissileSweep.contact(diagonal,target,Vec3.ZERO,movement)==null) corners++;
  }
  check(corners>0,"native diagonal AABB false-positive reproduction missing");
  var expand=FlightCollision.class.getDeclaredMethod("expandForBody",AABB.class,AABB.class,Vec3.class);
  expand.setAccessible(true);
  AABB target=new AABB(10,2,30,10.8,3.8,30.8);
  Vec3 from=new Vec3(3,2,-1);
  AABB offsetBody=body.move(from);
  AABB expanded=(AABB)expand.invoke(null,target,offsetBody,from);
  AABB recovered=NativeMissileSweep.targetBounds(expanded,offsetBody,from);
  check(Math.abs(recovered.minX-target.minX)<1e-9 && Math.abs(recovered.maxZ-target.maxZ)<1e-9
    && Math.abs(recovered.minY-target.minY)<1e-9 && Math.abs(recovered.maxY-target.maxY)<1e-9,
    "native broad phase expansion must invert without growing the target");
  var miss=BlockHitResult.miss(new Vec3(10,0,0),Direction.WEST,new BlockPos(10,0,0));
  check(!new FlightCollision.SweepResult(miss,miss.getLocation(),1,Set.of(new net.minecraft.world.level.ChunkPos(0,0)),.5)
    .terrainKnownThroughContact(),"unknown terrain cannot count as air");
  check(!new FlightCollision.SweepResult(miss,miss.getLocation(),.75,Set.of(new net.minecraft.world.level.ChunkPos(0,0)),.5)
    .terrainKnownThroughContact(),"unknown foreground cannot be ignored for a farther target");
  check(new FlightCollision.SweepResult(miss,miss.getLocation(),.25,Set.of(new net.minecraft.world.level.ChunkPos(0,0)),.5)
    .terrainKnownThroughContact(),"known nearer collision must still resolve");
  for(float yaw: new float[]{0,90,180,270}) for(float elevation:new float[]{5,15,30,45,60}) {
   var mount=LauncherLayout.mount(LauncherBlock.Family.FP5,yaw,0,elevation,0);
   var pos=LauncherLayout.worldPosition(BlockPos.ZERO,mount);
   var dir=LauncherLayout.forward(LauncherBlock.Family.FP5,mount);
   check(Math.abs(dir.length()-1)<1e-6 && pos.y>1.9 && dir.y>0,"invalid native FP5 launch pose");
  }
  System.out.println("NATIVE_MISSILE_GEOMETRY_PASS checks="+checks+" rejectedDiagonalCorners="+corners);
 }
}
