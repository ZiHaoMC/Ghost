package com.zihaomc.ghost.features.chat;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;

import java.io.IOException;

/**
 * 一个继承自原生GuiChat的包装类。
 * 通过重写initGui方法，从根本上解决因窗口调整导致的文本丢失问题。
 */
public class GuiChatWrapper extends GuiChat {

    // 用于在initGui重建期间临时保存文本的变量
    private String textToRestore = "";
    private int cursorPositionToRestore = 0;

    /**
     * 构造函数，用于从已有的文本初始化
     * @param startingText 聊天框打开时预设的文本
     */
    public GuiChatWrapper(String startingText) {
        super(startingText);
        this.textToRestore = startingText;
        this.cursorPositionToRestore = startingText.length();
    }

    /**
     * 这是解决问题的核心。
     * 每当窗口大小调整，这个方法都会被调用。
     */
    @Override
    public void initGui() {
        // 1. 在原生逻辑运行之前，从旧的inputField（如果存在）中保存文本和光标位置
        if (this.inputField != null) {
            this.textToRestore = this.inputField.getText();
            this.cursorPositionToRestore = this.inputField.getCursorPosition();
        }

        // 2. 调用父类（原生GuiChat）的initGui方法。
        //    这一步会创建一个全新的、空的inputField，替换掉旧的。
        super.initGui();

        // 3. 在原生逻辑运行之后，将我们保存的文本和光标位置恢复到这个 *新* 的inputField上。
        if (this.inputField != null) {
            this.inputField.setText(this.textToRestore);
            // 确保光标位置在文本长度范围内
            int newCursorPos = Math.min(this.textToRestore.length(), this.cursorPositionToRestore);
            this.inputField.setCursorPosition(newCursorPos);
        }
    }

    /**
     * 我们还需要重写onGuiClosed，以确保在关闭时，
     * textToRestore被清空，避免在下次正常打开时（非重建）恢复旧文本。
     */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        this.textToRestore = "";
        this.cursorPositionToRestore = 0;
    }
}