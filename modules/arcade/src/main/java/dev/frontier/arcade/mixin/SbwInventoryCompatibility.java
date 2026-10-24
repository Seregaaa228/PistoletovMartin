package dev.frontier.arcade.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Superb Recruits adds Container; newer SBW exposes only its item handler API. */
@Mixin(targets="com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",remap=false)
public abstract class SbwInventoryCompatibility implements Container {
    @Shadow(prefix="sbw$",remap=false) public abstract int sbw$getContainerSize();
    @Shadow(prefix="sbw$",remap=false) public abstract ItemStack sbw$getItem(int slot);
    @Shadow(prefix="sbw$",remap=false) public abstract ItemStack sbw$removeItem(int slot,int count);
    @Shadow(prefix="sbw$",remap=false) public abstract void sbw$setItem(int slot,ItemStack stack);
    @Shadow(prefix="sbw$",remap=false) public abstract void sbw$setChanged();
    @Shadow(prefix="sbw$",remap=false) public abstract void sbw$clearContent();
    @Shadow(prefix="sbw$",remap=false) public abstract int sbw$getMaxStackSize();
    @Shadow(prefix="sbw$",remap=false) public abstract boolean sbw$canPlaceItem(int slot,ItemStack stack);

    @Override public int getContainerSize(){return sbw$getContainerSize();}
    @Override public boolean isEmpty(){for(int i=0;i<getContainerSize();i++)if(!getItem(i).isEmpty())return false;return true;}
    @Override public ItemStack getItem(int slot){return sbw$getItem(slot);}
    @Override public ItemStack removeItem(int slot,int count){return sbw$removeItem(slot,count);}
    @Override public ItemStack removeItemNoUpdate(int slot){return sbw$removeItem(slot,Integer.MAX_VALUE);}
    @Override public void setItem(int slot,ItemStack stack){sbw$setItem(slot,stack);}
    @Override public void setChanged(){sbw$setChanged();}
    @Override public boolean stillValid(Player player){Entity self=(Entity)(Object)this;return self.isAlive()&&player.distanceToSqr(self)<=64;}
    @Override public void clearContent(){sbw$clearContent();}
    @Override public int getMaxStackSize(){return sbw$getMaxStackSize();}
    @Override public boolean canPlaceItem(int slot,ItemStack stack){return sbw$canPlaceItem(slot,stack);}
}
