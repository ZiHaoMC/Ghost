package com.zihaomc.ghost.features.notes;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;

/**
 * 游戏内笔记的GUI界面。 (V10: 最终渲染同步算法版)
 */
public class GuiNote extends GuiScreen {

    private String textContent = "";
    private int cursorPosition = 0;
    private int selectionAnchor = 0;
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
        setCursorPosition(this.textContent.length());
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
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_RETURN) {
            this.insertText("\n");
            return;
        }
        super.handleKeyboardInput();
    }
    
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { this.mc.displayGuiScreen(null); return; }
        if (GhostConfig.enableAdvancedEditing && GuiScreen.isCtrlKeyDown()) {
            if (keyCode == Keyboard.KEY_A) { selectAll(); return; }
            if (keyCode == Keyboard.KEY_C) { GuiScreen.setClipboardString(getSelectedText()); return; }
            if (keyCode == Keyboard.KEY_X) { GuiScreen.setClipboardString(getSelectedText()); deleteSelection(); return; }
            if (keyCode == Keyboard.KEY_V) { insertText(GuiScreen.getClipboardString()); return; }
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (hasSelection()) deleteSelection(); else deleteCharBackwards();
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
        if (GhostConfig.enableAdvancedEditing && mouseButton == 0 &&
            mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth &&
            mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
            int charIndex = getCharIndexAt(mouseX, mouseY);
            setCursorPosition(charIndex, GuiScreen.isShiftKeyDown());
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (GhostConfig.enableAdvancedEditing && clickedMouseButton == 0 &&
            mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth &&
            mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
            int charIndex = getCharIndexAt(mouseX, mouseY);
            setCursorPosition(charIndex, true);
        }
    }
    
    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            int scrollAmount = this.fontRendererObj.FONT_HEIGHT * 3 * (dWheel < 0 ? 1 : -1);
            this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset + scrollAmount));
        }
    }
    
    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if(button.enabled && button.id == 0) this.mc.displayGuiScreen(null);
        super.actionPerformed(button);
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
        this.maxScroll = Math.max(0, lines.size() * this.fontRendererObj.FONT_HEIGHT - this.textAreaHeight + 5);
        this.scrollOffset = Math.min(this.maxScroll, Math.max(0, this.scrollOffset));

        int yPos = this.textAreaY + 4 - this.scrollOffset;
        int selStart = getSelectionStart();
        int selEnd = getSelectionEnd();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineStartCharIndex = getCharIndexOfLine(i, lines);
            int lineEndCharIndex = (i + 1 < lines.size()) ? getCharIndexOfLine(i + 1, lines) : this.textContent.length();
            
            if (hasSelection() && selStart < lineEndCharIndex && selEnd > lineStartCharIndex) {
                int highlightStart = Math.max(selStart, lineStartCharIndex);
                int highlightEnd = Math.min(selEnd, lineEndCharIndex);
                String textBeforeHighlight = this.textContent.substring(lineStartCharIndex, highlightStart);
                String highlightedText = this.textContent.substring(highlightStart, highlightEnd);
                int x1 = this.textAreaX + 4 + this.fontRendererObj.getStringWidth(textBeforeHighlight);
                int x2 = x1 + this.fontRendererObj.getStringWidth(highlightedText);
                if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                    drawSelectionBox(x1, yPos, x2, yPos + this.fontRendererObj.FONT_HEIGHT);
                }
            }
            if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                this.fontRendererObj.drawStringWithShadow(line, this.textAreaX + 4, yPos, 0xFFFFFF);
            }
            yPos += this.fontRendererObj.FONT_HEIGHT;
        }
        
        if ((this.cursorBlink / 6) % 2 == 0) {
            PositionData cursorPd = getPositionData(this.cursorPosition, lines);
            if (cursorPd != null) {
                int cursorY = this.textAreaY + 4 - this.scrollOffset + (cursorPd.lineIndex * this.fontRendererObj.FONT_HEIGHT);
                int cursorX = this.textAreaX + 4 + cursorPd.xOffset;
                if (cursorY >= this.textAreaY - 1 && cursorY + this.fontRendererObj.FONT_HEIGHT <= this.textAreaY + this.textAreaHeight) {
                     drawRect(cursorX, cursorY - 1, cursorX + 1, cursorY + this.fontRendererObj.FONT_HEIGHT, 0xFFFFFFFF);
                }
            }
        }
        
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.scroll_hint"), this.width / 2, this.height - 40, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    
    // --- 文本编辑辅助方法 ---
    private void insertText(String text) {
        if (hasSelection()) deleteSelection();
        String textToInsert;
        if ("\n".equals(text)) textToInsert = "\n"; else textToInsert = ChatAllowedCharacters.filterAllowedCharacters(text);
        if (textToInsert.isEmpty()) return;
        this.textContent = new StringBuilder(this.textContent).insert(this.cursorPosition, textToInsert).toString();
        moveCursorBy(textToInsert.length(), false);
    }
    private void deleteCharBackwards() {
        if (hasSelection()) { deleteSelection(); } else if (this.cursorPosition > 0) {
            this.textContent = new StringBuilder(this.textContent).deleteCharAt(this.cursorPosition - 1).toString();
            moveCursorBy(-1, false);
        }
    }
    private void deleteSelection() {
        if (!hasSelection()) return;
        int start = getSelectionStart();
        this.textContent = new StringBuilder(this.textContent).delete(start, getSelectionEnd()).toString();
        setCursorPosition(start, false);
    }
    private void moveCursorBy(int amount, boolean extendSelection) { setCursorPosition(this.cursorPosition + amount, extendSelection); }
    private void setCursorPosition(int newPosition, boolean extendSelection) {
        this.cursorPosition = Math.max(0, Math.min(this.textContent.length(), newPosition));
        if (!extendSelection) this.selectionAnchor = this.cursorPosition;
        this.cursorBlink = 0;
    }
    private void setCursorPosition(int newPosition) { setCursorPosition(newPosition, false); }
    private void selectAll() { this.selectionAnchor = 0; this.cursorPosition = this.textContent.length(); }
    private boolean hasSelection() { return this.cursorPosition != this.selectionAnchor; }
    private int getSelectionStart() { return Math.min(this.cursorPosition, this.selectionAnchor); }
    private int getSelectionEnd() { return Math.max(this.cursorPosition, this.selectionAnchor); }
    private String getSelectedText() { return hasSelection() ? this.textContent.substring(getSelectionStart(), getSelectionEnd()) : ""; }

    private void drawSelectionBox(int startX, int startY, int endX, int endY) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        GlStateManager.color(0.0F, 0.0F, 1.0F, 0.5F);
        GlStateManager.disableTexture2D();
        GlStateManager.enableColorLogic();
        GlStateManager.colorLogicOp(5387); // GL_OR_REVERSE
        worldrenderer.begin(7, DefaultVertexFormats.POSITION);
        worldrenderer.pos((double)startX, (double)endY, 0.0D).endVertex();
        worldrenderer.pos((double)endX, (double)endY, 0.0D).endVertex();
        worldrenderer.pos((double)endX, (double)startY, 0.0D).endVertex();
        worldrenderer.pos((double)startX, (double)startY, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.disableColorLogic();
        GlStateManager.enableTexture2D();
    }
    
    // --- [V10] 最终版核心定位算法 ---
    private static class PositionData { int lineIndex; int xOffset; PositionData(int line, int x) { this.lineIndex = line; this.xOffset = x; } }

    private int getCharIndexOfLine(int lineIndex, List<String> lines) {
        if (lineIndex <= 0) return 0;
        if (lineIndex >= lines.size()) return this.textContent.length();
        int charCount = 0;
        for(int i = 0; i < lineIndex; i++) {
            charCount += lines.get(i).length();
            if (charCount < this.textContent.length() && this.textContent.charAt(charCount) == '\n') {
                charCount++;
            }
        }
        return charCount;
    }

    private PositionData getPositionData(int charIndex, List<String> lines) {
        if (charIndex < 0 || charIndex > this.textContent.length()) return null;
        int charCounter = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int nextCharCounter = charCounter + line.length();
            if (charIndex >= charCounter && charIndex <= nextCharCounter) {
                String sub = this.textContent.substring(charCounter, charIndex);
                int xOffset = this.fontRendererObj.getStringWidth(sub);
                return new PositionData(i, xOffset);
            }
            charCounter = nextCharCounter;
            if (charCounter < this.textContent.length() && this.textContent.charAt(charCounter) == '\n') {
                if (charIndex == charCounter) {
                    return new PositionData(i + 1, 0);
                }
                charCounter++;
            }
        }
        return new PositionData(lines.size(), 0);
    }
    
    private int getCharIndexAt(int mouseX, int mouseY) {
        List<String> lines = this.fontRendererObj.listFormattedStringToWidth(this.textContent, this.textAreaWidth - 8);
        int relativeY = mouseY - this.textAreaY - 4 + this.scrollOffset;
        int clickedLineIndex = Math.max(0, Math.min(lines.size() - 1, relativeY / this.fontRendererObj.FONT_HEIGHT));
        int lineStartCharIndex = getCharIndexOfLine(clickedLineIndex, lines);
        String clickedLine = lines.get(clickedLineIndex);
        int relativeX = mouseX - this.textAreaX - 4;
        String trimmedToMouse = this.fontRendererObj.trimStringToWidth(clickedLine, relativeX);
        return lineStartCharIndex + trimmedToMouse.length();
    }
}