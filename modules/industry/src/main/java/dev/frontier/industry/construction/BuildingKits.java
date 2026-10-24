package dev.frontier.industry.construction;
import com.google.gson.*;
import dev.frontier.industry.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;

public final class BuildingKits {
    private BuildingKits(){}
    public static List<ItemStack> cost(SiteType type){
        int iron=type==SiteType.SUBSTATION?6:type==SiteType.POWER_STATION?24:type==SiteType.VEHICLE_FACTORY||type==SiteType.MISSILE_FACTORY?32:16;
        int copper=type==SiteType.SUBSTATION?4:type==SiteType.MISSILE_FACTORY?12:8;
        int redstone=type==SiteType.SUBSTATION?2:type==SiteType.RESEARCH_CENTER||type==SiteType.MISSILE_FACTORY||type==SiteType.VEHICLE_FACTORY?8:4;
        var list=new ArrayList<>(List.of(new ItemStack(Items.IRON_INGOT,iron),new ItemStack(Items.COPPER_INGOT,copper),new ItemStack(Items.REDSTONE,redstone)));
        if(type==SiteType.RESEARCH_CENTER)list.add(new ItemStack(Items.EMERALD,4));return list;
    }
    public static JsonArray snapshot(){
        var rows=new JsonArray();for(var type:SiteType.values()){
            var row=new JsonObject();row.addProperty("id",type.name());row.addProperty("name",type.title());row.addProperty("seconds",IndustryConfig.buildingTicks(type)/20);
            row.addProperty("cost",cost(type).stream().map(s->s.getCount()+" "+s.getHoverName().getString()).collect(java.util.stream.Collectors.joining(" · ")));rows.add(row);
        }return rows;
    }
    public static String purchase(ServerPlayer player,String typeId){
        SiteType type;try{type=SiteType.valueOf(typeId);}catch(IllegalArgumentException e){return "Комплект не найден";}
        var next=new ArrayList<ItemStack>();for(var stack:player.getInventory().items)next.add(stack.copy());
        for(var cost:cost(type)){
            int remaining=cost.getCount();for(var stack:next)if(ItemStack.isSameItemSameTags(stack,cost)){
                int n=Math.min(remaining,stack.getCount());stack.shrink(n);remaining-=n;if(remaining==0)break;
            }
            if(remaining>0)return "Не хватает материалов: "+cost.getHoverName().getString();
        }
        int empty=-1;for(int i=0;i<next.size();i++)if(next.get(i).isEmpty()){empty=i;break;}
        if(empty<0)return "Освободи ячейку для строительного комплекта";
        next.set(empty,new ItemStack(IndustryContent.deployer(type)));
        for(int i=0;i<next.size();i++)player.getInventory().items.set(i,next.get(i));
        player.getInventory().setChanged();player.containerMenu.broadcastChanges();return "Комплект готов: "+type.title()+". Возьми его в руку и выбери площадку.";
    }
}
