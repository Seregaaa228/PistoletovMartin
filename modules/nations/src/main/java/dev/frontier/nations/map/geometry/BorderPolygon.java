package dev.frontier.nations.map.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded immutable simple polygon. Cosmetic only: never creates native claims. */
public final class BorderPolygon {
    public static final int MAX_POINTS=128;
    public static final double WORLD_LIMIT=29_999_984;
    private static final double EPS=1e-7;
    public record Point(double x,double z) {}
    public record Triangle(Point a,Point b,Point c) {}
    public record Bounds(double minX,double minZ,double maxX,double maxZ) {
        public boolean intersects(double x0,double z0,double x1,double z1){return maxX>x0&&minX<x1&&maxZ>z0&&minZ<z1;}
    }
    private final List<Point> points;
    private final Bounds bounds;
    private final List<Triangle> triangles;
    private final Point label;

    public BorderPolygon(List<Point> source) {
        if(source==null || source.size()<3 || source.size()>MAX_POINTS)
            throw new IllegalArgumentException("Нужно от 3 до 128 точек");
        points=List.copyOf(source);
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(int i=0;i<points.size();i++) {
            Point a=points.get(i);
            if(a==null || !Double.isFinite(a.x) || !Double.isFinite(a.z) || Math.abs(a.x)>WORLD_LIMIT || Math.abs(a.z)>WORLD_LIMIT)
                throw new IllegalArgumentException("Точка за пределами мира");
            minX=Math.min(minX,a.x);minZ=Math.min(minZ,a.z);maxX=Math.max(maxX,a.x);maxZ=Math.max(maxZ,a.z);
            for(int j=0;j<i;j++) if(distance2(a,points.get(j))<EPS)
                throw new IllegalArgumentException("Точки не должны совпадать");
        }
        bounds=new Bounds(minX,minZ,maxX,maxZ);
        for(int i=0;i<points.size();i++) for(int j=i+1;j<points.size();j++) {
            if(j==i+1 || i==0 && j==points.size()-1) continue;
            if(intersects(points.get(i),points.get((i+1)%points.size()),points.get(j),points.get((j+1)%points.size())))
                throw new IllegalArgumentException("Граница пересекает сама себя");
        }
        double signed=signedArea(points);
        if(Math.abs(signed)<1) throw new IllegalArgumentException("Контур слишком мал или лежит на одной линии");
        triangles=List.copyOf(triangulate(points,signed));
        Triangle largest=triangles.stream().max(java.util.Comparator.comparingDouble(t -> Math.abs(cross(t.a,t.b,t.c)))).orElseThrow();
        label=new Point((largest.a.x+largest.b.x+largest.c.x)/3,(largest.a.z+largest.b.z+largest.c.z)/3);
    }
    public List<Point> points(){return points;}
    public Bounds bounds(){return bounds;}
    public List<Triangle> triangles(){return triangles;}
    public Point labelPoint(){return label;}
    public boolean contains(Point p) {
        boolean inside=false;
        for(int i=0,j=points.size()-1;i<points.size();j=i++) {
            Point a=points.get(i),b=points.get(j);
            if(Math.abs(cross(a,b,p))<EPS && between(a,b,p)) return true;
            if((a.z>p.z)!=(b.z>p.z) && p.x<(b.x-a.x)*(p.z-a.z)/(b.z-a.z)+a.x) inside=!inside;
        }
        return inside;
    }
    public static double distance2(Point a,Point b){double x=a.x-b.x,z=a.z-b.z;return x*x+z*z;}
    private static double cross(Point a,Point b,Point c){return (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x);}
    private static boolean between(Point a,Point b,Point p){return p.x>=Math.min(a.x,b.x)-EPS&&p.x<=Math.max(a.x,b.x)+EPS&&p.z>=Math.min(a.z,b.z)-EPS&&p.z<=Math.max(a.z,b.z)+EPS;}
    private static boolean intersects(Point a,Point b,Point c,Point d) {
        double abC=cross(a,b,c),abD=cross(a,b,d),cdA=cross(c,d,a),cdB=cross(c,d,b);
        if(((abC>EPS&&abD<-EPS)||(abC<-EPS&&abD>EPS))&&((cdA>EPS&&cdB<-EPS)||(cdA<-EPS&&cdB>EPS)))return true;
        return Math.abs(abC)<EPS&&between(a,b,c)||Math.abs(abD)<EPS&&between(a,b,d)||Math.abs(cdA)<EPS&&between(c,d,a)||Math.abs(cdB)<EPS&&between(c,d,b);
    }
    private static double signedArea(List<Point> p) {
        // Subtract the first point to avoid loss of precision at the world border.
        Point origin=p.get(0);double sum=0;
        for(int i=1;i+1<p.size();i++)sum+=cross(origin,p.get(i),p.get(i+1));
        return sum/2;
    }
    private static List<Triangle> triangulate(List<Point> source,double area) {
        List<Point> ring=new ArrayList<>(source);
        if(area<0) Collections.reverse(ring);
        for(int i=ring.size()-1;i>=0&&ring.size()>3;i--) {
            int n=ring.size();if(Math.abs(cross(ring.get((i+n-1)%n),ring.get(i),ring.get((i+1)%n)))<EPS)ring.remove(i);
        }
        List<Triangle> result=new ArrayList<>();
        while(ring.size()>3) {
            boolean found=false;int n=ring.size();
            for(int i=0;i<n;i++) {
                Point a=ring.get((i+n-1)%n),b=ring.get(i),c=ring.get((i+1)%n);
                if(cross(a,b,c)<=EPS)continue;
                boolean blocked=false;
                for(Point p:ring) if(p!=a&&p!=b&&p!=c&&cross(a,b,p)>=-EPS&&cross(b,c,p)>=-EPS&&cross(c,a,p)>=-EPS){blocked=true;break;}
                if(blocked)continue;
                result.add(new Triangle(a,b,c));ring.remove(i);found=true;break;
            }
            if(!found)throw new IllegalArgumentException("Не удалось замкнуть контур: проверьте точки");
        }
        result.add(new Triangle(ring.get(0),ring.get(1),ring.get(2)));
        return result;
    }
    @Override public boolean equals(Object other){return other instanceof BorderPolygon p&&points.equals(p.points);}
    @Override public int hashCode(){return points.hashCode();}
}
