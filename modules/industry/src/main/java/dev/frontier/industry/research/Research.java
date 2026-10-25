package dev.frontier.industry.research;

import com.diego.recruitsvillages.data.LivingVillagesSavedData;
import com.talhanation.recruits.FactionEvents;
import dev.frontier.industry.*;
import dev.frontier.industry.engine.*;
import dev.frontier.industry.engine.IndustryData.Site;
import com.google.gson.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;

public final class Research {
    private Research(){}
    public static boolean admin(ServerPlayer p){return p!=null&&p.isCreative()&&p.hasPermissions(2);}
    /** Uses the native persistent membership roster, including offline members; never a saved site team. */
    public static String scope(MinecraftServer server,UUID owner){
        var factions=FactionEvents.recruitsFactionManager;if(factions==null)return "";
        String nativeId=null;
        for(var f:factions.getFactions())if(owner.equals(f.getTeamLeaderUUID())||f.getMembers().stream().anyMatch(m->owner.equals(m.getUUID()))){nativeId=f.getStringID();break;}
        if(nativeId==null)return "player:"+owner;
        var data=LivingVillagesSavedData.get(server);if(data==null)return "";
        var research=ResearchData.get(server);String bound=research.nativeBindings.get(nativeId);if(bound!=null)return bound;
        String legacy="native:"+UUID.nameUUIDFromBytes(nativeId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(research.countries.containsKey(legacy))return research.bind(nativeId,legacy);
        for(var f:data.getFactions())if(nativeId.equals(f.getStringID())) {
            var capital=data.getCapital(f);if(capital!=null)return research.bind(nativeId,"country:"+capital.getId());
        }
        // A settlement can be founded later or lose its capital without replacing this research identity.
        return research.bind(nativeId,"country:"+UUID.randomUUID());
    }
    public static String label(MinecraftServer server,UUID owner){
        String scope=scope(server,owner);if(scope.startsWith("player:"))return "Личные исследования";
        if(scope.isEmpty())return "Реестр стран недоступен";
        for(var f:FactionEvents.recruitsFactionManager.getFactions())if(owner.equals(f.getTeamLeaderUUID())||f.getMembers().stream().anyMatch(m->owner.equals(m.getUUID())))return f.getTeamDisplayName();
        return "Технологии страны";
    }
    public static boolean unlocked(MinecraftServer server,UUID owner,String technology){
        if(technology==null||technology.equals("-"))return false;
        if(technology.isEmpty())return true;
        String scope=scope(server,owner);return !scope.isEmpty()&&ResearchData.get(server).country(scope).completed.contains(technology);
    }
    public static List<ItemStack> cost(Technology t){
        List<ItemStack> result=new ArrayList<>();
        addCost(result,Items.EMERALD,IndustryConfig.emeralds(t));addCost(result,Items.REDSTONE,IndustryConfig.redstone(t));return result;
    }
    private static void addCost(List<ItemStack> result,Item item,int count){while(count>0){int n=Math.min(64,count);result.add(new ItemStack(item,n));count-=n;}}
    public static void action(ServerPlayer player,Site site,String action,String arg){
        if(site.type!=SiteType.RESEARCH_CENTER||site.constructing||!site.owner.equals(player.getUUID())){IndustryEngine.tell(player,"Нужен свой построенный исследовательский центр");return;}
        if(action.equals("research_staff")){IndustryEngine.staff(player,IndustryData.get(player.server),site,"scientist");return;}
        Technology t=Technology.byId(arg);if(t==null){IndustryEngine.tell(player,"Выбери исследование");return;}
        String key=scope(player.server,site.owner);if(key.isEmpty()){IndustryEngine.tell(player,"Реестр стран ещё не загружен");return;}
        var data=ResearchData.get(player.server);var c=data.country(key);
        if(action.equals("research_supply")) {
            List<ItemStack> sources=new ArrayList<>();for(int i=0;i<player.getInventory().getContainerSize();i++)sources.add(player.getInventory().getItem(i));
            int moved=StockTransactions.supplyMissingInputs(site.inventory,sources,cost(t),1);player.getInventory().setChanged();IndustryEngine.tell(player,"Добавлено материалов: "+moved);return;
        }
        if(!action.equals("research_start"))return;
        if(!c.active.isEmpty()){IndustryEngine.tell(player,"В стране уже идёт одно исследование: "+Technology.byId(c.active).title);return;}
        if(!ResearchRules.available(t,c.completed)){IndustryEngine.tell(player,c.completed.contains(t.id)?"Технология уже изучена":"Сначала изучи предыдущие технологии");return;}
        if(!site.functional()||!site.powered||IndustryEngine.crew(player.server,IndustryData.get(player.server),site,"scientist").isEmpty()){
            IndustryEngine.tell(player,"Нужны исправный центр, питание и живой учёный рядом");return;
        }
        if(!StockTransactions.takeInputs(site.inventory,cost(t))){IndustryEngine.tell(player,"Не хватает материалов во входных слотах центра");return;}
        c.active=t.id;c.progress=0;c.total=IndustryConfig.researchTicks(t);c.centre=site.id;
        c.paidEmeralds=IndustryConfig.emeralds(t);c.paidRedstone=IndustryConfig.redstone(t);data.setDirty();
        IndustryEngine.tell(player,"Исследование: "+t.title+". Материалы оплачены один раз.");
    }
    public static void tick(MinecraftServer server){
        var industrial=IndustryData.get(server);var data=ResearchData.get(server);Set<String> advanced=new HashSet<>();
        for(Site site:industrial.sites.values())if(site.type==SiteType.RESEARCH_CENTER&&!site.constructing){
            String key=scope(server,site.owner);if(key.isEmpty()){site.status="Исследования: реестр стран недоступен";continue;}
            var c=data.country(key);
            if(c.active.isEmpty()){if(site.functional())site.status="Выбери исследование в планшете";continue;}
            if(!site.functional()||!site.powered||site.energy<16){site.status="Исследование: пауза — нет питания или центр повреждён";continue;}
            if(IndustryEngine.crew(server,industrial,site,"scientist").isEmpty()){site.status="Исследование: нужен живой учёный рядом";continue;}
            if(!advanced.add(key)){site.status="Исследование выполняет другой центр страны";continue;}
            if(!dev.frontier.industry.energy.IndustryEnergy.consume(industrial,site,16))continue;
            c.centre=site.id;c.progress=ResearchRules.advance(c.progress,c.total,true);data.setDirty();
            var t=Technology.byId(c.active);site.status="Исследование: "+t.title+" · "+100*c.progress/Math.max(1,c.total)+"%";
            if(c.progress>=c.total){c.completed.add(c.active);c.active="";c.progress=0;c.total=0;c.centre=null;data.setDirty();site.status="Технология изучена: "+t.title;}
        }
    }
    public static JsonObject snapshot(ServerPlayer p,Site site){
        var j=new JsonObject();String key=scope(p.server,site.owner);j.addProperty("scope",key);j.addProperty("country",label(p.server,site.owner));
        if(key.isEmpty())return j;
        var c=ResearchData.get(p.server).country(key);j.addProperty("active",c.active);j.addProperty("progress",c.progress);j.addProperty("total",c.total);
        j.addProperty("scientists",IndustryEngine.crew(p.server,IndustryData.get(p.server),site,"scientist").size());
        var rows=new JsonArray();
        for(var t:Technology.values()){
            var row=new JsonObject();row.addProperty("id",t.id);row.addProperty("name",t.title);row.addProperty("seconds",IndustryConfig.researchTicks(t)/20);
            row.addProperty("emeralds",IndustryConfig.emeralds(t));row.addProperty("redstone",IndustryConfig.redstone(t));
            row.addProperty("complete",c.completed.contains(t.id));row.addProperty("available",ResearchRules.available(t,c.completed));
            String requirements=t.prerequisites.stream().map(Technology::byId).map(a->a.title).collect(java.util.stream.Collectors.joining(", "));
            row.addProperty("requires",requirements);rows.add(row);
        }j.add("technologies",rows);return j;
    }
}
