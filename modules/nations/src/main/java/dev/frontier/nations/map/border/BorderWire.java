package dev.frontier.nations.map.border;

import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded wire decoding independent of channel registration and world state. */
public final class BorderWire {
    public static final int SNAPSHOT_BATCH = 64;
    private BorderWire(){}
    public static List<Map<String,BorderPolygon>> batches(Map<String,BorderPolygon> borders){
        List<Map<String,BorderPolygon>> result=new ArrayList<>();
        Map<String,BorderPolygon> batch=new LinkedHashMap<>();
        for(var entry:borders.entrySet()){
            batch.put(entry.getKey(),entry.getValue());
            if(batch.size()==SNAPSHOT_BATCH){result.add(Map.copyOf(batch));batch.clear();}
        }
        if(!batch.isEmpty()||result.isEmpty())result.add(Map.copyOf(batch));
        return List.copyOf(result);
    }
    public static void write(FriendlyByteBuf buffer,List<Point> points){
        if(points.size()>BorderPolygon.MAX_POINTS)throw new IllegalArgumentException("Too many border vertices");
        buffer.writeVarInt(points.size());for(Point point:points){buffer.writeDouble(point.x());buffer.writeDouble(point.z());}
    }
    public static List<Point> read(FriendlyByteBuf buffer){
        int count=buffer.readVarInt();
        if(count<0||count>BorderPolygon.MAX_POINTS||buffer.readableBytes()<count*16)throw new DecoderException("Invalid border vertex count");
        List<Point> points=new ArrayList<>(count);
        for(int i=0;i<count;i++)points.add(new Point(buffer.readDouble(),buffer.readDouble()));
        return List.copyOf(points);
    }
}
