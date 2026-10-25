package dev.frontier.nations.mixin.client;

import dev.frontier.nations.flag.TabFlags;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla row/column layout, sorting, scores, skin, ping and narration. */
@Mixin(value=PlayerTabOverlay.class,remap=false)
public abstract class PlayerTabFlagsMixin {
    @Redirect(method="m_280406_",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/components/PlayerTabOverlay;m_94549_(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"),remap=false)
    private Component frontier$reserveFlag(PlayerTabOverlay overlay,PlayerInfo player){return TabFlags.reserve(player,overlay.getNameForDisplay(player));}
    @Inject(method="m_280020_(Lnet/minecraft/client/gui/GuiGraphics;IIILnet/minecraft/client/multiplayer/PlayerInfo;)V",at=@At("HEAD"),remap=false)
    private void frontier$flag(GuiGraphics graphics,int width,int x,int y,PlayerInfo player,CallbackInfo ci){TabFlags.draw(graphics,x,y,player);}
}
