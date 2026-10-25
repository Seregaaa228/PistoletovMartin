package dev.frontier.flamingofix;

public final class SweptMissileObbTest {
    private static int checks;
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label);checks++; }
    private static double[] rotate(double[] p,double yaw,double roll) {
        double c=Math.cos(yaw),s=Math.sin(yaw),cr=Math.cos(roll),sr=Math.sin(roll);
        double x=p[0]*c+p[2]*s,z=-p[0]*s+p[2]*c;
        return new double[]{x*cr-p[1]*sr,x*sr+p[1]*cr,z};
    }
    private static SweptMissileObb.Box rotated(SweptMissileObb.Box box,double yaw,double roll) {
        double[] center=rotate(new double[]{box.x,box.y,box.z},yaw,roll),edges=new double[9];
        for(int i=0;i<3;i++)System.arraycopy(rotate(new double[]{box.edges[i*3],box.edges[i*3+1],box.edges[i*3+2]},yaw,roll),0,edges,i*3,3);
        return new SweptMissileObb.Box(center[0],center[1],center[2],edges);
    }
    public static void main(String[] args) {
        var missile=new SweptMissileObb.Box(20,0,-10,new double[]{.1,0,0,0,.1,0,0,0,1});
        var wing=new SweptMissileObb.Box(20,0,0,new double[]{4,0,0,0,.1,0,0,0,1});
        check(Math.abs(SweptMissileObb.fraction(missile,wing,0,0,20,0)-.4)<1e-9,"known thin wing contact time");
        for(int i=0;i<20;i++) {
            double yaw=i*.213,roll=i*.137;
            var m=rotated(missile,yaw,roll);var w=rotated(wing,yaw,roll);double[] velocity=rotate(new double[]{0,0,20},yaw,roll);
            check(Math.abs(SweptMissileObb.fraction(m,w,velocity[0],velocity[1],velocity[2],0)-.4)<1e-8,"all-axis rotation preserves contact "+i);
            var miss=rotated(missile.move(6,0,0),yaw,roll);
            check(SweptMissileObb.fraction(miss,w,velocity[0],velocity[1],velocity[2],0)<0,"rotated outer wing near miss "+i);
            var diagonal=new SweptMissileObb.Box(0,0,10,new double[]{3,0,3,0,.1,0,-.1,0,.1});
            var corner=new SweptMissileObb.Box(2.5,-5,7.5,new double[]{.1,0,0,0,.1,0,0,0,.1});
            var through=new SweptMissileObb.Box(2.5,-5,12.5,new double[]{.1,0,0,0,.1,0,0,0,.1});
            check(SweptMissileObb.fraction(corner,diagonal,0,10,0,0)<0,"world AABB empty corner remains empty "+i);
            check(SweptMissileObb.fraction(through,diagonal,0,10,0,0)>=0,"diagonal actual wing hit "+i);
            check(SweptMissileObb.fraction(missile.move(i,0,0),wing.move(i,0,0),0,0,20,0)==.4,"common translation invariance "+i);
        }
        check(SweptMissileObb.fraction(wing,wing,0,0,0,0)==0,"initial overlap is immediate native contact");
        check(SweptMissileObb.fraction(missile.move(0,.3,0),wing,0,0,20,0)<0,"thin plane vertical gap");
        check(SweptMissileObb.fraction(missile.move(0,.3,0),wing,0,0,20,.11)>=0,"native angular padding retained");
        check(Math.abs(SweptMissileObb.fraction(missile,wing,0,0,10,0)-.8)<1e-9,"relative translating target contact");
        System.out.println("SweptMissileObbTest: "+checks+" checks passed");
    }
}
