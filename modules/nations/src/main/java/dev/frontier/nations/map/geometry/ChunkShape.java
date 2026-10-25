package dev.frontier.nations.map.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A union of native chunks: merged horizontal fill spans and only exterior edges. */
public record ChunkShape(List<Rect> fills,List<Edge> edges) {
    public record Chunk(int x,int z){}
    public record Rect(double x0,double z0,double x1,double z1){}
    public record Edge(double x0,double z0,double x1,double z1){}
    private record Axis(boolean vertical,int fixed){}
    public static ChunkShape build(List<Chunk> chunks){
        Set<Chunk> cells=new HashSet<>(chunks);Map<Integer,List<Integer>> rows=new HashMap<>();
        Map<Axis,List<Integer>> lines=new HashMap<>();
        for(Chunk c:cells){
            rows.computeIfAbsent(c.z,k->new ArrayList<>()).add(c.x);
            if(!cells.contains(new Chunk(c.x-1,c.z)))add(lines,new Axis(true,c.x),c.z);
            if(!cells.contains(new Chunk(c.x+1,c.z)))add(lines,new Axis(true,c.x+1),c.z);
            if(!cells.contains(new Chunk(c.x,c.z-1)))add(lines,new Axis(false,c.z),c.x);
            if(!cells.contains(new Chunk(c.x,c.z+1)))add(lines,new Axis(false,c.z+1),c.x);
        }
        List<Rect> fills=new ArrayList<>();List<Edge> edges=new ArrayList<>();
        rows.forEach((z,xs)->spans(xs,(start,end)->fills.add(new Rect(start*16.0,z*16.0,end*16.0,(z+1)*16.0))));
        lines.forEach((axis,values)->spans(values,(start,end)->edges.add(axis.vertical?new Edge(axis.fixed*16.0,start*16.0,axis.fixed*16.0,end*16.0):new Edge(start*16.0,axis.fixed*16.0,end*16.0,axis.fixed*16.0))));
        return new ChunkShape(List.copyOf(fills),List.copyOf(edges));
    }
    public double perimeter(){return edges.stream().mapToDouble(e->Math.hypot(e.x1-e.x0,e.z1-e.z0)).sum();}
    private static void add(Map<Axis,List<Integer>> lines,Axis axis,int v){lines.computeIfAbsent(axis,k->new ArrayList<>()).add(v);}
    private interface Span{void accept(int start,int end);}
    private static void spans(List<Integer> values,Span sink){
        values.sort(Comparator.naturalOrder());int start=values.get(0),previous=start;
        for(int i=1;i<values.size();i++){int v=values.get(i);if(v>previous+1){sink.accept(start,previous+1);start=v;}previous=v;}
        sink.accept(start,previous+1);
    }
}
