package dev.frontier.flamingofix;

/** Continuous SAT for one native missile cuboid translating past one rotated vehicle part. */
public final class SweptMissileObb {
    public static final class Box {
        final double x,y,z;
        final double[] edges,normals;
        public Box(double x,double y,double z,double[] edges) {
            this(x,y,z,edges,null);
        }
        public Box(double x,double y,double z,double[] edges,double[] normals) {
            if(edges.length!=9 || normals!=null && normals.length!=9)throw new IllegalArgumentException("Three axes required");
            this.x=x;this.y=y;this.z=z;this.edges=edges.clone();
            if(normals!=null)this.normals=normals.clone();
            else {
                this.normals=new double[9];
                for(int i=0;i<3;i++)cross(edges,(i+1)%3*3,edges,(i+2)%3*3,this.normals,i*3);
            }
        }
        public Box move(double dx,double dy,double dz) { return new Box(x+dx,y+dy,z+dz,edges,normals); }
    }

    /** -1 is a true miss; initial overlap is an immediate contact, as in native missile SAT. */
    public static double fraction(Box moving,Box target,double mx,double my,double mz,double padding) {
        double enter=0,exit=1;
        double dx=moving.x-target.x,dy=moving.y-target.y,dz=moving.z-target.z;
        for(int a=0;a<15;a++) {
            double nx,ny,nz;
            if(a<6) {
                double[] normal=a<3?target.normals:moving.normals;int at=(a%3)*3;
                nx=normal[at];ny=normal[at+1];nz=normal[at+2];
            } else {
                int p=(a-6)/3*3,q=(a-6)%3*3;
                nx=moving.edges[p+1]*target.edges[q+2]-moving.edges[p+2]*target.edges[q+1];
                ny=moving.edges[p+2]*target.edges[q]-moving.edges[p]*target.edges[q+2];
                nz=moving.edges[p]*target.edges[q+1]-moving.edges[p+1]*target.edges[q];
            }
            double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            if(length<1e-10)continue;
            nx/=length;ny/=length;nz/=length;
            double radius=padding;
            for(int e=0;e<9;e+=3)radius+=Math.abs(moving.edges[e]*nx+moving.edges[e+1]*ny+moving.edges[e+2]*nz)
                    +Math.abs(target.edges[e]*nx+target.edges[e+1]*ny+target.edges[e+2]*nz);
            double position=dx*nx+dy*ny+dz*nz,velocity=mx*nx+my*ny+mz*nz;
            if(Math.abs(velocity)<1e-12) { if(Math.abs(position)>radius)return -1; }
            else {
                double first=(-radius-position)/velocity,last=(radius-position)/velocity;
                if(first>last) {double swap=first;first=last;last=swap;}
                enter=Math.max(enter,first);exit=Math.min(exit,last);
                if(enter>exit+1e-10)return -1;
            }
        }
        return enter>=0 && enter<=1?enter:-1;
    }
    private static void cross(double[] a,int p,double[] b,int q,double[] out,int at) {
        out[at]=a[p+1]*b[q+2]-a[p+2]*b[q+1];out[at+1]=a[p+2]*b[q]-a[p]*b[q+2];out[at+2]=a[p]*b[q+1]-a[p+1]*b[q];
    }
    private SweptMissileObb() {}
}
