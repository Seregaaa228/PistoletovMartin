package dev.frontier.nations.map.border;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

/** Matches the two pinned PDA item IDs accepted by IndustryContent.isTablet, on either side. */
public final class CountryEditorAccess {
    private static final ResourceLocation INDUSTRY_PDA=new ResourceLocation("frontier_industry","dispatch_tablet");
    private static final ResourceLocation NATIVE_PDA=new ResourceLocation("deepstrike","jagm_camera_tablet");
    private CountryEditorAccess(){}
    public static boolean hasPda(Player player){
        return player!=null&&player.isAlive()&&!player.isSpectator()&&Level.OVERWORLD.equals(player.level().dimension())
            &&(isPda(player.getMainHandItem())||isPda(player.getOffhandItem()));
    }
    private static boolean isPda(ItemStack item){
        if(item.isEmpty())return false;
        ResourceLocation id=ForgeRegistries.ITEMS.getKey(item.getItem());
        return INDUSTRY_PDA.equals(id)||NATIVE_PDA.equals(id);
    }
}
