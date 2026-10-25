package dev.frontier.nations.mixin.client;

import dev.frontier.nations.map.geometry.MapProjection;
import dev.frontier.nations.map.xaero.XaeroCountryLayer;
import dev.frontier.nations.map.xaero.XaeroMapAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.animation.SlowingAnimation;
import xaero.map.gui.GuiMap;

/** Pinned Xaero 1.47.0: render after terrain and before its widgets/tooltips. */
@Mixin(value=GuiMap.class,remap=false)
public abstract class XaeroMapMixin implements XaeroMapAccess {
    @Shadow private double cameraX,cameraZ,scale,screenScale;
    @Shadow private static boolean attachedCamera;
    @Shadow private int[] cameraDestination;
    @Shadow private SlowingAnimation cameraDestinationAnimX,cameraDestinationAnimZ;
    @Override public MapProjection frontier$projection(){
        var window=Minecraft.getInstance().getWindow();
        return MapProjection.physical(cameraX,cameraZ,scale,screenScale,window.getWidth(),window.getHeight());
    }
    @Override public void frontier$pan(double dx,double dy){
        if(scale<=0)return;
        attachedCamera=false;cameraDestination=null;cameraDestinationAnimX=null;cameraDestinationAnimZ=null;
        cameraX-=dx*screenScale/scale;cameraZ-=dy*screenScale/scale;
    }
    @Inject(method="m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",at=@At(value="INVOKE",target="Lxaero/map/gui/ScreenBase;m_88315_(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"),remap=false)
    private void frontier$mapLayer(GuiGraphics graphics,int mouseX,int mouseY,float delta,CallbackInfo ci){
        XaeroCountryLayer.render((GuiMap)(Object)this,graphics,mouseX,mouseY);
    }
}
