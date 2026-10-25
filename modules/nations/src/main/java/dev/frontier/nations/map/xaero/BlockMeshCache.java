package dev.frontier.nations.map.xaero;

import dev.frontier.nations.map.geometry.BlockContour;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.geometry.ChunkShape;
import dev.frontier.nations.map.geometry.MapProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded row tiles: exact block cells at close zoom, rasterisation never runs on the render thread. */
final class BlockMeshCache {
    private static final int TILE_ROWS=256,MAX_PARTS=400_000,MAX_ENTRIES=2048,JOBS_PER_FRAME=8;
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,5,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),task->{Thread thread=new Thread(task,"Frontier border raster");thread.setDaemon(true);return thread;});
    static { WORKER.allowCoreThreadTimeOut(true); }
    private record Key(BorderPolygon polygon,BlockContour.Window window){}
    private record RowKey(BorderPolygon polygon,int step,int minZ,int maxZ){}
    private static final class Mesh {
        final Key key;
        final FutureTask<ChunkShape> task;
        ChunkShape shape;
        long seen;
        Mesh(Key key){this.key=key;task=new FutureTask<>(()->BlockContour.build(key.polygon,key.window));}
        void cancel(){task.cancel(true);WORKER.remove(task);}
    }
    private static final Map<Key,Mesh> MESHES=new LinkedHashMap<>(64,0.75f,true);
    private static final Map<RowKey,Mesh> FALLBACKS=new java.util.HashMap<>();
    private static int parts,jobs;
    private static long frame;
    private static boolean suspended;
    private BlockMeshCache(){}

    static void beginFrame(){
        suspended=false;frame++;jobs=0;
        var iterator=MESHES.entrySet().iterator();
        while(iterator.hasNext()){
            Mesh mesh=iterator.next().getValue();
            if(mesh.shape==null&&frame-mesh.seen>2){mesh.cancel();iterator.remove();continue;}
            if(mesh.shape==null&&mesh.task.isDone()){
                try{mesh.shape=mesh.task.get();parts+=size(mesh.shape);FALLBACKS.put(rowKey(mesh.key),mesh);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();mesh.cancel();iterator.remove();}
                catch(ExecutionException|CancellationException failed){mesh.cancel();iterator.remove();}
            }
        }
        trim();
    }
    static void clear(){for(Mesh mesh:MESHES.values())mesh.cancel();MESHES.clear();FALLBACKS.clear();parts=jobs=0;}
    static void pause(){
        if(suspended)return;suspended=true;
        var iterator=MESHES.values().iterator();
        while(iterator.hasNext()){Mesh mesh=iterator.next();if(mesh.shape==null){mesh.cancel();iterator.remove();}}
    }

    static List<ChunkShape> visible(BorderPolygon polygon,MapProjection view,int width,int height){
        if(width<=0||height<=64)return List.of();
        int step=1;
        while(step<(1<<26)&&(view.scale()*step<0.125||height/view.scale()/step>BlockContour.MAX_ROWS-512))step*=2;
        Point a=view.world(new Point(-8,30)),b=view.world(new Point(width+8,height-26));
        if(!polygon.bounds().intersects(a.x(),a.z(),b.x(),b.z()))return List.of();
        long tile=step*(long)TILE_ROWS,limit=(long)Math.ceil(BorderPolygon.WORLD_LIMIT/step)*step;
        int minX=bound(a.x(),step*64L,limit,false),maxX=bound(b.x(),step*64L,limit,true);
        int minZ=bound(a.z(),tile,limit,false),maxZ=bound(b.z(),tile,limit,true);
        List<ChunkShape> visible=new ArrayList<>();
        for(long z=minZ;z<maxZ;z+=tile){
            int end=(int)Math.min(maxZ,z+tile);
            if(!polygon.bounds().intersects(minX,z,maxX,end))continue;
            Key key=new Key(polygon,new BlockContour.Window(minX,(int)z,maxX,end,step));
            Mesh mesh=MESHES.get(key);
            if(mesh==null&&jobs<JOBS_PER_FRAME){
                mesh=new Mesh(key);
                try{WORKER.execute(mesh.task);MESHES.put(key,mesh);jobs++;}
                catch(RejectedExecutionException full){mesh.cancel();mesh=null;}
            }
            if(mesh!=null)mesh.seen=frame;
            if(mesh==null||mesh.shape==null){
                // Keep the previous overlapping row visible while panning into a new X window.
                Mesh old=FALLBACKS.get(rowKey(key));
                if(old!=null&&old.key.window.maxX()>a.x()&&old.key.window.minX()<b.x()){
                    MESHES.get(old.key);mesh=old;
                }
            }
            if(mesh!=null&&mesh.shape!=null&&size(mesh.shape)>0)visible.add(mesh.shape);
        }
        return visible;
    }
    private static void trim(){
        var iterator=MESHES.entrySet().iterator();
        while(iterator.hasNext()&&(parts>MAX_PARTS||MESHES.size()>MAX_ENTRIES)){
            Mesh old=iterator.next().getValue();if(old.shape!=null)parts-=size(old.shape);
            FALLBACKS.remove(rowKey(old.key),old);old.cancel();iterator.remove();
        }
    }
    private static RowKey rowKey(Key key){var w=key.window;return new RowKey(key.polygon,w.step(),w.minZ(),w.maxZ());}
    private static int size(ChunkShape shape){return shape.fills().size()+shape.edges().size();}
    private static int bound(double coordinate,long tile,long limit,boolean upper){
        double value=(upper?Math.ceil(coordinate/tile):Math.floor(coordinate/tile))*tile;
        return (int)Math.max(-limit,Math.min(limit,value));
    }
}
