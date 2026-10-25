package dev.frontier.nations.flag;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** One buffered draw per flag, including pixel art; no per-pixel GPU flushes. */
public final class FlagRenderer {
    private FlagRenderer(){}
    public static void draw(GuiGraphics graphics,String faction,int color,int x,int y,int width,int height){
        FlagDefinition flag=FlagCache.flag(faction);
        graphics.flush();
        VertexConsumer buffer=graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f matrix=graphics.pose().last().pose();
        quad(buffer,matrix,x-1,y-1,x+width+1,y+height+1,0xB0101418);
        if(flag==null){
            quad(buffer,matrix,x,y,x+width,y+height,0xFF000000|(color&0xFFFFFF));
            quad(buffer,matrix,x,y+height/3f,x+width,y+height*2/3f,0xFFE6E5DC);
        }else{
            for(int row=0;row<16;row++)for(int start=0;start<16;){
                int palette=flag.pixel(start,row),end=start+1;
                while(end<16&&flag.pixel(end,row)==palette)end++;
                quad(buffer,matrix,x+start*width/16f,y+row*height/16f,x+end*width/16f,y+(row+1)*height/16f,FlagPalette.color(palette));start=end;
            }
        }
        graphics.flush();
    }
    private static void quad(VertexConsumer buffer,Matrix4f matrix,float left,float top,float right,float bottom,int color){
        vertex(buffer,matrix,left,top,color);vertex(buffer,matrix,left,bottom,color);vertex(buffer,matrix,right,bottom,color);vertex(buffer,matrix,right,top,color);
    }
    private static void vertex(VertexConsumer buffer,Matrix4f matrix,float x,float y,int color){buffer.vertex(matrix,x,y,0).color((color>>16)&255,(color>>8)&255,color&255,(color>>>24)&255).endVertex();}
}
