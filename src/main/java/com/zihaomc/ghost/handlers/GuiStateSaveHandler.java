package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.notes.GuiNote;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;

/**
 * [最终修复版]
 * 处理因窗口大小调整或全屏切换导致的GUI状态（如输入框文本）丢失问题。
 * 通过在每个Tick中监控GUI内部组件（如GuiTextField）实例的变化，实现最可靠的状态保存与恢复。
 */
public class GuiStateSaveHandler {

    // --- 状态保存变量 ---
    private static String savedChatText = ""; // 初始化为空字符串以避免null检查
    private static String savedNoteText = "";

    // --- [核心改动] 跟踪GUI内部组件的实例 ---
    private static GuiTextField lastChatFieldInstance = null;
    private static GuiNote lastNoteInstance = null;
    
    // 用于通过反射访问聊天输入框的字段
    private static Field chatInputField = null;
    
    static {
        try {
            chatInputField = ReflectionHelper.findField(GuiChat.class, "field_146415_a", "inputField");
            chatInputField.setAccessible(true);
        } catch (Exception e) {
            LogUtil.error("log.error.reflection.inputField");
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !GhostConfig.fixGuiStateLossOnResize) {
            return;
        }

        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;

        // --- GuiChat 状态处理 ---
        if (currentScreen instanceof GuiChat) {
            handleChatState((GuiChat) currentScreen);
        } else {
            // 如果当前屏幕不是GuiChat，重置跟踪器
            lastChatFieldInstance = null; 
        }

        // --- GuiNote 状态处理 ---
        if (currentScreen instanceof GuiNote) {
            handleNoteState((GuiNote) currentScreen);
        } else {
            // 如果当前屏幕不是GuiNote，重置跟踪器
            lastNoteInstance = null;
        }
    }

    private void handleChatState(GuiChat currentChatGui) {
        if (chatInputField == null) return; // 反射失败则不执行

        try {
            GuiTextField currentChatField = (GuiTextField) chatInputField.get(currentChatGui);
            if (currentChatField == null) return;

            // [核心逻辑] 检测输入框实例是否被替换
            if (currentChatField != lastChatFieldInstance) {
            LogUtil.debug("log.debug.gui.restoring", savedChatText);
                // 实例不同，说明GUI被重建 (initGui被调用)
                // 此时，用我们保存的文本恢复这个 *新* 的输入框
                if (lastChatFieldInstance != null) { // 避免首次打开时恢复
                    currentChatField.setText(savedChatText);
                    currentChatField.setCursorPositionEnd();
                }
                // 更新跟踪器为当前的新实例
                lastChatFieldInstance = currentChatField;
            }

            // 无论如何，每一帧都保存当前输入框的文本
            savedChatText = currentChatField.getText();

        } catch (Exception e) {
            // 发生异常时重置，避免错误状态
            lastChatFieldInstance = null;
        }
    }

    private void handleNoteState(GuiNote currentNoteGui) {
        // 对于我们自己的GUI，逻辑可以简化，因为我们可以直接控制它
        // 但为了统一和稳健，同样采用实例比较法
        if (currentNoteGui != lastNoteInstance) {
            if (lastNoteInstance != null) { // 避免首次打开
                currentNoteGui.setTextContentAndInitialize(savedNoteText);
            }
            lastNoteInstance = currentNoteGui;
        }
        // 持续保存
        savedNoteText = currentNoteGui.getTextContent();
    }
}