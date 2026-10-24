package dev.frontier.arcade.smoke;

import dev.frontier.arcade.PdaShop;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** Exercises the private atomic purchase core; real PDA authority is also checked through public action. */
final class ShopSmoke {
    private static int emeralds(ServerPlayer p) {return p.getInventory().items.stream().filter(s->s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();}
    private static String inventory(ServerPlayer p) {return p.getInventory().save(new net.minecraft.nbt.ListTag()).toString();}
    static void inspect(ServerPlayer p)throws Exception {
        var buy=PdaShop.class.getDeclaredMethod("purchase",ServerPlayer.class,String.class);buy.setAccessible(true);
        p.getInventory().clearContent();p.getInventory().items.set(0,new ItemStack(Items.EMERALD,10));
        String before=inventory(p);PdaShop.action(p,"shop","superbwarfare:a_10a",1);
        AirSmoke.check("shop requires held real unified PDA",before.equals(inventory(p)));
        buy.invoke(null,p,"superbwarfare:a_10a");
        AirSmoke.check("shop insufficient funds is atomic",before.equals(inventory(p)));
        buy.invoke(null,p,"superbwarfare:t_90a");
        AirSmoke.check("shop cannot buy blocked or unknown vehicle",before.equals(inventory(p)));
        for(int i=0;i<p.getInventory().items.size();i++)p.getInventory().items.set(i,new ItemStack(Items.STONE,64));
        p.getInventory().items.set(0,new ItemStack(Items.EMERALD,64));before=inventory(p);
        buy.invoke(null,p,"superbwarfare:small_shell_ap");
        AirSmoke.check("full inventory rejects entire purchase without charge",before.equals(inventory(p)));
        p.getInventory().items.set(1,ItemStack.EMPTY);buy.invoke(null,p,"superbwarfare:small_shell_ap");
        int shellCount=p.getInventory().items.stream().filter(s->String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())).equals("superbwarfare:small_shell_ap")).mapToInt(ItemStack::getCount).sum();
        AirSmoke.check("ammo purchase debits exact price and delivers finite pack",emeralds(p)==52&&shellCount==64);
        p.getInventory().clearContent();for(int i=0;i<6;i++)p.getInventory().items.set(i,new ItemStack(Items.EMERALD,64));
        buy.invoke(null,p,"superbwarfare:a_10a");
        var crates=p.getInventory().items.stream().filter(s->s.hasTag()&&s.getTag().getCompound("BlockEntityTag").getString("EntityType").equals("superbwarfare:a_10a")).toList();
        AirSmoke.check("aircraft purchase charges exact full price once",emeralds(p)==0&&crates.size()==1&&crates.get(0).getCount()==1);
        AirSmoke.check("shop delivers actual fueled native aircraft NBT",crates.size()==1&&crates.get(0).getTag().getCompound("BlockEntityTag").getCompound("Entity").getInt("Energy")>0);
        if(crates.size()==1) {
            var type=ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation("superbwarfare:a_10a"));
            var first=type.create(p.serverLevel());var second=type.create(p.serverLevel());
            var template=crates.get(0).getTag().getCompound("BlockEntityTag").getCompound("Entity");
            first.load(template.copy());second.load(template.copy());
            AirSmoke.check("copied factory aircraft templates deploy with unique UUIDs",!first.getUUID().equals(second.getUUID()));
            first.invalidateCaps();second.invalidateCaps();
        }
        var snapshot=PdaShop.snapshot(p);boolean schema=snapshot.get("available").getAsBoolean()&&!snapshot.getAsJsonArray("items").isEmpty();
        for(var item:snapshot.getAsJsonArray("items")) {var e=item.getAsJsonObject();schema&=e.has("id")&&e.has("name")&&e.has("cost")&&(e.has("item")||e.has("icon"));if(e.has("icon"))schema&=e.get("icon").getAsString().length()<=8192;}
        AirSmoke.check("PDA snapshot has compatible prices and native icons",schema);
        p.getInventory().clearContent();
    }
}
