package dev.frontier.arcade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.arcade.shop.NativeAviationCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.List;

/** Server-side bridge for Industry's unified PDA. Currency and output never come from the client. */
public final class PdaShop {
    private PdaShop() { }
    public static JsonObject snapshot(ServerPlayer player) {
        JsonObject out=new JsonObject();out.addProperty("available",true);
        out.addProperty("title","Авиация, БПЛА и боеприпасы");out.addProperty("currency","изумруды");
        int balance=player.getInventory().items.stream().filter(s->s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
        out.addProperty("balance",balance);JsonArray items=new JsonArray();
        for(var offer:NativeAviationCatalog.offers()) {
            var entry=new JsonObject();entry.addProperty("id",offer.id());entry.addProperty("name",offer.title());entry.addProperty("cost",offer.price());
            entry.addProperty("kind",offer.kind().name());entry.addProperty("count",offer.count());
            if(offer.kind()==NativeAviationCatalog.Kind.AIRCRAFT) {
                // Display-only native crate; no entity construction on PDA refresh.
                var crate=ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare:container"));
                if(crate!=null&&crate!=Items.AIR) {
                    var icon=new ItemStack(crate);icon.getOrCreateTagElement("BlockEntityTag").putString("EntityType",offer.id());
                    String nbt=icon.save(new CompoundTag()).toString();if(nbt.length()<=8192)entry.addProperty("icon",nbt);
                }
            } else entry.addProperty("item",offer.id());
            items.add(entry);
        }
        out.add("items",items);
        JsonArray cameras=new JsonArray();
        for(var target:player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class,player.getBoundingBox().inflate(96),e->dev.frontier.arcade.uav.NativeUavCamera.owns(player,e))) {
            var entry=new JsonObject();entry.addProperty("id",target.getStringUUID());entry.addProperty("name","Камера Шахеда");cameras.add(entry);
        }
        out.add("cameraTargets",cameras);return out;
    }
    public static String action(ServerPlayer player,String action,String arg,int value) {
        if(player.getServer()==null||!player.getServer().isSameThread())return "Покупка доступна только на сервере.";
        if(!player.isAlive()||player.isSpectator())return "Покупка сейчас недоступна.";
        if(!isPda(player.getMainHandItem())&&!isPda(player.getOffhandItem()))return "Возьмите единый КПК в руку.";
        if("camera".equals(action)&&value==1&&arg!=null&&arg.length()<=128)return dev.frontier.arcade.uav.NativeUavCamera.pdaAction(player,arg);
        if(!"shop".equals(action)||value!=1||arg==null||arg.length()>128)return "Некорректный запрос покупки.";
        return purchase(player,arg);
    }
    private static String purchase(ServerPlayer player,String arg) {
        var offer=NativeAviationCatalog.offer(arg).orElse(null);
        if(offer==null||offer.price()<=0)return "Товар не найден.";
        ItemStack output=NativeAviationCatalog.createStack(player.serverLevel(),offer.id());
        if(output.isEmpty()||ArcadeVehicles.isReplaced(output))return "Товар недоступен в установленной сборке.";
        // Work on copies first: either payment and the entire output fit, or nothing changes.
        List<ItemStack> next=new ArrayList<>();for(ItemStack stack:player.getInventory().items)next.add(stack.copy());
        int remaining=offer.price();
        for(ItemStack stack:next)if(stack.is(Items.EMERALD)&&remaining>0) {int n=Math.min(remaining,stack.getCount());stack.shrink(n);remaining-=n;}
        if(remaining!=0)return "Недостаточно изумрудов: нужно "+offer.price()+".";
        ItemStack pending=output.copy();
        for(ItemStack stack:next)if(!stack.isEmpty()&&ItemStack.isSameItemSameTags(stack,pending)) {
            int n=Math.min(pending.getCount(),Math.max(0,Math.min(64,stack.getMaxStackSize())-stack.getCount()));stack.grow(n);pending.shrink(n);
            if(pending.isEmpty())break;
        }
        for(int i=0;i<next.size()&&!pending.isEmpty();i++)if(next.get(i).isEmpty()) {
            int n=Math.min(pending.getCount(),Math.min(64,pending.getMaxStackSize()));next.set(i,pending.copyWithCount(n));pending.shrink(n);
        }
        if(!pending.isEmpty())return "Освободите место в инвентаре. Оплата не списана.";
        for(int i=0;i<next.size();i++)player.getInventory().items.set(i,next.get(i));
        player.getInventory().setChanged();player.containerMenu.broadcastChanges();
        return "Куплено: "+offer.title()+" ×"+offer.count()+" за "+offer.price()+" изумр.";
    }
    private static boolean isPda(ItemStack stack) {
        var id=ForgeRegistries.ITEMS.getKey(stack.getItem());return id!=null&&java.util.Set.of("frontier_industry:dispatch_tablet","deepstrike:jagm_camera_tablet").contains(id.toString());
    }
}
