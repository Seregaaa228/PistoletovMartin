package dev.frontier.nations.map.border;

import dev.frontier.nations.FrontierNations;
import dev.frontier.nations.flag.FlagAuthority;
import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import dev.frontier.nations.network.WriteRateLimit;
import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class BorderNet {
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(FrontierNations.ID,"borders"),()->"2","2"::equals,"2"::equals);
    private static final WriteRateLimit WRITES=new WriteRateLimit(500_000_000L);
    private record Processed(Set request,Result result){}
    private static final Map<java.util.UUID,Processed> PROCESSED=new java.util.HashMap<>();
    private static boolean registered;
    private BorderNet(){}
    public static synchronized void init(){
        if(registered)return;registered=true;
        CHANNEL.registerMessage(0,Set.class,Set::encode,Set::decode,Set::handle,Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1,Snapshot.class,Snapshot::encode,Snapshot::decode,Snapshot::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2,Update.class,Update::encode,Update::decode,Update::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3,Result.class,Result::encode,Result::decode,Result::handle,Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(BorderNet::login);
        MinecraftForge.EVENT_BUS.addListener(BorderNet::logout);
        MinecraftForge.EVENT_BUS.addListener(BorderNet::stopped);
    }
    private static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        boolean replace=true;
        for(var batch:BorderWire.batches(NationBorders.get(player.server).snapshot())){
            CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new Snapshot(replace,batch));
            replace=false;
        }
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event){WRITES.remove(event.getEntity().getUUID());PROCESSED.remove(event.getEntity().getUUID());}
    private static void stopped(ServerStoppedEvent event){WRITES.clear();PROCESSED.clear();}
    public static long save(String faction,List<Point> points){
        List<Point> copy=List.copyOf(points);long request=BorderCache.beginSave(faction);if(request==0)return 0;
        try{CHANNEL.sendToServer(new Set(request,faction,copy));return request;}
        catch(RuntimeException unavailable){BorderCache.abortSave(request);return 0;}
    }
    public static void removeFaction(net.minecraft.server.MinecraftServer server,String faction){
        NationBorders borders=NationBorders.get(server);
        if(borders.snapshot().containsKey(faction)){borders.set(faction,null);CHANNEL.send(PacketDistributor.ALL.noArg(),new Update(faction,null));}
    }
    private static void reply(ServerPlayer player,Set request,boolean accepted,String text){
        Result result=new Result(request.request,request.faction,accepted,text);
        PROCESSED.put(player.getUUID(),new Processed(request,result));
        CHANNEL.send(PacketDistributor.PLAYER.with(()->player),result);
    }
    public record Set(long request,String faction,List<Point> points){
        static void encode(Set m,FriendlyByteBuf b){b.writeLong(m.request);b.writeUtf(m.faction,NationBorders.MAX_ID_LENGTH);BorderWire.write(b,m.points);}
        static Set decode(FriendlyByteBuf b){long request=b.readLong();if(request<=0)throw new DecoderException("Invalid border request");return new Set(request,b.readUtf(NationBorders.MAX_ID_LENGTH),BorderWire.read(b));}
        static void handle(Set m,Supplier<NetworkEvent.Context> supplier){
            var ctx=supplier.get();ctx.enqueueWork(()->{
                ServerPlayer player=ctx.getSender();if(!FlagAuthority.isConnected(player))return;
                var limit=WRITES.check(player.getUUID());
                Processed previous=PROCESSED.get(player.getUUID());
                if(previous!=null&&m.request<=previous.request.request){
                    if(limit==WriteRateLimit.Decision.ALLOW)CHANNEL.send(PacketDistributor.PLAYER.with(()->player),
                        m.equals(previous.request)?previous.result:new Result(m.request,m.faction,false,"Устаревший запрос: отправьте контур заново"));
                    return;
                }
                if(limit!=WriteRateLimit.Decision.ALLOW){
                    String text="Подождите полсекунды перед следующим сохранением";
                    if(limit==WriteRateLimit.Decision.WARN)reply(player,m,false,text);
                    else PROCESSED.put(player.getUUID(),new Processed(m,new Result(m.request,m.faction,false,text)));
                    return;
                }
                // Advance before validation: even failed newer requests must retire older writes.
                PROCESSED.put(player.getUUID(),new Processed(m,new Result(m.request,m.faction,false,"Сохранение не завершено. Отправьте контур заново")));
                String own=FlagAuthority.ownFactionId(player);
                if(own==null||!own.equals(m.faction)||!FlagAuthority.isLeader(player,own)){reply(player,m,false,"Контур меняет только лидер своей страны");return;}
                if(!CountryEditorAccess.hasPda(player)){reply(player,m,false,"Для изменения контура нужен КПК в руке и Верхний мир");return;}
                BorderPolygon polygon;
                try{polygon=m.points.isEmpty()?null:new BorderPolygon(m.points);}catch(IllegalArgumentException invalid){reply(player,m,false,invalid.getMessage());return;}
                if(!NationBorders.get(player.server).set(own,polygon)){reply(player,m,false,"Достигнут лимит контуров сервера");return;}
                CHANNEL.send(PacketDistributor.ALL.noArg(),new Update(own,polygon));
                reply(player,m,true,polygon==null?"Восстановлен контур по владениям":"Контур сохранён. Владения не изменены");
            });ctx.setPacketHandled(true);
        }
    }
    public record Snapshot(boolean replace,Map<String,BorderPolygon> borders){
        static void encode(Snapshot m,FriendlyByteBuf b){if(m.borders.size()>BorderWire.SNAPSHOT_BATCH)throw new IllegalArgumentException("Border snapshot must be paged");b.writeBoolean(m.replace);b.writeVarInt(m.borders.size());m.borders.forEach((id,p)->{b.writeUtf(id,NationBorders.MAX_ID_LENGTH);BorderWire.write(b,p.points());});}
        static Snapshot decode(FriendlyByteBuf b){boolean replace=b.readBoolean();int n=b.readVarInt();if(n<0||n>BorderWire.SNAPSHOT_BATCH)throw new DecoderException("Invalid border snapshot size");Map<String,BorderPolygon> map=new LinkedHashMap<>();for(int i=0;i<n;i++){String id=b.readUtf(NationBorders.MAX_ID_LENGTH);map.put(id,new BorderPolygon(BorderWire.read(b)));}return new Snapshot(replace,Map.copyOf(map));}
        static void handle(Snapshot m,Supplier<NetworkEvent.Context> s){var ctx=s.get();ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->{if(!dev.frontier.nations.network.ClientPacketGuard.current(ctx))return;if(m.replace)BorderCache.replace(m.borders);else BorderCache.merge(m.borders);}));ctx.setPacketHandled(true);}
    }
    public record Update(String faction,BorderPolygon polygon){
        static void encode(Update m,FriendlyByteBuf b){b.writeUtf(m.faction,NationBorders.MAX_ID_LENGTH);BorderWire.write(b,m.polygon==null?List.of():m.polygon.points());}
        static Update decode(FriendlyByteBuf b){String id=b.readUtf(NationBorders.MAX_ID_LENGTH);List<Point> p=BorderWire.read(b);return new Update(id,p.isEmpty()?null:new BorderPolygon(p));}
        static void handle(Update m,Supplier<NetworkEvent.Context> s){var ctx=s.get();ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->{if(dev.frontier.nations.network.ClientPacketGuard.current(ctx))BorderCache.update(m.faction,m.polygon);}));ctx.setPacketHandled(true);}
    }
    public record Result(long request,String faction,boolean accepted,String message){
        static void encode(Result m,FriendlyByteBuf b){b.writeLong(m.request);b.writeUtf(m.faction,NationBorders.MAX_ID_LENGTH);b.writeBoolean(m.accepted);b.writeUtf(m.message,256);}
        static Result decode(FriendlyByteBuf b){long request=b.readLong();if(request<=0)throw new DecoderException("Invalid border result");return new Result(request,b.readUtf(NationBorders.MAX_ID_LENGTH),b.readBoolean(),b.readUtf(256));}
        static void handle(Result m,Supplier<NetworkEvent.Context> s){var ctx=s.get();ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->{if(dev.frontier.nations.network.ClientPacketGuard.current(ctx))BorderCache.result(m.request,m.faction,m.accepted,m.message);}));ctx.setPacketHandled(true);}
    }
}
