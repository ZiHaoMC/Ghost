package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.notes.GuiNote;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;

/**
 * 处理因窗口大小调整或全屏切换导致的GUI状态（如输入框文本）丢失问题。
 * 通过在GUI重建前后保存和恢复状态来修复此BUG。
 */
public class GuiStateSaveHandler {

    private static String savedChatText = null;
    private static String savedNoteText = null;
    
    // 一个简单的标志，表示我们已保存状态，因为一个相同类型的GUI即将被打开（很可能是重建）。
    private static boolean statePotentiallySavedForRebuild = false;
    
    private static Field chatInputField = null;
    
    // 静态初始化块，用于安全地获取一次反射字段
    static {
        try {
            // 从 ChatSuggestEventHandler 借鉴的反射字段获取方法
            chatInputField = ReflectionHelper.findField(GuiChat.class, "field_146415_a", "inputField");
            chatInputField.setAccessible(true);
        } catch (Exception e) {
            LogUtil.error("log.error.reflection.inputField");
        }
    }

    /**
     * 在一个新的GUI屏幕即将被打开时触发。
     * 这是我们“抢救”旧GUI状态的最后机会。
     */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!GhostConfig.fixGuiStateLossOnResize) {
            return;
        }

        GuiScreen oldScreen = Minecraft.getMinecraft().currentScreen;
        GuiScreen newScreen = event.gui;

        // 如果我们正在关闭GUI（新GUI为null）或打开一个完全不同的GUI，则重置所有已保存的状态。
        if (oldScreen == null || newScreen == null || oldScreen.getClass() != newScreen.getClass()) {
            savedChatText = null;
            savedNoteText = null;
            statePotentiallySavedForRebuild = false;
            return;
        }

        // 如果新旧GUI是同一个类的实例，我们有理由相信这是一次重建（例如全屏切换）。
        // 于是我们保存它的状态。
        statePotentiallySavedForRebuild = true;
        
        if (oldScreen instanceof GuiChat) {
            try {
                if (chatInputField != null) {
                    GuiTextField inputField = (GuiTextField) chatInputField.get(oldScreen);
                    savedChatText = inputField.getText();
                }
            } catch (Exception e) {
                savedChatText = null; // 安全起见
            }
        } else if (oldScreen instanceof GuiNote) {
            // 使用我们为 GuiNote 添加的公共方法
            savedNoteText = ((GuiNote) oldScreen).getTextContent();
        } else {
            // 这不是我们追踪的GUI类型，所以实际上没有状态被保存。
            statePotentiallySavedForRebuild = false;
        }
    }

    /**
     * 在GUI的 `initGui` 方法执行完毕后触发。
     * 这是恢复我们之前保存的状态到新GUI实例的最佳时机。
     */
    @SubscribeEvent
    public void onGuiInitPost(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!GhostConfig.fixGuiStateLossOnResize || !statePotentiallySavedForRebuild) {
            return;
        }
        
        GuiScreen newScreen = event.gui;

        if (newScreen instanceof GuiChat && savedChatText != null) {
            try {
                if (chatInputField != null) {
                    GuiTextField inputField = (GuiTextField) chatInputField.get(newScreen);
                    inputField.setText(savedChatText);
                    inputField.setCursorPositionEnd(); // 将光标移到末尾
                }
            } catch (Exception e) {
                // 如果出错，记录日志（可选）
            }
        } else if (newScreen instanceof GuiNote && savedNoteText != null) {
            // 使用我们为 GuiNote 添加的公共方法
            ((GuiNote) newScreen).setTextContentAndInitialize(savedNoteText);
        }

        // 无论恢复成功与否，都重置状态，防止它们被错误地用于其他GUI。
        savedChatText = null;
        savedNoteText = null;
        statePotentiallySavedForRebuild = false;
    }
}