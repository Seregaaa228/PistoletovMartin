package dev.frontier.industry;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import net.minecraft.network.FriendlyByteBuf;

/** Exercise complete large Unicode snapshots through the actual packet codec. */
public final class OperationsPayloadTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        byte[] entropy=new byte[700_000];new Random(20261025).nextBytes(entropy);
        String json="{\"message\":\"КПК • флаг 🇺🇦\",\"catalogue\":\""+Base64.getEncoder().encodeToString(entropy)+"я界🙂".repeat(100_000)+"\"}";
        check(json.length()>196608,"snapshot exceeds former silent-drop threshold");
        byte[] compressed=OperationsPayload.encode(json);
        check(compressed.length>OperationsPayload.PART_BYTES,"incompressible catalogue needs multiple packets");
        int parts=(compressed.length+OperationsPayload.PART_BYTES-1)/OperationsPayload.PART_BYTES;
        var assembled=new ByteArrayOutputStream();
        for(int part=0;part<parts;part++) {
            int start=part*OperationsPayload.PART_BYTES;
            byte[] bytes=Arrays.copyOfRange(compressed,start,Math.min(compressed.length,start+OperationsPayload.PART_BYTES));
            var packet=new OperationsNet.View(42,part,compressed.length,bytes,true,false);
            FriendlyByteBuf wire=new FriendlyByteBuf(Unpooled.buffer());
            try {
                OperationsNet.View.encode(packet,wire);
                check(wire.readableBytes()<1_048_576,"part fits vanilla payload limit");
                var decoded=OperationsNet.View.decode(wire);
                check(decoded.id()==42&&decoded.part()==part&&decoded.parts()==parts&&decoded.open()&&!decoded.camera(),"packet metadata round trip");
                check(wire.readableBytes()==0,"whole packet consumed");assembled.write(decoded.data());
            } finally {wire.release();}
        }
        check(OperationsPayload.decode(assembled.toByteArray()).equals(json),"all Unicode and catalogue data survives split transfer");
        check(OperationsPayload.decode(OperationsPayload.encode("{}")).equals("{}"),"small snapshot round trip");
        byte[] corrupt=compressed.clone();corrupt[corrupt.length-8]^=1;
        rejects(()->OperationsPayload.decode(corrupt),"damaged compressed snapshot rejected");
        rejects(()->OperationsPayload.decode(Arrays.copyOf(compressed,9)),"truncated gzip rejected");
        rejects(()->OperationsPayload.encode("x".repeat(OperationsPayload.MAX_RAW_BYTES+1)),"oversized source rejected");
        rejects(()->OperationsPayload.decode(new byte[OperationsPayload.MAX_BYTES+1]),"oversized wire rejected before decompression");
        var bomb=new ByteArrayOutputStream();
        try(var gzip=new GZIPOutputStream(bomb)){gzip.write(new byte[OperationsPayload.MAX_RAW_BYTES+1]);}
        rejects(()->OperationsPayload.decode(bomb.toByteArray()),"decompression cannot exceed raw byte budget");
        String multibyte="界".repeat(OperationsPayload.MAX_RAW_BYTES/3+1);
        rejects(()->OperationsPayload.encode(multibyte),"UTF-8 byte budget is independent of character count");
        System.out.println("OperationsPayloadTest: "+checks+" checks passed");
    }
    private interface IoAction {void run() throws IOException;}
    private static void rejects(IoAction action,String name) throws Exception {try{action.run();throw new AssertionError(name);}catch(IOException expected){checks++;}}
    private static void check(boolean pass,String name){checks++;if(!pass)throw new AssertionError(name);}
}
