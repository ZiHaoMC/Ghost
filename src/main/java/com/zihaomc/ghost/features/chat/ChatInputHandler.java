package com.zihaomc.ghost.features.chat;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ChatInputHandler {

    private static Field chatInputField = null;
    private static Field defaultInputFieldTextField = null;
    
    private static int chatHistoryIndex = -1;
    private static String originalChatText = null;
    private static GuiChat activeGuiChatInstance = null;

    public ChatInputHandler() {
        initializeReflectionFields();
    }

    private void initializeReflectionFields() {
        try {
            chatInputField = ReflectionHelper.findField(GuiChat.class, "field_146415_a", "inputField");
            chatInputField.setAccessible(true);
            
            defaultInputFieldTextField = ReflectionHelper.findField(GuiChat.class, "field_146409_v", "defaultInputFieldText");
            defaultInputFieldTextField.setAccessible(true);
        } catch (Exception e) {
            LogUtil.error("log.error.reflection.inputField");
        }
    }

    /**
     * 修复窗口调整大小时聊天内容丢失的问题。
     */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!GhostConfig.GuiTweaks.fixGuiStateLossOnResize) return;
        
        if (event.gui != null && event.gui.getClass() == GuiChat.class) {
            String startingText = "";
            try {
                if (defaultInputFieldTextField != null) {
                    startingText = (String) defaultInputFieldTextField.get(event.gui);
                }
                if ((startingText == null || startingText.isEmpty()) && chatInputField != null) {
                    GuiTextField textField = (GuiTextField) chatInputField.get(event.gui);
                    if (textField != null) startingText = textField.getText();
                }
            } catch (Exception ignored) {}

            if (startingText == null) startingText = "";
            event.gui = new GuiChatWrapper(startingText);
        }
    }

    /**
     * 键盘输入：Twitch拦截 & 历史记录滚动。
     */
    @SubscribeEvent
    public void onGuiKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!(event.gui instanceof GuiChat)) return;

        // 1. Twitch 拦截
        if (GhostConfig.ChatFeatures.disableTwitchAtKey && Keyboard.getEventCharacter() == '@') {
            event.setCanceled(true);
            try {
                if (chatInputField != null) {
                    GuiTextField inputField = (GuiTextField) chatInputField.get(event.gui);
                    if (inputField != null) inputField.writeText("@");
                }
            } catch (Exception e) {
                LogUtil.printStackTrace("log.error.gui.twitchkey.failed", e);
            }
            return;
        }

        // 2. 历史记录滚动 (Shift + 上下)
        if (!GhostConfig.ChatFeatures.enableCommandHistoryScroll) return;
        
        GuiChat currentChatGui = (GuiChat) event.gui;
        updateActiveInstance(currentChatGui);

        if (Keyboard.getEventKeyState() && net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
            int keyCode = Keyboard.getEventKey();
            if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
                handleHistoryScroll(currentChatGui, (keyCode == Keyboard.KEY_UP) ? -1 : 1);
                event.setCanceled(true);
            }
        }
    }

    /**
     * 鼠标输入：历史记录滚动 (Shift + 滚轮)。
     */
    @SubscribeEvent
    public void onGuiMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!GhostConfig.ChatFeatures.enableCommandHistoryScroll || !(event.gui instanceof GuiChat)) return;
        
        int wheelDelta = Mouse.getEventDWheel();
        if (wheelDelta != 0 && net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
            updateActiveInstance((GuiChat) event.gui);
            handleHistoryScroll((GuiChat) event.gui, (wheelDelta > 0) ? -1 : 1);
            event.setCanceled(true);
        }
    }

    private void updateActiveInstance(GuiChat currentChatGui) {
        if (activeGuiChatInstance == null || activeGuiChatInstance != currentChatGui) {
            activeGuiChatInstance = currentChatGui;
            chatHistoryIndex = -1;
            originalChatText = null;
        }
    }

    private void handleHistoryScroll(GuiChat gui, int delta) {
        try {
            if (chatInputField == null) return;
            GuiTextField inputField = (GuiTextField) chatInputField.get(gui);
            if (inputField == null) return;
            
            List<String> sentMessages = Minecraft.getMinecraft().ingameGUI.getChatGUI().getSentMessages();
            if (sentMessages == null || sentMessages.isEmpty()) return;
            
            if (chatHistoryIndex == -1) originalChatText = inputField.getText();

            chatHistoryIndex += delta;
            
            if (chatHistoryIndex < -1) chatHistoryIndex = sentMessages.size() - 1;
            else if (chatHistoryIndex >= sentMessages.size()) chatHistoryIndex = -1;
            
            String newText = (chatHistoryIndex >= 0) ? sentMessages.get(chatHistoryIndex) : (originalChatText != null ? originalChatText : "");
            inputField.setText(newText);
            inputField.setCursorPositionEnd();
        } catch (Exception e) {
            LogUtil.printStackTrace("log.error.gui.mouse.failed", e);
        }
    }
}