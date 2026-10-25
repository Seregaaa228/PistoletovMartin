package dev.frontier.nations.map.xaero;

import dev.frontier.nations.map.border.BorderCache;
import dev.frontier.nations.map.geometry.BlockContour;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.geometry.ChunkShape;
import dev.frontier.nations.map.geometry.MapProjection;
import java.util.List;

/** Late ACK regression and actual background tile completion, without OpenGL. */
public final class MapStateTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        BorderCache.clear();long first=BorderCache.beginSave("country");
        check(first>0&&BorderCache.beginSave("country")==0,"only one in-flight save");
        BorderCache.result(first,"country",true,"saved");
        check(BorderCache.takeResult(first).accepted(),"matching server ACK completes save");
        long second=BorderCache.beginSave("country");
        BorderCache.result(first,"country",false,"late refusal");
        check(BorderCache.hasPendingSave()&&BorderCache.takeResult(second)==null,"old ACK cannot complete a new draft of same country");
        BorderCache.result(second,"another",true,"wrong country");
        check(BorderCache.hasPendingSave(),"country mismatch ignored");
        BorderCache.result(second,"country",true,"saved");
        check(BorderCache.takeResult(second).accepted(),"new ACK accepted");
        long third=BorderCache.beginSave("country");BorderCache.clear();long fourth=BorderCache.beginSave("country");
        BorderCache.result(third,"country",true,"old connection");
        check(fourth>third&&BorderCache.hasPendingSave(),"connection reset cannot reuse a request number");
        BorderCache.abortSave(fourth);check(!BorderCache.hasPendingSave(),"failed send can release pending state");
        long timedOut=BorderCache.beginSave("country");Thread.sleep(10_050);
        check(!BorderCache.hasPendingSave(),"missing ACK releases the save after the real timeout");
        check(!BorderCache.takeResult(timedOut).accepted(),"timeout is reported as unconfirmed, not successful");
        long retry=BorderCache.beginSave("country");
        BorderCache.result(timedOut,"country",true,"late success");
        check(retry>timedOut&&BorderCache.hasPendingSave(),"late success after timeout cannot finish the retry");BorderCache.abortSave(retry);

        var polygon=new BorderPolygon(List.of(new Point(0,0),new Point(600,0),new Point(600,600),new Point(300,400),new Point(0,600)));
        var projection=new MapProjection(300,300,1,500,500);
        var expected=BlockContour.build(polygon,new BlockContour.Window(-256,-256,1024,1024,1));
        List<ChunkShape> tiles=await(polygon,projection,area(expected));
        check(tiles.size()>=3,"large contour is cached in multiple row tiles");
        for(var tile:tiles)for(var edge:tile.edges()) {
            check(edge.x0()==edge.x1()||edge.z0()==edge.z1(),"raster edges remain block-aligned");
            check(realEdge(expected,edge),"tile clipping creates no false border");
        }
        BlockMeshCache.pause();
        check(area(await(polygon,new MapProjection(340,300,1,500,500),area(expected)))==area(expected),"panning retains complete contour after asynchronous refresh");
        BlockMeshCache.clear();
        check(BlockMeshCache.visible(polygon,projection,1000,1000).isEmpty(),"clearing discards old ready meshes");
        BlockMeshCache.pause();BorderCache.clear();
        System.out.println("MapStateTest: "+checks+" checks passed");
    }
    private static List<ChunkShape> await(BorderPolygon polygon,MapProjection projection,double target) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<deadline){
            BlockMeshCache.beginFrame();var tiles=BlockMeshCache.visible(polygon,projection,1000,1000);
            if(area(tiles)==target)return tiles;
            Thread.sleep(5);
        }
        throw new AssertionError("Background contour did not complete");
    }
    private static double area(List<ChunkShape> shapes){return shapes.stream().mapToDouble(MapStateTest::area).sum();}
    private static double area(ChunkShape shape){return shape.fills().stream().mapToDouble(r->(r.x1()-r.x0())*(r.z1()-r.z0())).sum();}
    private static boolean realEdge(ChunkShape expected,ChunkShape.Edge edge){
        double x=(edge.x0()+edge.x1())/2,z=(edge.z0()+edge.z1())/2;
        for(var candidate:expected.edges())if(candidate.x0()==candidate.x1()&&edge.x0()==edge.x1()
                &&x==candidate.x0()&&z>=Math.min(candidate.z0(),candidate.z1())&&z<=Math.max(candidate.z0(),candidate.z1())
            ||candidate.z0()==candidate.z1()&&edge.z0()==edge.z1()&&z==candidate.z0()
                &&x>=Math.min(candidate.x0(),candidate.x1())&&x<=Math.max(candidate.x0(),candidate.x1()))return true;
        return false;
    }
    private static void check(boolean pass,String name){checks++;if(!pass)throw new AssertionError(name);}
}
