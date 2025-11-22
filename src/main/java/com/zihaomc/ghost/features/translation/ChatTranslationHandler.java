package com.zihaomc.ghost.features.translation;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;

public class ChatTranslationHandler {

    private static final Set<Integer> processedMessageHashes = new HashSet<>();

    @SubscribeEvent
    public void onClientChatReceived(ClientChatReceivedEvent event) {
        if (event.type == 2) return; // 忽略 Action Bar 消息
        
        // 如果两个功能都没开，直接返回，节省性能
        if (!GhostConfig.Translation.enableAutomaticTranslation && !GhostConfig.Translation.enableChatTranslation) {
            return;
        }

        int messageHash = System.identityHashCode(event.message);
        if (processedMessageHashes.contains(messageHash)) return;

        String unformattedText = event.message.getUnformattedText();
        String translatedPrefix = LangUtil.translate("ghost.generic.prefix.translation");

        // 检查消息是否已经是我们自己的翻译结果，如果是则不处理
        if (unformattedText.startsWith(translatedPrefix)) {
            processedMessageHashes.add(messageHash);
            return;
        }

        if ((event.type == 0 || event.type == 1) && !unformattedText.trim().isEmpty()) {
            if (GhostConfig.Translation.enableAutomaticTranslation) {
                // 自动翻译模式：自动发起请求并显示结果
                triggerAutomaticChatTranslation(unformattedText);
                processedMessageHashes.add(messageHash);
            } else if (GhostConfig.Translation.enableChatTranslation) {
                // 手动翻译模式：添加 [翻译] 按钮
                appendTranslateButton(event.message, unformattedText);
                processedMessageHashes.add(messageHash);
            }
        }
    }

    private void triggerAutomaticChatTranslation(String originalText) {
        String currentProvider = GhostConfig.Translation.translationProvider.toUpperCase();
        
        TranslationUtil.runAsynchronously(() -> {
            final String result = TranslationUtil.translate(originalText, currentProvider);
            
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().thePlayer == null) return;
            
                if (!result.startsWith(TranslationUtil.ERROR_PREFIX)) {
                    ChatComponentText resultMessage = new ChatComponentText("");
                    ChatComponentText resultPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.translation") + " ");
                    resultPrefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
                    
                    ChatComponentText providerTag = new ChatComponentText(EnumChatFormatting.YELLOW + "[" + currentProvider + "] " + EnumChatFormatting.RESET);
                    ChatComponentText resultContent = new ChatComponentText(result);
                    
                    resultMessage.appendSibling(resultPrefix)
                                 .appendSibling(providerTag)
                                 .appendSibling(resultContent);
                                 
                    resultMessage.appendSibling(CommandHelper.createProviderSwitchButtons(originalText, currentProvider));
                    
                    Minecraft.getMinecraft().thePlayer.addChatMessage(resultMessage);
                }
            });
        });
    }

    private void appendTranslateButton(IChatComponent targetComponent, String textToTranslate) {
        try {
            String buttonText = String.format(" %s", LangUtil.translate("ghostblock.chat.button.translate"));
            ChatComponentText buttonComponent = new ChatComponentText(buttonText);
            
            String command = "/gtranslate \"" + textToTranslate.replace("\"", "\\\"") + "\"";
            
            ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
            String hoverText = LangUtil.translate("ghostblock.chat.button.translate.hover");
            IChatComponent hoverComponent = new ChatComponentText(hoverText).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
            HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent);
            
            ChatStyle buttonStyle = new ChatStyle()
                    .setColor(EnumChatFormatting.GREEN)
                    .setChatClickEvent(clickEvent)
                    .setChatHoverEvent(hoverEvent);
            buttonComponent.setChatStyle(buttonStyle);
            targetComponent.appendSibling(buttonComponent);
        } catch (Exception e) {
            LogUtil.error("log.error.chat.attachButton.failed", e.getMessage());
        }
    }
}