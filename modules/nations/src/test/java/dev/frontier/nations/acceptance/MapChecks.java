package dev.frontier.nations.acceptance;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/** Exists only in the explicitly built acceptance JAR, never production Nations. */
@Mod("frontier_map_checks")
public final class MapChecks {
    public static final String FACTION="map_acceptance";
    public static boolean enabled(){
        if(!Boolean.getBoolean("frontier.mapAcceptance"))return false;
        try{
            var marker=JsonParser.parseString(Files.readString(FMLPaths.GAMEDIR.get().resolve("test-profile.json"))).getAsJsonObject();
            return !marker.get("worldsCopied").getAsBoolean()&&marker.get("runId").getAsString().equals(System.getProperty("frontier.mapRunId"));
        }catch(Exception invalid){return false;}
    }
    private static final net.minecraftforge.network.simple.SimpleChannel TEST_NET=net.minecraftforge.network.NetworkRegistry.newSimpleChannel(new net.minecraft.resources.ResourceLocation("frontier_map_checks","fixtures"),()->"1","1"::equals,"1"::equals);
    public MapChecks(){if(!enabled())return;
        TEST_NET.registerMessage(0,LargePda.class,(p,b)->{},b->new LargePda(),MapChecks::largePda,java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        MinecraftForge.EVENT_BUS.register(new MapServerChecks());DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->MapClientChecks::register);}
    public record LargePda(){}
    public static void requestLargePda(){TEST_NET.sendToServer(new LargePda());}
    public static String largeBlob(){byte[] random=new byte[600_000];new java.util.Random(20261025).nextBytes(random);return java.util.Base64.getEncoder().encodeToString(random)+"КПК界🙂".repeat(20_000);}
    private static void largePda(LargePda packet,java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> source){
        var ctx=source.get();ctx.enqueueWork(()->{
            var player=ctx.getSender();if(!enabled()||player==null||!player.getGameProfile().getName().equals("MapOwner"))return;
            try{
                var json=new com.google.gson.JsonObject();json.addProperty("fixture","large-unicode-pda");json.addProperty("blob",largeBlob());
                byte[] bytes=dev.frontier.industry.OperationsPayload.encode(json.toString());
                var channelField=dev.frontier.industry.OperationsNet.class.getDeclaredField("CHANNEL");channelField.setAccessible(true);
                var channel=(net.minecraftforge.network.simple.SimpleChannel)channelField.get(null);
                var sequence=dev.frontier.industry.OperationsNet.class.getDeclaredField("nextView");sequence.setAccessible(true);long id=sequence.getLong(null)+1;sequence.setLong(null,id);
                int size=dev.frontier.industry.OperationsPayload.PART_BYTES;
                for(int part=0;part*size<bytes.length;part++){
                    int start=part*size;channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(()->player),new dev.frontier.industry.OperationsNet.View(id,part,bytes.length,java.util.Arrays.copyOfRange(bytes,start,Math.min(bytes.length,start+size)),false,false));
                }
                write("large-pda-fixture.json","{\"compressedBytes\":"+bytes.length+",\"parts\":"+((bytes.length+size-1)/size)+"}");
            }catch(Exception failure){throw new IllegalStateException("Large PDA fixture failed",failure);}
        });ctx.setPacketHandled(true);
    }
    public static void write(String name,String text){try{Files.writeString(FMLPaths.GAMEDIR.get().resolve(name),text);}catch(Exception error){throw new IllegalStateException(error);}}
}
