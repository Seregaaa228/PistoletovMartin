package dev.frontier.industry.research;

import com.google.gson.*;
import dev.frontier.industry.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact server-authored product identities. Unknown military items fail closed. */
public final class ResearchPolicy {
    private static final Set<String> MILITARY=Set.of("tacz","superbwarfare","uncomplicatedfpv","deepstrike","frontier_aviation","frontier_arcade_vehicles");
    private static final Map<String,String> PRODUCTS=new LinkedHashMap<>();
    static { reload(); }
    private ResearchPolicy(){}
    public static synchronized void reload(){
        PRODUCTS.clear();
        try(var input=ResearchPolicy.class.getResourceAsStream("/research-products.json")){
            if(input==null)throw new IOException("Missing research product policy");
            merge(JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject());
            var override=net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("frontier-industry-research-products.json");
            if(Files.isRegularFile(override))try(var reader=Files.newBufferedReader(override)){merge(JsonParser.parseReader(reader).getAsJsonObject());}
        }catch(Exception e){com.mojang.logging.LogUtils.getLogger().error("Research policy could not load; military products remain locked",e);}
    }
    private static void merge(JsonObject object){
        for(var e:object.entrySet())if(e.getKey().length()<=128&&e.getValue().isJsonPrimitive()){
            String value=e.getValue().getAsString();if(value.isEmpty()||Technology.byId(value)!=null)PRODUCTS.put(e.getKey(),value);
        }
    }
    public static String requirement(String identity){
        if(identity==null||identity.isBlank())return "-";
        String known=PRODUCTS.get(identity);if(known!=null)return known;
        String id=identity.startsWith("ammo/")?identity.substring(5):identity;
        int colon=id.indexOf(':');String namespace=colon<0?"":id.substring(0,colon);
        return namespace.equals("minecraft")?"":"-";
    }
    public static String identity(ItemStack stack){
        if(stack==null||stack.isEmpty())return "minecraft:air";
        var tag=stack.getTag();
        if(tag!=null){
            if(tag.contains("GunId",8))return tag.getString("GunId");
            if(tag.contains("AmmoId",8))return "ammo/"+tag.getString("AmmoId");
            var block=tag.getCompound("BlockEntityTag");if(!block.getString("EntityType").isBlank())return block.getString("EntityType");
        }
        var key=ForgeRegistries.ITEMS.getKey(stack.getItem());return key==null?"":key.toString();
    }
    public static boolean military(ItemStack stack){
        var key=ForgeRegistries.ITEMS.getKey(stack.getItem());if(key==null)return false;
        return MILITARY.contains(key.getNamespace())||PRODUCTS.containsKey(identity(stack));
    }
    public static String denialId(MinecraftServer server,UUID owner,String id){
        String tech=requirement(id);
        if(tech.isEmpty())return "";
        if(tech.equals("-"))return "Не настроено для производства";
        return Research.unlocked(server,owner,tech)?"":"Нужно исследование: "+Technology.byId(tech).title;
    }
    public static String denial(ServerPlayer player,ItemStack stack){
        if(Research.admin(player))return "";
        if(!military(stack))return "";
        return denialId(player.server,player.getUUID(),identity(stack));
    }
    public static String recipeDenial(MinecraftServer server,UUID owner,IndustryCatalog.Recipe recipe){
        if(Research.admin(server.getPlayerList().getPlayer(owner)))return "";
        String id=PRODUCTS.containsKey(recipe.id())?recipe.id():identity(recipe.output());
        if(!military(recipe.output())&&recipe.id().startsWith("minecraft:"))return "";
        return denialId(server,owner,id);
    }
    public static boolean deployment(ItemStack stack){
        if(!military(stack))return false;
        String id=identity(stack),name=id.substring(id.indexOf(':')+1);
        if(Set.of("dev.sbw.deepstrike.game.VehicleDeployItem","dev.sbw.deepstrike.game.AirDefenseSpawnItem").contains(stack.getItem().getClass().getName()))return true;
        if(stack.getItem() instanceof BlockItem)return !requirement(id).isEmpty();
        return Set.of("su25","f16","j10","geranium2","geranium_2","shahed","fpv","mavic","recon_plane","relay_plane").contains(name)
            ||name.endsWith("_deployer")||stack.getItem() instanceof SpawnEggItem||name.contains("drone")||name.contains("shahed")||name.contains("mavic")||name.contains("geranium")||name.contains("relay")||name.contains("reb")||name.endsWith("_launcher")||name.endsWith("_platform")||name.contains("patriot")||name.equals("iron_dome")||stack.getItem().getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("deployer");
    }
    /** Removes alternative fabrication, including TaCZ table recipes, with no player/owner ambiguity. */
    public static int filterRecipes(MinecraftServer server){
        var keep=new ArrayList<net.minecraft.world.item.crafting.Recipe<?>>();int removed=0;
        for(var recipe:server.getRecipeManager().getRecipes()){
            ItemStack out;
            try{out=recipe.getResultItem(server.registryAccess());}catch(RuntimeException e){keep.add(recipe);continue;}
            if(!out.isEmpty()&&military(out)&&!requirement(identity(out)).isEmpty())removed++;else keep.add(recipe);
        }
        if(removed>0)server.getRecipeManager().replaceRecipes(keep);
        return removed;
    }
}
