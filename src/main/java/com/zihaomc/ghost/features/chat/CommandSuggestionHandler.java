package com.zihaomc.ghost.features.chat;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CommandSuggestionHandler {

    private static String lastCommand = null;
    private static final Set<Integer> processedMessageHashes = new HashSet<>();
    
    private static Field drawnChatLinesField = null;
    private static Field chatComponentField = null;

    public CommandSuggestionHandler() {
        initializeReflectionFields();
    }

    private void initializeReflectionFields() {
        if (drawnChatLinesField == null) { 
            try { 
                drawnChatLinesField = ReflectionHelper.findField(GuiNewChat.class, "field_146253_i", "drawnChatLines"); 
                drawnChatLinesField.setAccessible(true); 
            } catch (Exception e) { 
                LogUtil.error("log.error.reflection.drawnChatLines"); 
            } 
        }
        if (chatComponentField == null) { 
            try { 
                chatComponentField = ReflectionHelper.findField(ChatLine.class, "field_74541_b", "chatComponent", "lineString"); 
                chatComponentField.setAccessible(true); 
            } catch (Exception e) { 
                LogUtil.error("log.error.reflection.chatComponent"); 
            } 
        }
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!GhostConfig.ChatFeatures.enableChatSuggestions) return;
        try {
            StringBuilder commandBuilder = new StringBuilder("/").append(event.command.getCommandName());
            if (event.parameters != null) {
                for (String param : event.parameters) commandBuilder.append(" ").append(param);
            }
            lastCommand = commandBuilder.toString();
        } catch (Exception e) {
            lastCommand = null;
        }
    }

    @SubscribeEvent
    public void onClientChatReceived(ClientChatReceivedEvent event) {
        if (event.type == 2 || !GhostConfig.ChatFeatures.enableChatSuggestions) return;
        
        int messageHash = System.identityHashCode(event.message);
        if (processedMessageHashes.contains(messageHash)) return;

        if (event.message instanceof ChatComponentTranslation) {
            ChatComponentTranslation translation = (ChatComponentTranslation) event.message;
            String key = translation.getKey();
            if ("commands.generic.unknownCommand".equals(key) || "commands.generic.notFound".equals(key)) {
                Minecraft mc = Minecraft.getMinecraft();
                List<String> sentMessages = mc.ingameGUI.getChatGUI().getSentMessages();
                if (sentMessages != null && !sentMessages.isEmpty()) {
                    String lastSentMsg = sentMessages.get(sentMessages.size() - 1);
                    if (lastSentMsg != null && lastSentMsg.startsWith("/")) {
                        appendSuggestButton(event.message, lastSentMsg);
                        processedMessageHashes.add(messageHash);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();

        if (!GhostConfig.ChatFeatures.enableChatSuggestions || lastCommand == null) return;
        if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null || drawnChatLinesField == null || chatComponentField == null) return;
        
        processTickFallback(mc, lastCommand);
    }

    private void processTickFallback(Minecraft mc, String commandToProcess) {
        GuiNewChat chatGUI = mc.ingameGUI.getChatGUI();
        try {
            @SuppressWarnings("unchecked")
            final List<ChatLine> drawnChatLines = (List<ChatLine>) drawnChatLinesField.get(chatGUI);
            if (drawnChatLines == null || drawnChatLines.isEmpty()) return;
            
            boolean commandProcessedThisTick = false;
            for (int i = drawnChatLines.size() - 1; i >= 0; i--) {
                ChatLine currentChatLine = drawnChatLines.get(i);
                IChatComponent currentComponent = (IChatComponent) chatComponentField.get(currentChatLine);
                int currentComponentHash = System.identityHashCode(currentComponent);
                
                if (processedMessageHashes.contains(currentComponentHash)) continue;
                
                if (currentComponent instanceof ChatComponentTranslation) {
                    String key = ((ChatComponentTranslation) currentComponent).getKey();
                    if ("commands.generic.unknownCommand".equals(key) || "commands.generic.notFound".equals(key)) continue;
                }
                
                ChatLine targetBottomLine = currentChatLine;
                int bottomIndex = i;
                
                for (int j = i - 1; j >= 0; j--) {
                    ChatLine previousLine = drawnChatLines.get(j);
                    IChatComponent previousComponent = (IChatComponent) chatComponentField.get(previousLine);
                    int previousHash = System.identityHashCode(previousComponent);
                    boolean prevIsError = false;
                    if (previousComponent instanceof ChatComponentTranslation) {
                        String prevKey = ((ChatComponentTranslation) previousComponent).getKey();
                        prevIsError = "commands.generic.unknownCommand".equals(prevKey) || "commands.generic.notFound".equals(prevKey);
                    }
                    if (processedMessageHashes.contains(previousHash) || prevIsError) break;
                    else {
                        targetBottomLine = previousLine;
                        bottomIndex = j;
                    }
                }
                
                IChatComponent bottomComponent = (IChatComponent) chatComponentField.get(targetBottomLine);
                boolean alreadyHasButton = bottomComponent.getSiblings().stream().anyMatch(s -> s instanceof ChatComponentText && ((ChatComponentText) s).getUnformattedText().contains(LangUtil.translate("ghostblock.commands.suggest.text", "\u21A9")));
                
                if (!alreadyHasButton) {
                    if (appendSuggestButton(bottomComponent, commandToProcess)) {
                        Set<Integer> hashesToMark = new HashSet<>();
                        for (int k = i; k >= bottomIndex; k--) hashesToMark.add(System.identityHashCode(chatComponentField.get(drawnChatLines.get(k))));
                        processedMessageHashes.addAll(hashesToMark);
                        commandProcessedThisTick = true;
                        break;
                    }
                } else {
                    Set<Integer> hashesToMark = new HashSet<>();
                    for (int k = i; k >= bottomIndex; k--) hashesToMark.add(System.identityHashCode(chatComponentField.get(drawnChatLines.get(k))));
                    processedMessageHashes.addAll(hashesToMark);
                }
            }
            if (commandProcessedThisTick) lastCommand = null;
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.tick.fallback.failed", e);
            lastCommand = null;
        }
    }

    private boolean appendSuggestButton(IChatComponent targetComponent, String commandToSuggest) {
        if (targetComponent == null || commandToSuggest == null) return false;
        try {
            String suggestText = LangUtil.translate("ghostblock.commands.suggest.text", " \u21A9");
            ChatComponentText suggestComponent = new ChatComponentText(suggestText);
            ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, commandToSuggest);
            IChatComponent hoverComponent = new ChatComponentText(LangUtil.translate("ghostblock.commands.suggest.hovertext", commandToSuggest))
                    .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
            HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent);
            ChatStyle buttonStyle = new ChatStyle()
                    .setColor(EnumChatFormatting.AQUA)
                    .setBold(true)
                    .setChatClickEvent(clickEvent)
                    .setChatHoverEvent(hoverEvent);
            suggestComponent.setChatStyle(buttonStyle);
            targetComponent.appendSibling(suggestComponent);
            return true;
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.chat.attachSuggestButton.failed", e);
            return false;
        }
    }
}