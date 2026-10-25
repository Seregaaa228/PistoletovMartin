package dev.frontier.nations.map;

import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BlockContour;
import dev.frontier.nations.map.geometry.MapProjection;
import dev.frontier.nations.map.geometry.ChunkShape;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public final class GeometryTest {
    private static int checks;
    public static void main(String[] args) {
        for (double guiScale : new double[]{1, 2, 3, 4}) {
            for (double zoom : new double[]{0.025, 0.5, 1, 7, 32}) {
                MapProjection view = MapProjection.physical(-1273.25, 468.875, zoom, guiScale, 1919, 1079);
                Point screen = view.screen(new Point(-1550.75, -210.125));
                Point back = view.world(screen);
                near(back.x(), -1550.75, "projection x");
                near(back.z(), -210.125, "projection z");
                near(view.screen(new Point(-1273.25, 468.875)).x(), 959 / guiScale, "physical center");
            }
        }
        BorderPolygon concave = polygon(0,0, 8,0, 8,3, 3,3, 3,8, 0,8);
        check(concave.contains(new Point(1,7)), "concave interior");
        check(!concave.contains(new Point(7,7)), "concave cutout stays empty");
        near(area(concave.triangles()), 39, "triangulated concave area");
        near(area(polygon(0,8, 3,8, 3,3, 8,3, 8,0, 0,0).triangles()), 39, "reverse winding");
        reject("crossing", () -> polygon(0,0, 8,8, 0,8, 8,0));
        reject("touching edge", () -> polygon(0,0, 8,0, 8,8, 4,0, 0,8));
        reject("collinear", () -> polygon(0,0, 1,1, 2,2));
        reject("duplicate", () -> polygon(0,0, 1,0, 1,1, 0,0));
        reject("NaN", () -> polygon(0,0, Double.NaN,1, 1,0));
        reject("world bounds", () -> polygon(0,0, 30_000_000,0, 0,1));
        reject("too few", () -> polygon(0,0, 1,1));
        List<Point> many = new ArrayList<>();
        for(int i=0;i<129;i++) many.add(new Point(Math.cos(i*2*Math.PI/129)*100,Math.sin(i*2*Math.PI/129)*100));
        reject("vertex budget", () -> new BorderPolygon(many));
        BorderPolygon negative=polygon(-100,-100, -90,-100, -90,-90, -100,-90);
        near(area(negative.triangles()),100,"negative world coordinates");
        Point label=concave.labelPoint();
        check(concave.contains(label), "label lies inside concave border");
        List<Point> source=new ArrayList<>(concave.points());
        BorderPolygon immutable=new BorderPolygon(source); source.clear();
        check(immutable.points().size()==6,"defensive list copy");
        reject("invalid projection", () -> MapProjection.physical(0,0,0,2,1280,720));
        var shape=ChunkShape.build(List.of(new ChunkShape.Chunk(0,0),new ChunkShape.Chunk(1,0)));
        check(shape.fills().size()==1,"adjacent chunks merge into one fill");
        near(shape.perimeter(),96,"no shared internal border");
        var ring=new ArrayList<ChunkShape.Chunk>();
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)if(x!=1||z!=1)ring.add(new ChunkShape.Chunk(x,z));
        near(ChunkShape.build(ring).perimeter(),256,"hole perimeter retained");
        near(ChunkShape.build(List.of(new ChunkShape.Chunk(-2,-2),new ChunkShape.Chunk(2,2))).perimeter(),128,"disjoint islands");
        blockRasterTests();
        System.out.println("GeometryTest: "+checks+" checks passed");
    }
    private record Cell(int x,int z){}
    private static void blockRasterTests(){
        var window=new BlockContour.Window(-16,-16,16,16,1);
        for(BorderPolygon polygon:List.of(
            polygon(-8.2,-7.1, 10.3,-5.2, 6.4,9.3),
            polygon(-9.2,-8.1, 9.3,-8.1, 9.3,-2.1, -2.2,-2.1, -2.2,9.1, -9.2,9.1),
            polygon(6.4,9.3, 10.3,-5.2, -8.2,-7.1),
            polygon(-12,-11, 11,-11, 11,10, 3,2, -12,10))){
            ChunkShape mesh=BlockContour.build(polygon,window);Set<Cell> cells=cells(mesh);
            for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
                Point centre=new Point(x+0.5,z+0.5);
                boolean boundary=false;
                for(int i=0;i<polygon.points().size();i++){
                    Point a=polygon.points().get(i),b=polygon.points().get((i+1)%polygon.points().size());
                    double cross=(b.x()-a.x())*(centre.z()-a.z())-(b.z()-a.z())*(centre.x()-a.x());
                    if(Math.abs(cross)<1e-8&&centre.x()>=Math.min(a.x(),b.x())&&centre.x()<=Math.max(a.x(),b.x())&&centre.z()>=Math.min(a.z(),b.z())&&centre.z()<=Math.max(a.z(),b.z()))boundary=true;
                }
                if(!boundary)check(cells.contains(new Cell(x,z))==polygon.contains(centre),"one block centre inclusion "+centre);
            }
            Set<String> expected=new HashSet<>();
            for(Cell cell:cells){
                int x=cell.x,z=cell.z;
                if(!cells.contains(new Cell(x-1,z)))expected.add(edge(x,z,x,z+1));
                if(!cells.contains(new Cell(x+1,z)))expected.add(edge(x+1,z,x+1,z+1));
                if(!cells.contains(new Cell(x,z-1)))expected.add(edge(x,z,x+1,z));
                if(!cells.contains(new Cell(x,z+1)))expected.add(edge(x,z+1,x+1,z+1));
            }
            Set<String> actual=new HashSet<>();
            for(var e:mesh.edges()){
                check(e.x0()==Math.rint(e.x0())&&e.x1()==Math.rint(e.x1())&&e.z0()==Math.rint(e.z0())&&e.z1()==Math.rint(e.z1()),"block edge on integer grid");
                check(e.x0()==e.x1()||e.z0()==e.z1(),"no diagonal block edges");
                for(int z=(int)e.z0();z<e.z1();z++)check(actual.add(edge((int)e.x0(),z,(int)e.x0(),z+1)),"no duplicate vertical edge");
                for(int x=(int)e.x0();x<e.x1();x++)check(actual.add(edge(x,(int)e.z0(),x+1,(int)e.z0())),"no duplicate horizontal edge");
            }
            check(actual.equals(expected),"outline exactly matches filled blocks, including concave corners");
        }
        var triangle=polygon(-5,-5, 6,-5, -5,6);
        var reverse=polygon(-5,6, 6,-5, -5,-5);
        Set<Cell> triangleCells=cells(BlockContour.build(triangle,window));
        check(triangleCells.equals(cells(BlockContour.build(reverse,window))),"centre on diagonal tie independent of winding");
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++)check(triangleCells.contains(new Cell(x,z))==(x>=-5&&z>=-5&&x+z<0),"known staircase triangle occupancy");
        var huge=polygon(-29_999_980,-29_999_980, 29_999_980,-29_999_980, 29_999_980,29_999_980, -29_999_980,29_999_980);
        var clipped=BlockContour.build(huge,new BlockContour.Window(-64,-2048,64,2048,1));
        check(clipped.fills().size()==1&&clipped.edges().isEmpty(),"worldwide polygon bounded by viewport without fake clipping border");
        var left=BlockContour.build(polygon(-100,-100, 0,-100, 0,100, -100,100),new BlockContour.Window(-16,-16,16,16,1));
        check(left.edges().size()==1&&left.edges().get(0).x0()==0,"clipping retains only real exterior edge");
        check(BlockContour.build(triangle,new BlockContour.Window(100,100,200,200,1)).fills().isEmpty(),"offscreen contour skipped");
        var coarse=BlockContour.build(huge,new BlockContour.Window(-4096,-4096,4096,4096,16));
        check(coarse.edges().isEmpty()&&coarse.fills().size()==1,"bounded subpixel generalisation");
        reject("unbounded raster window",()->new BlockContour.Window(0,0,100,10_000_000,1));
    }
    private static Set<Cell> cells(ChunkShape mesh){
        Set<Cell> cells=new HashSet<>();
        for(var r:mesh.fills())for(int x=(int)r.x0();x<r.x1();x++)for(int z=(int)r.z0();z<r.z1();z++)check(cells.add(new Cell(x,z)),"nonoverlapping block fill");
        return cells;
    }
    private static String edge(int x0,int z0,int x1,int z1){return x0+","+z0+":"+x1+","+z1;}
    private static BorderPolygon polygon(double... v) {
        List<Point> points=new ArrayList<>();
        for(int i=0;i<v.length;i+=2) points.add(new Point(v[i],v[i+1]));
        return new BorderPolygon(points);
    }
    private static double area(List<BorderPolygon.Triangle> triangles) {
        double sum=0;
        for(var t:triangles) sum+=Math.abs((t.b().x()-t.a().x())*(t.c().z()-t.a().z())-(t.b().z()-t.a().z())*(t.c().x()-t.a().x()))/2;
        return sum;
    }
    private static void reject(String label,Runnable action) {
        try {action.run();throw new AssertionError(label+" accepted");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void near(double actual,double expected,String label){check(Math.abs(actual-expected)<1e-6,label+": "+actual+" != "+expected);}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
