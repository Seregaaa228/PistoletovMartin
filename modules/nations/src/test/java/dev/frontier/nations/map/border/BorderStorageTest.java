package dev.frontier.nations.map.border;

import dev.frontier.nations.flag.FlagDefinition;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;

public final class BorderStorageTest {
    private static int checks;
    public static void main(String[] args){
        BorderPolygon shape=new BorderPolygon(List.of(new Point(-100,-30),new Point(200,-30),new Point(200,90),new Point(-30,90),new Point(-30,300),new Point(-100,300)));
        NationBorders store=new NationBorders();
        check(store.set("alpha",shape),"store new border");
        check(!store.set(" ",shape),"blank faction refused");
        check(!store.set("x".repeat(129),shape),"long faction refused");
        CompoundTag saved=store.save(new CompoundTag());
        check(NationBorders.load(saved).snapshot().equals(store.snapshot()),"NBT keeps all vertices and faction IDs");
        check(store.set("alpha",null)&&NationBorders.load(store.save(new CompoundTag())).snapshot().isEmpty(),"reset survives reload");
        for(int i=0;i<NationBorders.MAX_ENTRIES;i++)check(store.set("nation"+i,shape),"bounded entry "+i);
        check(!store.set("overflow",shape),"entry limit refused");
        check(store.set("nation0",shape),"existing border writable at limit");
        CompoundTag bad=new CompoundTag();bad.putInt("Version",1);ListTag entries=new ListTag();CompoundTag entry=new CompoundTag();entry.putString("Faction","bad");entry.put("Points",new ListTag());entries.add(entry);bad.put("Borders",entries);
        check(NationBorders.load(bad).snapshot().isEmpty(),"invalid saved contour skipped");
        for(List<Point> points:List.of(shape.points(),List.<Point>of())){
            FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
            try{BorderWire.write(buffer,points);check(BorderWire.read(buffer).equals(points),"wire round trip including reset");check(buffer.readableBytes()==0,"whole wire record consumed");}finally{buffer.release();}
        }
        for(int count:new int[]{-1,129,Integer.MAX_VALUE,3}){
            FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
            try{buffer.writeVarInt(count);try{BorderWire.read(buffer);throw new AssertionError("malformed count accepted: "+count);}catch(DecoderException expected){checks++;}}finally{buffer.release();}
        }
        FlagDefinition flag=FlagDefinition.solid(14);
        check(flag.equals(FlagDefinition.fromNbt(flag.toNbt())),"existing flag NBT format retained");
        FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
        try{flag.encode(buffer);check(buffer.readableBytes()==129,"flag packet remains version plus 128 bytes");check(flag.equals(FlagDefinition.decode(buffer)),"existing flag packet round trip");}finally{buffer.release();}
        var vertices=new ArrayList<Point>();
        for(int i=0;i<128;i++)vertices.add(new Point(1000*Math.cos(i*Math.PI/64),1000*Math.sin(i*Math.PI/64)));
        var maximum=new BorderPolygon(vertices);Map<String,BorderPolygon> huge=new LinkedHashMap<>();
        for(int i=0;i<1024;i++)huge.put("nation_"+i+"_"+"я".repeat(110),maximum);
        var batches=BorderWire.batches(huge);check(batches.size()==16,"max snapshot is paged");
        Map<String,BorderPolygon> rebuilt=new LinkedHashMap<>();
        for(var batch:batches){
            FriendlyByteBuf packet=new FriendlyByteBuf(Unpooled.buffer());
            try{packet.writeBoolean(rebuilt.isEmpty());packet.writeVarInt(batch.size());for(var entry2:batch.entrySet()){packet.writeUtf(entry2.getKey(),128);BorderWire.write(packet,entry2.getValue().points());}check(packet.readableBytes()<1_048_576,"every maximum-size packet fits vanilla payload limit");}finally{packet.release();}
            rebuilt.putAll(batch);
        }
        check(rebuilt.equals(huge),"paged snapshot preserves every contour");
        check(BorderWire.batches(Map.of()).size()==1,"empty snapshot still clears previous cache");
        System.out.println("BorderStorageTest: "+checks+" checks passed");
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
