package com.zihaomc.ghost.features.note;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.MathHelper;

/**
 * 笔记编辑器核心，负责管理文本内容、光标和选区。
 * 这是一个纯粹的数据和逻辑模型，不涉及任何GUI渲染。
 */
public class NoteEditor {

    private String textContent = "";
    private int cursorPosition = 0;
    private int selectionAnchor = 0;

    public String getTextContent() { return textContent; }
    public int getCursorPosition() { return cursorPosition; }
    public int getSelectionAnchor() { return selectionAnchor; }
    
    public void setTextContent(String text) {
        this.textContent = text;
        this.cursorPosition = MathHelper.clamp_int(this.cursorPosition, 0, this.textContent.length());
        this.selectionAnchor = MathHelper.clamp_int(this.selectionAnchor, 0, this.textContent.length());
    }

    public void insertText(String textToInsert) {
        if (hasSelection()) deleteSelection();
        
        StringBuilder filtered = new StringBuilder();
        for (char c : textToInsert.toCharArray()) {
            if (c == '§' || c == '&' || ChatAllowedCharacters.isAllowedCharacter(c) || c == '\n') {
                filtered.append(c);
            }
        }
        String cleanText = filtered.toString();
        if (cleanText.isEmpty()) return;

        this.textContent = new StringBuilder(this.textContent).insert(this.cursorPosition, cleanText).toString();
        this.cursorPosition += cleanText.length();
        this.selectionAnchor = this.cursorPosition;
    }
    
    public void deleteCharBackwards() {
        if (cursorPosition > 0) {
            int numToDelete = 1;
            if (cursorPosition > 1) {
                char precedingChar = textContent.charAt(cursorPosition - 2);
                char lastChar = textContent.charAt(cursorPosition - 1);
                boolean isColorPrefix = precedingChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && precedingChar == '&');
                if (isColorPrefix && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(lastChar)) != -1) {
                    numToDelete = 2;
                }
            }
            
            int start = this.cursorPosition - numToDelete;
            this.textContent = new StringBuilder(this.textContent).delete(start, this.cursorPosition).toString();
            setCursorPosition(start, false);
        }
    }
    
    /**
     * 新增：处理前进删除（DEL键）的逻辑
     */
    public void deleteCharForwards() {
        if (cursorPosition < textContent.length()) {
            int numToDelete = 1;
            if (cursorPosition < textContent.length() - 1) {
                char currentChar = textContent.charAt(cursorPosition);
                char nextChar = textContent.charAt(cursorPosition + 1);
                boolean isColorPrefix = currentChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&');
                if (isColorPrefix && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(nextChar)) != -1) {
                    numToDelete = 2;
                }
            }
            this.textContent = new StringBuilder(this.textContent).delete(this.cursorPosition, this.cursorPosition + numToDelete).toString();
            // 前进删除后，光标位置不变，但需要重置选区
            this.selectionAnchor = this.cursorPosition;
        }
    }
    
    public void deleteSelection() {
        if (!hasSelection()) return;
        int start = getSelectionStart();
        this.textContent = new StringBuilder(this.textContent).delete(start, getSelectionEnd()).toString();
        setCursorPosition(start, false);
    }
    
    public boolean hasSelection() { return this.cursorPosition != this.selectionAnchor; }
    public int getSelectionStart() { return Math.min(this.cursorPosition, this.selectionAnchor); }
    public int getSelectionEnd() { return Math.max(this.cursorPosition, this.selectionAnchor); }
    public String getSelectedText() { return hasSelection() ? this.textContent.substring(getSelectionStart(), getSelectionEnd()) : ""; }
    
    public void setCursorPosition(int newPosition, boolean extendSelection) {
        this.cursorPosition = MathHelper.clamp_int(newPosition, 0, this.textContent.length());
        if (!extendSelection) {
            this.selectionAnchor = this.cursorPosition;
        }
    }
    
    public void moveCursorBy(int amount, boolean extendSelection) {
        int newPos = this.cursorPosition;
        if (amount < 0) { // 向左
            newPos = Math.max(0, this.cursorPosition + amount);
            if (newPos > 0) {
                 char precedingChar = this.textContent.charAt(newPos - 1);
                 if (precedingChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && precedingChar == '&')) {
                    newPos = Math.max(0, newPos - 1);
                 }
            }
        } else if (amount > 0) { // 向右
            newPos = Math.min(this.textContent.length(), this.cursorPosition + amount);
            if (newPos < this.textContent.length() - 1) {
                char currentChar = this.textContent.charAt(newPos);
                if (currentChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&')) {
                    newPos = Math.min(this.textContent.length(), newPos + 1);
                }
            }
        }
        setCursorPosition(newPos, extendSelection);
    }

    public void selectAll() {
        this.selectionAnchor = 0;
        this.cursorPosition = this.textContent.length();
    }
}