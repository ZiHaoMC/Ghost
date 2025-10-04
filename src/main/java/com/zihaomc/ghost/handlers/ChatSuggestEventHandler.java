package com.zihaomc.ghost.handlers;

// ---- Minecraft Client 相关导入 ----
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiScreen;

// ---- Minecraft 实用工具类导入 ----
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.*;

// ---- Forge 事件系统相关导入 ----
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.GuiScreenEvent.MouseInputEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

// ---- Java 反射与集合类导入 ----
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

// ---- LWJGL 输入库导入 ----
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

// ---- 本项目工具类导入 ----
import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;

/**
 * 处理与聊天建议相关的事件，包括：
 * 1. 在聊天消息（尤其是错误消息和成功命令反馈）后添加 "建议命令" 按钮。
 * 2. 实现 Shift + 箭头/滚轮 在聊天输入框中滚动发送历史。
 * 3. 在其他玩家的聊天消息后添加 "翻译" 按钮。
 */
public class ChatSuggestEventHandler {

    // ==================
    // === 成员变量 ===
    // ==================
    private static String lastCommand = null;
    private static final Set<Integer> processedMessageHashes = new HashSet<>();
    private static Field drawnChatLinesField = null;
    private static Field chatComponentField = null;
    private static Field updateCounterField = null;
    private static Field chatInputField = null;
    private static int chatHistoryIndex = -1;
    private static String originalChatText = null;
    private static GuiChat activeGuiChatInstance = null;

    // ==================
    // === 构造与初始化 ===
    // ==================
    public ChatSuggestEventHandler() {
        System.out.println("[GhostBlock-Suggest DEBUG] ChatSuggestEventHandler 实例已创建。");
        initializeReflectionFields();
    }

    private void initializeReflectionFields() {
        if (drawnChatLinesField == null) { try { drawnChatLinesField = ReflectionHelper.findField(GuiNewChat.class, "field_146253_i", "drawnChatLines"); drawnChatLinesField.setAccessible(true); } catch (Exception e) { System.err.println("[Ghost-Suggest ERROR] 无法找到 drawnChatLines 字段！"); } }
        if (chatComponentField == null) { try { chatComponentField = ReflectionHelper.findField(ChatLine.class, "field_74541_b", "chatComponent", "lineString"); chatComponentField.setAccessible(true); } catch (Exception e) { System.err.println("[Ghost-Suggest ERROR] 无法找到 chatComponent 字段！"); } }
        if (updateCounterField == null) { try { updateCounterField = ReflectionHelper.findField(ChatLine.class, "field_74549_e", "updateCounter", "field_146250_d"); updateCounterField.setAccessible(true); } catch (Exception e) { System.err.println("[Ghost-Suggest WARN] 无法找到 updateCounter 字段，将使用Fallback逻辑。"); updateCounterField = null; } }
        if (chatInputField == null) { try { chatInputField = ReflectionHelper.findField(GuiChat.class, "field_146415_a", "inputField"); chatInputField.setAccessible(true); } catch (Exception e) { System.err.println("[Ghost-Suggest ERROR] 无法找到 inputField 字段！"); } }
    }

    // ========================
    // === 事件处理方法 ===
    // ========================

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!GhostConfig.enableChatSuggestions) return;
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
        if (event.type == 2) return;
        int messageHash = System.identityHashCode(event.message);
        if (processedMessageHashes.contains(messageHash)) return;

        // --- 命令建议逻辑 (优先级高) ---
        if (GhostConfig.enableChatSuggestions && event.message instanceof ChatComponentTranslation) {
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
                        return;
                    }
                }
            }
        }

        // --- 翻译逻辑 ---
        String unformattedText = event.message.getUnformattedText();
        String translatedPrefix = LangUtil.translate("ghost.generic.prefix.translation");

        // 检查消息是否已经是我们自己的翻译结果，如果是则不处理
        if (unformattedText.startsWith(translatedPrefix)) {
            processedMessageHashes.add(messageHash);
            return;
        }

        if ((event.type == 0 || event.type == 1) && !unformattedText.trim().isEmpty()) {
            if (GhostConfig.enableAutomaticTranslation) {
                // 自动翻译模式
                triggerAutomaticChatTranslation(unformattedText);
                processedMessageHashes.add(messageHash);
            } else if (GhostConfig.enableChatTranslation) {
                // 手动翻译模式（添加按钮）
                appendTranslateButton(event.message, unformattedText);
                processedMessageHashes.add(messageHash);
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();

        if (activeGuiChatInstance != null && mc.currentScreen != activeGuiChatInstance) {
            activeGuiChatInstance = null;
            chatHistoryIndex = -1;
            originalChatText = null;
        }

        if (!GhostConfig.enableChatSuggestions || lastCommand == null) return;
        if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() == null || drawnChatLinesField == null || chatComponentField == null) return;
        
        processTickFallbackV2(mc, lastCommand);
    }

    private void processTickFallbackV2(Minecraft mc, String commandToProcess) {
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
            e.printStackTrace();
            lastCommand = null;
        }
    }

    @SubscribeEvent
    public void onGuiKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!GhostConfig.enableCommandHistoryScroll || chatInputField == null) return;
        if (!(event.gui instanceof GuiChat)) {
            if (activeGuiChatInstance != null) {
                activeGuiChatInstance = null;
                chatHistoryIndex = -1;
                originalChatText = null;
            }
            return;
        }
        GuiChat currentChatGui = (GuiChat) event.gui;
        if (activeGuiChatInstance == null || activeGuiChatInstance != currentChatGui) {
            activeGuiChatInstance = currentChatGui;
            chatHistoryIndex = -1;
            originalChatText = null;
        }
        if (Keyboard.getEventKeyState() && GuiScreen.isShiftKeyDown()) {
            int keyCode = Keyboard.getEventKey();
            if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
                try {
                    GuiTextField inputField = (GuiTextField) chatInputField.get(currentChatGui);
                    if (inputField == null) return;
                    List<String> sentMessages = Minecraft.getMinecraft().ingameGUI.getChatGUI().getSentMessages();
                    if (sentMessages == null) sentMessages = new ArrayList<>();
                    if (chatHistoryIndex == -1) originalChatText = inputField.getText();
                    
                    int delta = (keyCode == Keyboard.KEY_UP) ? -1 : 1;
                    updateChatHistory(delta, sentMessages, inputField);
                    
                    event.setCanceled(true);
                } catch (Exception e) { e.printStackTrace(); }
            }
        }
    }

    @SubscribeEvent
    public void onGuiMouseInput(MouseInputEvent.Pre event) {
        if (!GhostConfig.enableCommandHistoryScroll || chatInputField == null) return;
        if (!(event.gui instanceof GuiChat)) return;
        
        int wheelDelta = Mouse.getEventDWheel();
        if (wheelDelta != 0 && GuiScreen.isShiftKeyDown()) {
             try {
                int delta = (wheelDelta > 0) ? -1 : 1;
                
                GuiTextField inputField = (GuiTextField) chatInputField.get(event.gui);
                if (inputField == null) return;
                
                List<String> sentMessages = Minecraft.getMinecraft().ingameGUI.getChatGUI().getSentMessages();
                if (sentMessages == null || sentMessages.isEmpty()) return;
                
                if (chatHistoryIndex == -1) originalChatText = inputField.getText();

                updateChatHistory(delta, sentMessages, inputField);

                event.setCanceled(true);
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void updateChatHistory(int delta, List<String> sentMessages, GuiTextField inputField) {
        if (sentMessages.isEmpty()) {
            return;
        }
        
        chatHistoryIndex += delta;
        
        if (chatHistoryIndex < -1) {
            chatHistoryIndex = sentMessages.size() - 1;
        } else if (chatHistoryIndex >= sentMessages.size()) {
            chatHistoryIndex = -1;
        }
        
        String newText = (chatHistoryIndex >= 0) ? sentMessages.get(chatHistoryIndex) : (originalChatText != null ? originalChatText : "");
        inputField.setText(newText);
        inputField.setCursorPositionEnd();
    }

    // ====================
    // === 辅助方法 ===
    // ====================
    private void triggerAutomaticChatTranslation(String originalText) {
        new Thread(() -> {
            final String result = NiuTransUtil.translate(originalText);
            
            // 调度回主线程来发送聊天消息
            Minecraft.getMinecraft().addScheduledTask(() -> {
                // 只有成功翻译的结果才显示
                if (!result.startsWith(NiuTransUtil.ERROR_PREFIX)) {
                    ChatComponentText resultMessage = new ChatComponentText("");
                    ChatComponentText resultPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.translation") + " ");
                    resultPrefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
                    ChatComponentText resultContent = new ChatComponentText(result);
                    resultMessage.appendSibling(resultPrefix).appendSibling(resultContent);
                    
                    // 向玩家自己发送一条新的聊天消息
                    Minecraft.getMinecraft().thePlayer.addChatMessage(resultMessage);
                }
            });
        }).start();
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
            System.err.println("[Ghost-Chat] 附加翻译按钮时出错: " + e.getMessage());
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
            e.printStackTrace();
            return false;
        }
    }
}