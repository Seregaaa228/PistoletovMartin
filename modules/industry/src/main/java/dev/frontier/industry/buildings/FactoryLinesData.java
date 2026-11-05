package dev.frontier.industry.buildings;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Only the independent line switches; existing paid orders/research/inventories stay native. */
public final class FactoryLinesData extends SavedData {
    private final Map<UUID,Integer> masks=new HashMap<>();
    public static FactoryLinesData get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(FactoryLinesData::load,FactoryLinesData::new,"frontier_factory_lines");}
    public boolean enabled(UUID site,int line){return line>=0&&line<3&&(masks.getOrDefault(site,7)&(1<<line))!=0;}
    public void toggle(UUID site,int line){if(line<0||line>2)return;masks.put(site,masks.getOrDefault(site,7)^(1<<line));setDirty();}
    public static FactoryLinesData load(CompoundTag root){var d=new FactoryLinesData();var list=root.getList("Lines",Tag.TAG_COMPOUND);for(int i=0;i<Math.min(2048,list.size());i++){var t=list.getCompound(i);if(t.hasUUID("Site"))d.masks.put(t.getUUID("Site"),t.getInt("Mask")&7);}return d;}
    @Override public CompoundTag save(CompoundTag root){var list=new ListTag();masks.forEach((id,mask)->{var t=new CompoundTag();t.putUUID("Site",id);t.putInt("Mask",mask);list.add(t);});root.put("Lines",list);return root;}
}
