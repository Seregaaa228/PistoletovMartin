package dev.frontier.nations.map.geometry;

import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.geometry.ChunkShape.Edge;
import dev.frontier.nations.map.geometry.ChunkShape.Rect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cosmetic cell union sampled at block centres. No native claims or saved points are changed. */
public final class BlockContour {
    private static final ChunkShape EMPTY=new ChunkShape(List.of(),List.of());
    public static final int MAX_ROWS=8192;
    /** All bounds are world block edges, aligned to step. Clipping never creates a border. */
    public record Window(int minX,int minZ,int maxX,int maxZ,int step) {
        public Window {
            if(step<1||minX>maxX||minZ>maxZ||minX%step!=0||minZ%step!=0||maxX%step!=0||maxZ%step!=0
                ||((long)maxZ-minZ)/step>MAX_ROWS)throw new IllegalArgumentException("Invalid raster window");
        }
    }
    private record Span(int start,int end){}
    private BlockContour(){}

    public static ChunkShape build(BorderPolygon polygon,Window window){
        int step=window.step;
        var bounds=polygon.bounds();double minX=bounds.minX(),minZ=bounds.minZ(),maxX=bounds.maxX(),maxZ=bounds.maxZ();
        if(maxX<=window.minX||minX>=window.maxX||maxZ<=window.minZ||minZ>=window.maxZ)return EMPTY;
        int first=Math.max(window.minZ,(int)(Math.floor(minZ/step)*step));
        int last=Math.min(window.maxZ,(int)(Math.ceil(maxZ/step)*step));
        List<Rect> fills=new ArrayList<>();List<Edge> edges=new ArrayList<>();
        Map<Span,Integer> activeFills=new HashMap<>();Map<Integer,Integer> activeEdges=new HashMap<>();
        List<Span> previous=row(polygon,first-step,step);
        for(int z=first;z<=last;z+=step){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            List<Span> current=row(polygon,z,step);
            // The extra row on either side preserves real edges at the viewport boundary.
            difference(previous,current,z,window,edges);difference(current,previous,z,window,edges);
            if(z==last)break;
            Map<Span,Integer> nextFills=new HashMap<>();Map<Integer,Integer> nextEdges=new HashMap<>();
            for(Span span:current){
                int left=Math.max(span.start,window.minX),right=Math.min(span.end,window.maxX);
                if(left<right){
                    Span clipped=new Span(left,right);Integer index=activeFills.get(clipped);
                    if(index==null){index=fills.size();fills.add(new Rect(left,z,right,z+step));}
                    else{Rect old=fills.get(index);fills.set(index,new Rect(old.x0(),old.z0(),right,z+step));}
                    nextFills.put(clipped,index);
                }
                vertical(span.start,z,step,window,activeEdges,nextEdges,edges);
                vertical(span.end,z,step,window,activeEdges,nextEdges,edges);
            }
            activeFills=nextFills;activeEdges=nextEdges;previous=current;
        }
        return new ChunkShape(List.copyOf(fills),List.copyOf(edges));
    }

    private static List<Span> row(BorderPolygon polygon,int z,int step){
        double sample=z+step/2.0;double[] crossings=new double[polygon.points().size()];int count=0;
        List<Point> points=polygon.points();
        for(int i=0,j=points.size()-1;i<points.size();j=i++){
            Point a=points.get(j),b=points.get(i);
            if((a.z()>sample)!=(b.z()>sample))crossings[count++]=a.x()+(sample-a.z())*(b.x()-a.x())/(b.z()-a.z());
        }
        Arrays.sort(crossings,0,count);List<Span> spans=new ArrayList<>(count/2);
        // Half-open crossings consistently assign a centre exactly on an edge to one side.
        for(int i=0;i+1<count;i+=2){
            int left=(int)(Math.ceil(crossings[i]/step-0.5)*step),right=(int)(Math.ceil(crossings[i+1]/step-0.5)*step);
            if(left>=right)continue;
            if(!spans.isEmpty()&&spans.get(spans.size()-1).end>=left){Span old=spans.remove(spans.size()-1);left=old.start;}
            spans.add(new Span(left,right));
        }
        return spans;
    }
    private static void difference(List<Span> from,List<Span> other,int z,Window w,List<Edge> edges){
        int next=0;
        for(Span span:from){
            int cursor=span.start;
            while(next<other.size()&&other.get(next).end<=cursor)next++;
            for(int j=next;j<other.size()&&other.get(j).start<span.end;j++){
                Span cut=other.get(j);
                horizontal(cursor,Math.min(cut.start,span.end),z,w,edges);
                cursor=Math.max(cursor,cut.end);if(cursor>=span.end)break;
            }
            horizontal(cursor,span.end,z,w,edges);
        }
    }
    private static void horizontal(int start,int end,int z,Window w,List<Edge> edges){
        start=Math.max(start,w.minX);end=Math.min(end,w.maxX);
        if(start<end)edges.add(new Edge(start,z,end,z));
    }
    private static void vertical(int x,int z,int step,Window w,Map<Integer,Integer> active,Map<Integer,Integer> next,List<Edge> edges){
        if(x<w.minX||x>w.maxX)return;
        Integer index=active.get(x);
        if(index==null){index=edges.size();edges.add(new Edge(x,z,x,z+step));}
        else{Edge old=edges.get(index);edges.set(index,new Edge(x,old.z0(),x,z+step));}
        next.put(x,index);
    }
}
