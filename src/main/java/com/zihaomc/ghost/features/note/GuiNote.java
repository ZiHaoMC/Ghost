package com.zihaomc.ghost.features.notes;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;

/**
 * 游戏内笔记的GUI界面。 (V7: 高级编辑功能版)
 */
public class GuiNote extends GuiScreen {

    private String textContent = "";
    private int cursorPosition = 0;
    private int selectionAnchor = 0; // 选区的固定端点
    private int scrollOffset = 0;
    private int maxScroll = 0;

    private int textAreaX, textAreaY, textAreaWidth, textAreaHeight;
    private int cursorBlink;

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);

        this.textAreaX = this.width / 2 - 150;
        this.textAreaY = 40;
        this.textAreaWidth = 300;
        this.textAreaHeight = this.height - 90;

        this.textContent = NoteManager.loadNote();
        setCursorPosition(this.textContent.length()); // 使用 setCursorPosition 来同时更新光标和选区锚点
        
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 25, LangUtil.translate("ghost.gui.note.save_and_close")));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        NoteManager.saveNote(this.textContent);
    }
    
    @Override
    public void updateScreen() {
        super.updateScreen();
        this.cursorBlink++;
    }

    @Override
    public void handleKeyboardInput() throws IOException {
        if (Keyboard.getEventKeyState()) {
            if (Keyboard.getEventKey() == Keyboard.KEY_RETURN) {
                this.insertText("\n");
                return;
            }
        }
        super.handleKeyboardInput();
    }
    
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { this.mc.displayGuiScreen(null); return; }

        // --- [V7] 高级快捷键 ---
        if (GhostConfig.enableAdvancedEditing && GuiScreen.isCtrlKeyDown()) {
            switch(keyCode) {
                case Keyboard.KEY_A: // Ctrl+A: 全选
                    selectAll();
                    return;
                case Keyboard.KEY_C: // Ctrl+C: 复制
                    GuiScreen.setClipboardString(getSelectedText());
                    return;
                case Keyboard.KEY_X: // Ctrl+X: 剪切
                    GuiScreen.setClipboardString(getSelectedText());
                    deleteSelection();
                    return;
                case Keyboard.KEY_V: // Ctrl+V: 粘贴
                    insertText(GuiScreen.getClipboardString());
                    return;
            }
        }
        
        if (keyCode == Keyboard.KEY_BACK) {
            if (hasSelection()) deleteSelection();
            else deleteCharBackwards();
            return;
        }

        if (keyCode == Keyboard.KEY_LEFT) { moveCursorBy(-1, GuiScreen.isShiftKeyDown()); return; }
        if (keyCode == Keyboard.KEY_RIGHT) { moveCursorBy(1, GuiScreen.isShiftKeyDown()); return; }
        if (keyCode == Keyboard.KEY_HOME) { setCursorPosition(0, GuiScreen.isShiftKeyDown()); return; }
        if (keyCode == Keyboard.KEY_END) { setCursorPosition(this.textContent.length(), GuiScreen.isShiftKeyDown()); return; }

        if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
            insertText(Character.toString(typedChar));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (GhostConfig.enableAdvancedEditing && mouseButton == 0) { // 左键点击
            if (mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth &&
                mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
                int charIndex = getCharIndexAt(mouseX, mouseY);
                setCursorPosition(charIndex, GuiScreen.isShiftKeyDown());
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (GhostConfig.enableAdvancedEditing && clickedMouseButton == 0) { // 左键拖动
            if (mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth &&
                mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
                int charIndex = getCharIndexAt(mouseX, mouseY);
                setCursorPosition(charIndex, true); // 拖动时总是扩展选区
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.title"), this.width / 2, 20, 0xFFFFFF);
        
        drawRect(textAreaX - 1, textAreaY - 1, textAreaX + textAreaWidth + 1, textAreaY + textAreaHeight + 1, 0xFFC0C0C0);
        drawRect(textAreaX, textAreaY, textAreaX + textAreaWidth, textAreaY + textAreaHeight, 0xFF000000);
        
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int scaleFactor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        GL11.glScissor(textAreaX * scaleFactor, mc.displayHeight - (textAreaY + textAreaHeight) * scaleFactor, textAreaWidth * scaleFactor, textAreaHeight * scaleFactor);
        
        List<String> lines = this.fontRendererObj.listFormattedStringToWidth(this.textContent, this.textAreaWidth - 8);
        
        int totalTextHeight = lines.size() * this.fontRendererObj.FONT_HEIGHT;
        this.maxScroll = Math.max(0, totalTextHeight - this.textAreaHeight + 5);
        this.scrollOffset = Math.min(this.maxScroll, Math.max(0, this.scrollOffset));

        int yPos = this.textAreaY + 4 - this.scrollOffset;
        int charCount = 0;

        // --- [V7] 渲染选区和文本 ---
        int selStart = getSelectionStart();
        int selEnd = getSelectionEnd();

        for (String line : lines) {
            int lineStartChar = charCount;
            int lineEndChar = lineStartChar + line.length();

            // 绘制选区高亮
            if (hasSelection() && selStart < lineEndChar && selEnd > lineStartChar) {
                int highlightStart = Math.max(selStart, lineStartChar);
                int highlightEnd = Math.min(selEnd, lineEndChar);

                String textBeforeHighlight = this.textContent.substring(lineStartChar, highlightStart);
                String highlightedText = this.textContent.substring(highlightStart, highlightEnd);

                int x1 = this.textAreaX + 4 + this.fontRendererObj.getStringWidth(textBeforeHighlight);
                int x2 = x1 + this.fontRendererObj.getStringWidth(highlightedText);
                
                if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                    drawRect(x1, yPos, x2, yPos + this.fontRendererObj.FONT_HEIGHT, 0xFF000080); // 蓝色半透明高亮
                }
            }
            
            // 绘制文本
            if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                this.fontRendererObj.drawStringWithShadow(line, this.textAreaX + 4, yPos, 0xFFFFFF);
            }

            yPos += this.fontRendererObj.FONT_HEIGHT;
            charCount += line.length();
            // 在原始文本中，换行符也算一个字符
            if (charCount < this.textContent.length() && this.textContent.charAt(charCount) == '\n') {
                charCount++;
            }
        }
        
        // --- 渲染光标 ---
        if ((this.cursorBlink / 6) % 2 == 0) {
            // (光标渲染逻辑与V6基本相同，这里不再重复展示)
            String textBeforeCursor = this.textContent.substring(0, this.cursorPosition);
            List<String> linesBeforeCursor = this.fontRendererObj.listFormattedStringToWidth(textBeforeCursor, this.textAreaWidth - 8);
            int cursorLineIndex = Math.max(0, linesBeforeCursor.size() - 1);
            int cursorX;
            if (textBeforeCursor.isEmpty() || textBeforeCursor.endsWith("\n")) {
                cursorX = this.textAreaX + 4;
                if (textBeforeCursor.endsWith("\n")) cursorLineIndex = linesBeforeCursor.size();
            } else {
                String lastLineText = linesBeforeCursor.isEmpty() ? "" : linesBeforeCursor.get(cursorLineIndex);
                cursorX = this.textAreaX + 4 + this.fontRendererObj.getStringWidth(lastLineText);
            }
            int cursorY = this.textAreaY + 4 - this.scrollOffset + (cursorLineIndex * this.fontRendererObj.FONT_HEIGHT);
            if (cursorY >= this.textAreaY - 1 && cursorY + this.fontRendererObj.FONT_HEIGHT <= this.textAreaY + this.textAreaHeight) {
                 drawRect(cursorX, cursorY - 1, cursorX + 1, cursorY + this.fontRendererObj.FONT_HEIGHT, 0xFFFFFFFF);
            }
        }
        
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.scroll_hint"), this.width / 2, this.height - 40, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    
    // --- [V7] 新增和修改的文本编辑辅助方法 ---

    private void insertText(String text) {
        if (hasSelection()) {
            deleteSelection();
        }
        String textToInsert;
        if ("\n".equals(text)) { textToInsert = "\n"; } 
        else { textToInsert = ChatAllowedCharacters.filterAllowedCharacters(text); }
        if (textToInsert.isEmpty()) { return; }
        
        this.textContent = new StringBuilder(this.textContent).insert(this.cursorPosition, textToInsert).toString();
        moveCursorBy(textToInsert.length(), false);
    }
    
    private void deleteCharBackwards() {
        if (this.cursorPosition > 0) {
            this.textContent = new StringBuilder(this.textContent).deleteCharAt(this.cursorPosition - 1).toString();
            moveCursorBy(-1, false);
        }
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int start = getSelectionStart();
        int end = getSelectionEnd();
        this.textContent = new StringBuilder(this.textContent).delete(start, end).toString();
        setCursorPosition(start, false);
    }
    
    private void moveCursorBy(int amount, boolean extendSelection) {
        setCursorPosition(this.cursorPosition + amount, extendSelection);
    }

    private void setCursorPosition(int newPosition, boolean extendSelection) {
        this.cursorPosition = Math.max(0, Math.min(this.textContent.length(), newPosition));
        if (!extendSelection) {
            this.selectionAnchor = this.cursorPosition; // 如果不扩展选区，就重置锚点
        }
        this.cursorBlink = 0;
    }
    
    // 重载旧方法以保持兼容
    private void setCursorPosition(int newPosition) {
        setCursorPosition(newPosition, false);
    }

    private void selectAll() {
        this.selectionAnchor = 0;
        this.cursorPosition = this.textContent.length();
    }

    private boolean hasSelection() { return this.cursorPosition != this.selectionAnchor; }
    private int getSelectionStart() { return Math.min(this.cursorPosition, this.selectionAnchor); }
    private int getSelectionEnd() { return Math.max(this.cursorPosition, this.selectionAnchor); }
    private String getSelectedText() {
        if (!hasSelection()) return "";
        return this.textContent.substring(getSelectionStart(), getSelectionEnd());
    }

    // --- 核心方法: 将屏幕坐标转换为文本索引 ---
    private int getCharIndexAt(int mouseX, int mouseY) {
        int relativeY = mouseY - this.textAreaY - 4 + this.scrollOffset;
        int lineIndex = Math.max(0, relativeY / this.fontRendererObj.FONT_HEIGHT);
        
        List<String> lines = this.fontRendererObj.listFormattedStringToWidth(this.textContent, this.textAreaWidth - 8);
        if (lineIndex >= lines.size()) {
            return this.textContent.length();
        }
        
        int charCount = 0;
        for (int i = 0; i < lineIndex; i++) {
            charCount += lines.get(i).length();
            if (charCount < this.textContent.length() && this.textContent.charAt(charCount) == '\n') {
                charCount++;
            }
        }
        
        int relativeX = mouseX - this.textAreaX - 4;
        String clickedLine = lines.get(lineIndex);
        String trimmedLine = this.fontRendererObj.trimStringToWidth(clickedLine, relativeX);
        charCount += trimmedLine.length();
        
        return Math.min(this.textContent.length(), charCount);
    }
}