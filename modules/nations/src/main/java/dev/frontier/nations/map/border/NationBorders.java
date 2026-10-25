package dev.frontier.nations.map.border;

import dev.frontier.nations.map.geometry.BorderPolygon;
import dev.frontier.nations.map.geometry.BorderPolygon.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Separate cosmetic store. Native Recruits claims and their formats are untouched. */
public final class NationBorders extends SavedData {
    public static final String KEY="frontier_nation_borders";
    public static final int MAX_ENTRIES=1024, MAX_ID_LENGTH=128;
    private final Map<String,BorderPolygon> borders=new LinkedHashMap<>();
    public static NationBorders get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(NationBorders::load,NationBorders::new,KEY);
    }
    public Map<String,BorderPolygon> snapshot(){return Map.copyOf(borders);}
    public void retainFactions(java.util.Set<String> ids){if(borders.keySet().removeIf(id->!ids.contains(id)))setDirty();}
    public boolean set(String faction,BorderPolygon polygon) {
        if(faction==null||faction.isBlank()||faction.length()>MAX_ID_LENGTH)return false;
        if(polygon==null){if(borders.remove(faction)!=null)setDirty();return true;}
        if(borders.size()>=MAX_ENTRIES&&!borders.containsKey(faction))return false;
        if(!polygon.equals(borders.put(faction,polygon)))setDirty();
        return true;
    }
    public static NationBorders load(CompoundTag root) {
        NationBorders data=new NationBorders();
        if(root.getInt("Version")!=1)return data;
        ListTag entries=root.getList("Borders",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(entries.size(),MAX_ENTRIES);i++) {
            CompoundTag entry=entries.getCompound(i);String faction=entry.getString("Faction");
            ListTag vertices=entry.getList("Points",Tag.TAG_COMPOUND);
            if(vertices.size()<3||vertices.size()>BorderPolygon.MAX_POINTS)continue;
            var points=new ArrayList<Point>();
            for(int j=0;j<vertices.size();j++) {CompoundTag p=vertices.getCompound(j);points.add(new Point(p.getDouble("X"),p.getDouble("Z")));}
            try{data.set(faction,new BorderPolygon(points));}catch(IllegalArgumentException ignored){/* Skip invalid entries without touching native data. */}
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag root) {
        ListTag entries=new ListTag();
        borders.forEach((faction,polygon)->{
            CompoundTag entry=new CompoundTag();entry.putString("Faction",faction);
            ListTag points=new ListTag();
            for(Point p:polygon.points()){CompoundTag vertex=new CompoundTag();vertex.putDouble("X",p.x());vertex.putDouble("Z",p.z());points.add(vertex);}
            entry.put("Points",points);entries.add(entry);
        });
        root.putInt("Version",1);root.put("Borders",entries);return root;
    }
}
