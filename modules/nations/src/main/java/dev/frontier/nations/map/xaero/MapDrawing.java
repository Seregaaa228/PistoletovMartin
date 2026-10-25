package dev.frontier.nations.map.xaero;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.map.geometry.ChunkShape;
import dev.frontier.nations.map.geometry.MapProjection;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

final class MapDrawing {
    private MapDrawing(){}
    static void clear(){BlockMeshCache.clear();}
    static void beginFrame(){BlockMeshCache.beginFrame();}
    static void pause(){BlockMeshCache.pause();}
    static void blocks(GuiGraphics g,BorderPolygon polygon,MapProjection view,int color,int width,int height){
        chunks(g,BlockMeshCache.visible(polygon,view,width,height),view,color,width,height);
    }
    static void blockOutline(GuiGraphics g,BorderPolygon polygon,MapProjection view,int color,int width,int height){
        List<ChunkShape> shapes=BlockMeshCache.visible(polygon,view,width,height);
        if(shapes.isEmpty())return;
        g.flush();begin();BufferBuilder buffer=Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);Matrix4f matrix=g.pose().last().pose();
        for(ChunkShape shape:shapes){stroke(buffer,matrix,shape,view,4,0xFF101418,width,height);stroke(buffer,matrix,shape,view,1.5f,color,width,height);}
        BufferUploader.drawWithShader(buffer.end());end();
    }
    static void outline(GuiGraphics graphics,List<Point> points,MapProjection view,int color,float width,boolean closed){
        if(points.size()<2)return;
        graphics.flush();begin();BufferBuilder buffer=Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);Matrix4f matrix=graphics.pose().last().pose();
        for(int i=0;i<(closed?points.size():points.size()-1);i++)line(buffer,matrix,view.screen(points.get(i)),view.screen(points.get((i+1)%points.size())),width,color);
        BufferUploader.drawWithShader(buffer.end());end();
    }
    static void chunks(GuiGraphics graphics,ChunkShape shape,MapProjection view,int color,int width,int height){
        if(!shape.fills().isEmpty()||!shape.edges().isEmpty())chunks(graphics,List.of(shape),view,color,width,height);
    }
    private static void chunks(GuiGraphics graphics,List<ChunkShape> shapes,MapProjection view,int color,int width,int height){
        if(shapes.isEmpty())return;
        graphics.flush();begin();BufferBuilder buffer=Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);Matrix4f matrix=graphics.pose().last().pose();
        Point low=view.world(new Point(0,38)),high=view.world(new Point(width,height-34));
        for(ChunkShape shape:shapes)for(var r:shape.fills()){
            if(r.x1()<low.x()||r.x0()>high.x()||r.z1()<low.z()||r.z0()>high.z())continue;
            Point a=view.screen(new Point(Math.max(r.x0(),low.x()),Math.max(r.z0(),low.z()))),c=view.screen(new Point(Math.min(r.x1(),high.x()),Math.min(r.z1(),high.z())));
            Point b=new Point(c.x(),a.z()),d=new Point(a.x(),c.z());int fill=0x26000000|(color&0xFFFFFF);
            vertex(buffer,matrix,a,fill);vertex(buffer,matrix,b,fill);vertex(buffer,matrix,c,fill);
            vertex(buffer,matrix,a,fill);vertex(buffer,matrix,c,fill);vertex(buffer,matrix,d,fill);
        }
        for(ChunkShape shape:shapes){stroke(buffer,matrix,shape,view,3,0xFF101418,width,height);stroke(buffer,matrix,shape,view,1.2f,0xFF000000|(color&0xFFFFFF),width,height);}
        BufferUploader.drawWithShader(buffer.end());end();
    }
    private static void stroke(BufferBuilder buffer,Matrix4f matrix,ChunkShape shape,MapProjection view,float width,int color,int screenWidth,int screenHeight){
        double margin=width/2+2;
        Point low=view.world(new Point(-margin,38-margin)),high=view.world(new Point(screenWidth+margin,screenHeight-34+margin));
        for(var e:shape.edges()){
            if(Math.max(e.x0(),e.x1())<low.x()||Math.min(e.x0(),e.x1())>high.x()||Math.max(e.z0(),e.z1())<low.z()||Math.min(e.z0(),e.z1())>high.z())continue;
            Point a=new Point(Math.max(low.x(),Math.min(high.x(),e.x0())),Math.max(low.z(),Math.min(high.z(),e.z0())));
            Point b=new Point(Math.max(low.x(),Math.min(high.x(),e.x1())),Math.max(low.z(),Math.min(high.z(),e.z1())));
            line(buffer,matrix,view.screen(a),view.screen(b),width,color);
        }
    }
    private static void line(BufferBuilder b,Matrix4f m,Point a,Point c,float width,int color){
        double dx=c.x()-a.x(),dy=c.z()-a.z(),length=Math.hypot(dx,dy);if(length<0.001)return;
        double nx=-dy/length*width/2,ny=dx/length*width/2;
        // Square joins cover the outer corner of each block without diagonal gaps.
        double capX=dx/length*width/2,capY=dy/length*width/2;
        Point p=new Point(a.x()+nx-capX,a.z()+ny-capY),q=new Point(c.x()+nx+capX,c.z()+ny+capY),r=new Point(c.x()-nx+capX,c.z()-ny+capY),s=new Point(a.x()-nx-capX,a.z()-ny-capY);
        vertex(b,m,p,color);vertex(b,m,q,color);vertex(b,m,r,color);vertex(b,m,p,color);vertex(b,m,r,color);vertex(b,m,s,color);
    }
    private static void vertex(BufferBuilder b,Matrix4f m,Point p,int color){b.vertex(m,(float)p.x(),(float)p.z(),0).color((color>>16)&255,(color>>8)&255,color&255,(color>>>24)&255).endVertex();}
    private static void begin(){RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.setShader(GameRenderer::getPositionColorShader);}
    private static void end(){RenderSystem.enableCull();}
}
