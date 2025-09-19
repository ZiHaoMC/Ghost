package com.zihaomc.ghost.features.notes;

import com.zihaomc.ghost.LangUtil;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;

/**
 * 游戏内笔记的GUI界面。 (V6: 最终光标修复版)
 */
public class GuiNote extends GuiScreen {

    private String textContent = "";
    private int cursorPosition = 0;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    private int textAreaX;
    private int textAreaY;
    private int textAreaWidth;
    private int textAreaHeight;

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
        this.cursorPosition = this.textContent.length();
        
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
        if (keyCode == Keyboard.KEY_BACK) {
            if (GuiScreen.isCtrlKeyDown()) { deleteWordBackwards(); } 
            else { deleteCharBackwards(); }
            return;
        }
        if (keyCode == Keyboard.KEY_LEFT) { moveCursorBy(-1); return; }
        if (keyCode == Keyboard.KEY_RIGHT) { moveCursorBy(1); return; }
        if (keyCode == Keyboard.KEY_HOME) { setCursorPosition(0); return; }
        if (keyCode == Keyboard.KEY_END) { setCursorPosition(this.textContent.length()); return; }
        if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
            insertText(GuiScreen.getClipboardString());
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
            insertText(Character.toString(typedChar));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.enabled && button.id == 0) {
            this.mc.displayGuiScreen(null);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            int scrollAmount = this.fontRendererObj.FONT_HEIGHT * 3;
            if (dWheel > 0) { this.scrollOffset = Math.max(0, this.scrollOffset - scrollAmount); } 
            else { this.scrollOffset = Math.min(this.maxScroll, this.scrollOffset + scrollAmount); }
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
        for (String line : lines) {
            if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                this.fontRendererObj.drawString(line, this.textAreaX + 4, yPos, 0xFFFFFF);
            }
            yPos += this.fontRendererObj.FONT_HEIGHT;
        }

        // --- [V6 FIX] 光标渲染逻辑重构 ---
        if ((this.cursorBlink / 6) % 2 == 0) {
            String textBeforeCursor = this.textContent.substring(0, this.cursorPosition);
            List<String> linesBeforeCursor = this.fontRendererObj.listFormattedStringToWidth(textBeforeCursor, this.textAreaWidth - 8);
            
            int cursorLineIndex = Math.max(0, linesBeforeCursor.size() - 1);
            int cursorX;

            // 如果光标前是空的，或者最后一个字符是换行符，那么光标一定在新行的最左边
            if (textBeforeCursor.isEmpty() || textBeforeCursor.endsWith("\n")) {
                cursorX = this.textAreaX + 4;
                // 如果是因为换行符导致，视觉行数需要+1
                if (textBeforeCursor.endsWith("\n")) {
                    cursorLineIndex = linesBeforeCursor.size();
                }
            } else {
                // 否则，计算光标在当前行内的位置
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
    
    // --- 文本编辑辅助方法 ---

    private void insertText(String text) {
        String textToInsert;
        if ("\n".equals(text)) { textToInsert = "\n"; } 
        else { textToInsert = ChatAllowedCharacters.filterAllowedCharacters(text); }
        if (textToInsert.isEmpty()) { return; }
        String newContent = new StringBuilder(this.textContent).insert(this.cursorPosition, textToInsert).toString();
        this.textContent = newContent;
        moveCursorBy(textToInsert.length());
    }
    
    private void deleteCharBackwards() {
        if (this.cursorPosition > 0) {
            String newContent = new StringBuilder(this.textContent).deleteCharAt(this.cursorPosition - 1).toString();
            this.textContent = newContent;
            moveCursorBy(-1);
        }
    }
    
    private void deleteWordBackwards() {
        if (this.cursorPosition > 0) {
            int deleteFrom = this.textContent.lastIndexOf(' ', this.cursorPosition - 2) + 1;
            String newContent = new StringBuilder(this.textContent).delete(deleteFrom, this.cursorPosition).toString();
            this.textContent = newContent;
            setCursorPosition(deleteFrom);
        }
    }

    private void moveCursorBy(int amount) {
        setCursorPosition(this.cursorPosition + amount);
    }

    private void setCursorPosition(int newPosition) {
        this.cursorPosition = Math.max(0, Math.min(this.textContent.length(), newPosition));
        this.cursorBlink = 0;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}