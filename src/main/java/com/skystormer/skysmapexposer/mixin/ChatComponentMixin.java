package com.skystormer.skysmapexposer.mixin;

import com.skystormer.skysmapexposer.ChatCoords;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Positions written in chat as JourneyMap writes them become clickable for Xaero's players
 * ({@link ChatCoords#decorate}), as JourneyMap makes them for its own: every chat line passes here on
 * its way in. Optional (require = 0): without it they stay plain text.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"), argsOnly = true, require = 0)
    private Component skysmapexposer$clickableCoords(Component message) {
        return ChatCoords.decorate(message);
    }
}
