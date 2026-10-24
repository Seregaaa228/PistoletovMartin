package dev.frontier.industry.research;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Country knowledge and its single paid research job are saved in one transaction. */
public final class ResearchData extends SavedData {
    public final Map<String,Country> countries=new LinkedHashMap<>();
    /** A native country's current generation keeps one key through capital/leader/banner changes. */
    public final Map<String,String> nativeBindings=new LinkedHashMap<>();
    public static ResearchData get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(ResearchData::load,ResearchData::new,"frontier_research");}
    public Country country(String scope){return countries.computeIfAbsent(scope,k->new Country());}
    public String bind(String nativeId,String seed){
        String known=nativeBindings.get(nativeId);if(known!=null)return known;
        nativeBindings.put(nativeId,seed);setDirty();return seed;
    }
    public void created(String nativeId){nativeBindings.put(nativeId,"country:"+UUID.randomUUID());setDirty();}
    public void disbanded(String nativeId){nativeBindings.remove(nativeId);setDirty();}
    public static final class Country {
        public final Set<String> completed=new LinkedHashSet<>();
        public String active="";
        public int progress,total,paidEmeralds,paidRedstone;
        public UUID centre;
    }
    public static ResearchData load(CompoundTag root){
        var result=new ResearchData();var entries=root.getList("Countries",Tag.TAG_COMPOUND);
        for(int n=0;n<Math.min(8192,entries.size());n++){
            var tag=entries.getCompound(n);String scope=tag.getString("Scope");if(scope.isBlank()||scope.length()>96)continue;
            var c=new Country();var done=tag.getList("Completed",Tag.TAG_STRING);
            for(int i=0;i<done.size();i++)if(Technology.byId(done.getString(i))!=null)c.completed.add(done.getString(i));
            c.active=tag.getString("Active");if(Technology.byId(c.active)==null)c.active="";
            c.total=Math.max(0,Math.min(720000,tag.getInt("Total")));c.progress=Math.max(0,Math.min(c.total,tag.getInt("Progress")));
            c.paidEmeralds=Math.max(0,tag.getInt("PaidEmeralds"));c.paidRedstone=Math.max(0,tag.getInt("PaidRedstone"));
            if(tag.hasUUID("Centre"))c.centre=tag.getUUID("Centre");result.countries.put(scope,c);
        }
        var bindings=root.getList("NativeBindings",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(8192,bindings.size());i++){
            var t=bindings.getCompound(i);String id=t.getString("NativeId"),scope=t.getString("Scope");
            if(!id.isBlank()&&id.length()<=256&&scope.length()<=96&&(scope.startsWith("country:")||scope.startsWith("native:")))result.nativeBindings.put(id,scope);
        }return result;
    }
    @Override public CompoundTag save(CompoundTag root){
        root.putInt("Version",2);var entries=new ListTag();
        countries.forEach((scope,c)->{
            var tag=new CompoundTag();tag.putString("Scope",scope);var done=new ListTag();for(String id:c.completed)done.add(StringTag.valueOf(id));tag.put("Completed",done);
            tag.putString("Active",c.active);tag.putInt("Progress",c.progress);tag.putInt("Total",c.total);
            tag.putInt("PaidEmeralds",c.paidEmeralds);tag.putInt("PaidRedstone",c.paidRedstone);if(c.centre!=null)tag.putUUID("Centre",c.centre);entries.add(tag);
        });root.put("Countries",entries);
        var bindings=new ListTag();nativeBindings.forEach((id,scope)->{var t=new CompoundTag();t.putString("NativeId",id);t.putString("Scope",scope);bindings.add(t);});root.put("NativeBindings",bindings);return root;
    }
}
