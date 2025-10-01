package com.zihaomc.ghost.features.notes;

import com.google.common.collect.Lists;
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
import java.util.ArrayList;

/**
 * 游戏内笔记的GUI界面。
 * Final Version (V6) - Optifine Compatible "What You See Is What You Get" Renderer
 * @version V6
 */
public class GuiNote extends GuiScreen {

    private String textContent = "";
    private int cursorPosition = 0;
    private int selectionAnchor = 0;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    private int textAreaX, textAreaY, textAreaWidth, textAreaHeight;
    private int cursorBlink;

    private static final int PADDING = 4;
    private int wrappingWidth;
    
    private List<String> renderedLines;
    private int[] lineStartIndices;

    // V6核心：用于缓存当前行每个字符的精确屏幕X坐标
    private final List<Integer> charXPositions = new ArrayList<>();

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        this.textAreaX = this.width / 2 - 150;
        this.textAreaY = 40;
        this.textAreaWidth = 300;
        this.textAreaHeight = this.height - 90;

        this.wrappingWidth = this.textAreaWidth - PADDING * 2; 
        
        this.textContent = NoteManager.loadNote();
        updateLinesAndIndices(); // 继续使用我们之前最稳定版本的换行逻辑
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
        if (keyCode == Keyboard.KEY_ESCAPE) { 
            this.mc.displayGuiScreen(null); 
            return; 
        }
        
        if (GhostConfig.enableAdvancedEditing && GuiScreen.isCtrlKeyDown()) {
            if (keyCode == Keyboard.KEY_A) { 
                selectAll(); 
                return; 
            }
            if (keyCode == Keyboard.KEY_C) { 
                GuiScreen.setClipboardString(getSelectedText()); 
                return; 
            }
            if (keyCode == Keyboard.KEY_X) { 
                GuiScreen.setClipboardString(getSelectedText()); 
                deleteSelection(); 
                return; 
            }
            if (keyCode == Keyboard.KEY_V) { 
                insertText(GuiScreen.getClipboardString()); 
                return; 
            }
        }
        
        switch (keyCode) {
            case Keyboard.KEY_BACK:
                if (hasSelection()) {
                    deleteSelection();
                } else if (this.cursorPosition > 0) {
                    deleteCharBackwards();
                }
                return;
                
            case Keyboard.KEY_DELETE:
                if (hasSelection()) {
                    deleteSelection();
                } else if (this.cursorPosition < this.textContent.length()) {
                    this.textContent = new StringBuilder(this.textContent).deleteCharAt(this.cursorPosition).toString();
                    updateLinesAndIndices();
                }
                return;
                
            case Keyboard.KEY_LEFT:
                moveCursorBy(-1, GuiScreen.isShiftKeyDown());
                return;
                
            case Keyboard.KEY_RIGHT:
                moveCursorBy(1, GuiScreen.isShiftKeyDown());
                return;
                
            case Keyboard.KEY_HOME:
                setCursorPosition(0, GuiScreen.isShiftKeyDown());
                return;
                
            case Keyboard.KEY_END:
                setCursorPosition(this.textContent.length(), GuiScreen.isShiftKeyDown());
                return;
                
            case Keyboard.KEY_RETURN:
                insertText("\n");
                return;
        }
        
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
            insertText(Character.toString(typedChar));
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton == 0 &&
            mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth &&
            mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
            
            // 拖动时也需要精确查找
            int charIndex = getCharIndexAt(mouseX, mouseY);
            this.cursorPosition = charIndex;
            this.cursorBlink = 0;
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
        
        if (this.renderedLines != null) {
            this.maxScroll = Math.max(0, this.renderedLines.size() * this.fontRendererObj.FONT_HEIGHT - this.textAreaHeight + PADDING);
            this.scrollOffset = Math.min(this.maxScroll, Math.max(0, this.scrollOffset));

            int yPos = this.textAreaY + PADDING - this.scrollOffset;
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();

            for (int i = 0; i < this.renderedLines.size(); i++) {
                String line = this.renderedLines.get(i);
                
                // 只在行可见时才进行绘制和计算
                if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                    
                    int cursorLineIndex = findLineForPosition(this.cursorPosition);
                    int selectionStartLine = findLineForPosition(selStart);
                    int selectionEndLine = findLineForPosition(selEnd);

                    // 判断当前行是否需要进行精确坐标计算（即，光标或选区涉及此行）
                    boolean isLineInvolved = (i == cursorLineIndex) || (hasSelection() && i >= selectionStartLine && i <= selectionEndLine);

                    if (isLineInvolved) {
                        // V6核心：绘制文本并实时缓存每个字符的精确X坐标
                        drawStringAndCachePositions(line, this.textAreaX + PADDING, yPos, 0xFFFFFF);

                        // --- 绘制选区 (使用精确坐标) ---
                        if (hasSelection()) {
                            int lineStart = this.lineStartIndices[i];
                            int lineEnd = (i + 1 < this.lineStartIndices.length) ? this.lineStartIndices[i+1] : this.textContent.length();
                            
                            // 检查当前行是否与选区有交集
                            if (selEnd > lineStart && selStart < lineEnd) {
                                // 计算选区在本行内的起始和结束字符索引
                                int highlightStartInText = Math.max(selStart, lineStart);
                                int highlightEndInText = Math.min(selEnd, lineEnd);
                                
                                int highlightStartInLine = highlightStartInText - lineStart;
                                int highlightEndInLine = highlightEndInText - lineStart;

                                // 从缓存中获取精确的X坐标进行绘制
                                if (highlightStartInLine < highlightEndInLine && highlightEndInLine < this.charXPositions.size()) {
                                    int x1 = this.charXPositions.get(highlightStartInLine);
                                    int x2 = this.charXPositions.get(highlightEndInLine);
                                    drawSelectionBox(x1, yPos, x2, yPos + this.fontRendererObj.FONT_HEIGHT);
                                }
                            }
                        }

                        // --- 绘制光标 (如果光标在当前行) ---
                        if (i == cursorLineIndex && (this.cursorBlink / 6) % 2 == 0) {
                            drawCursor(yPos);
                        }
                    } else {
                        // 对于与光标/选区无关的行，我们还是用老方法快速绘制，以优化性能
                        this.fontRendererObj.drawStringWithShadow(line, (float)(this.textAreaX + PADDING), (float)yPos, 0xFFFFFF);
                    }
                }
                
                yPos += this.fontRendererObj.FONT_HEIGHT;
            }
        }
        
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.scroll_hint"), this.width / 2, this.height - 40, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    
    /**
     * V6核心方法：通过逐个字符绘制来获取每个字符的精确屏幕X坐标，并将它们缓存起来。
     * 此方法会直接完成文本绘制，避免重复渲染。
     */
    private void drawStringAndCachePositions(String text, int x, int y, int color) {
        this.charXPositions.clear();
        
        float currentX = (float) x;
        
        // 缓存字符串开始的位置（即第0个字符的起始位置）
        this.charXPositions.add(x); 

        for (int i = 0; i < text.length(); ++i) {
            char character = text.charAt(i);
            String s = String.valueOf(character);
            
            // 直接使用带阴影的方法进行绘制，这是最接近真实渲染的方式
            this.fontRendererObj.drawStringWithShadow(s, currentX, (float)y, color);
            
            // 获取刚刚绘制的字符的宽度，并更新下一个字符的起始X坐标
            // 即使有Optifine，单个字符的getStringWidth通常也是相对准确的，或者其误差与渲染误差方向一致
            currentX += this.fontRendererObj.getStringWidth(s);
            this.charXPositions.add((int)Math.round(currentX)); // 四舍五入以提高精度
        }
    }

    /**
     * V6版光标绘制：直接从缓存的精确坐标中读取位置。
     */
    private void drawCursor(int cursorY) {
        int lineIndex = findLineForPosition(this.cursorPosition);
        if (lineIndex < 0 || lineIndex >= this.renderedLines.size()) return;
        
        int lineStart = this.lineStartIndices[lineIndex];
        int posInLine = this.cursorPosition - lineStart;
        
        // 如果缓存有效且索引在范围内
        if (posInLine < this.charXPositions.size()) {
            int cursorX = this.charXPositions.get(posInLine);
            
            // 绘制一个1像素宽的光标
            drawRect(cursorX, cursorY - 1, cursorX + 1, cursorY + this.fontRendererObj.FONT_HEIGHT, 0xFFFFFFFF);
        }
    }
    
    private int findLineForPosition(int position) {
        if (this.lineStartIndices == null) return 0;
        
        for (int i = 0; i < this.lineStartIndices.length - 1; i++) {
            if (position >= this.lineStartIndices[i] && position < this.lineStartIndices[i + 1]) {
                return i;
            }
        }
        
        // 如果位置在最后一行
        if (this.renderedLines != null && !this.renderedLines.isEmpty() && position >= this.lineStartIndices[this.lineStartIndices.length - 1]) {
            return this.renderedLines.size() - 1;
        }
        
        return 0;
    }
    
    /**
     * 我们继续使用V4的换行逻辑，因为它在逻辑上是正确的，
     * 能为我们提供正确的 renderedLines 和 lineStartIndices。
     * 渲染时的视觉修正由V6的绘制方法完成。
     */
    private void updateLinesAndIndices() {
        if (this.textContent == null || this.fontRendererObj == null) {
            return;
        }

        this.renderedLines = Lists.newArrayList();
        List<Integer> indices = Lists.newArrayList();
        
        if (this.textContent.isEmpty()) {
            this.renderedLines.add("");
            this.lineStartIndices = new int[]{0, 0};
            return;
        }

        int modelIndex = 0;
        while (modelIndex < this.textContent.length()) {
            indices.add(modelIndex);

            String remainingText = this.textContent.substring(modelIndex);
            int lineLength = computeMaxCharsForWidth(remainingText, this.wrappingWidth);
            
            if (lineLength <= 0 && modelIndex < this.textContent.length()) {
                if (remainingText.charAt(0) == '\n') {
                    lineLength = 0;
                } else {
                    lineLength = 1;
                }
            }
            
            String lineContent = this.textContent.substring(modelIndex, modelIndex + lineLength);
            this.renderedLines.add(lineContent);
            
            modelIndex += lineLength;

            if (modelIndex < this.textContent.length() && this.textContent.charAt(modelIndex) == '\n') {
                modelIndex++;
            }
        }
        
        if (this.renderedLines.isEmpty() || (this.textContent.length() > 0 && this.textContent.endsWith("\n"))) {
            this.renderedLines.add("");
            indices.add(this.textContent.length());
        }

        this.lineStartIndices = new int[indices.size() + 1];
        for (int i = 0; i < indices.size(); i++) {
            this.lineStartIndices[i] = indices.get(i);
        }
        this.lineStartIndices[indices.size()] = this.textContent.length();
    }
    
    private int computeMaxCharsForWidth(String text, int width) {
        if (text.isEmpty()) {
            return 0;
        }
        
        int manualNewlinePos = text.indexOf('\n');
        
        for (int chars = 1; chars <= text.length(); ++chars) {
            if (manualNewlinePos != -1 && chars > manualNewlinePos) {
                return manualNewlinePos;
            }

            String sub = text.substring(0, chars);
            if (this.fontRendererObj.getStringWidth(sub) > width) {
                return chars - 1;
            }
        }
        
        if (manualNewlinePos != -1) {
            return manualNewlinePos;
        }
        return text.length();
    }
    
    private void insertText(String text) {
        if (hasSelection()) {
            deleteSelection();
        }
        
        String textToInsert = ChatAllowedCharacters.filterAllowedCharacters(text);
        if (textToInsert.isEmpty() && !text.equals("\n")) {
            return;
        }
        
        if (text.equals("\n")) {
            textToInsert = "\n";
        }
        
        StringBuilder sb = new StringBuilder(this.textContent);
        sb.insert(this.cursorPosition, textToInsert);
        this.textContent = sb.toString();
        
        this.cursorPosition += textToInsert.length();
        this.selectionAnchor = this.cursorPosition;
        this.cursorBlink = 0;
        
        updateLinesAndIndices();
    }
    
    private void deleteCharBackwards() {
        if (hasSelection()) {
            deleteSelection();
        } else if (this.cursorPosition > 0) {
            StringBuilder sb = new StringBuilder(this.textContent);
            sb.deleteCharAt(this.cursorPosition - 1);
            this.textContent = sb.toString();
            this.cursorPosition--;
            this.selectionAnchor = this.cursorPosition;
            this.cursorBlink = 0;
            updateLinesAndIndices();
        }
    }
    
    private void deleteSelection() {
        if (!hasSelection()) return;
        
        int start = getSelectionStart();
        int end = getSelectionEnd();
        
        StringBuilder sb = new StringBuilder(this.textContent);
        sb.delete(start, end);
        this.textContent = sb.toString();
        
        this.cursorPosition = start;
        this.selectionAnchor = start;
        this.cursorBlink = 0;
        
        updateLinesAndIndices();
    }
    
    private void moveCursorBy(int amount, boolean extendSelection) {
        int newPosition = this.cursorPosition + amount;
        setCursorPosition(newPosition, extendSelection);
    }
    
    private void setCursorPosition(int newPosition) {
        setCursorPosition(newPosition, false);
    }
    
    private void setCursorPosition(int newPosition, boolean extendSelection) {
        newPosition = Math.max(0, Math.min(this.textContent.length(), newPosition));
        this.cursorPosition = newPosition;
        if (!extendSelection) {
            this.selectionAnchor = this.cursorPosition;
        }
        this.cursorBlink = 0;
        
        ensureCursorVisible();
    }
    
    private void ensureCursorVisible() {
        int lineIndex = findLineForPosition(this.cursorPosition);
        if (lineIndex < 0) return;
        
        int cursorY = lineIndex * this.fontRendererObj.FONT_HEIGHT;
        int visibleTop = this.scrollOffset;
        int visibleBottom = this.scrollOffset + this.textAreaHeight - PADDING * 2;
        
        if (cursorY < visibleTop) {
            this.scrollOffset = cursorY;
        } else if (cursorY + this.fontRendererObj.FONT_HEIGHT > visibleBottom) {
            this.scrollOffset = cursorY + this.fontRendererObj.FONT_HEIGHT - this.textAreaHeight + PADDING * 2;
        }
        
        this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset));
    }
    
    private void selectAll() {
        this.selectionAnchor = 0;
        this.cursorPosition = this.textContent.length();
        this.cursorBlink = 0;
    }
    
    private boolean hasSelection() {
        return this.cursorPosition != this.selectionAnchor;
    }
    
    private int getSelectionStart() {
        return Math.min(this.cursorPosition, this.selectionAnchor);
    }
    
    private int getSelectionEnd() {
        return Math.max(this.cursorPosition, this.selectionAnchor);
    }
    
    private String getSelectedText() {
        return hasSelection() ? this.textContent.substring(getSelectionStart(), getSelectionEnd()) : "";
    }

    private void drawSelectionBox(int startX, int startY, int endX, int endY) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        GlStateManager.color(0.0F, 0.0F, 1.0F, 0.5F);
        GlStateManager.disableTexture2D();
        GlStateManager.enableColorLogic();
        GlStateManager.colorLogicOp(5387);
        worldrenderer.begin(7, DefaultVertexFormats.POSITION);
        worldrenderer.pos((double)startX, (double)endY, 0.0D).endVertex();
        worldrenderer.pos((double)endX, (double)endY, 0.0D).endVertex();
        worldrenderer.pos((double)endX, (double)startY, 0.0D).endVertex();
        worldrenderer.pos((double)startX, (double)startY, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.disableColorLogic();
        GlStateManager.enableTexture2D();
    }
    
    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0 &&
            mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth &&
            mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
            
            int charIndex = getCharIndexAt(mouseX, mouseY);
            this.selectionAnchor = charIndex;
            this.cursorPosition = charIndex;
            this.cursorBlink = 0;
        }
    }
    
    /**
     * V6版字符索引查找：同样依赖于实时渲染和坐标缓存来找到鼠标点击位置。
     */
    private int getCharIndexAt(int mouseX, int mouseY) {
        if (this.renderedLines == null || this.renderedLines.isEmpty()) {
            return 0;
        }

        int relativeY = mouseY - this.textAreaY - PADDING + this.scrollOffset;
        int clickedLineIndex = relativeY / this.fontRendererObj.FONT_HEIGHT;

        if (clickedLineIndex < 0) return 0;
        if (clickedLineIndex >= this.renderedLines.size()) return this.textContent.length();

        String clickedLine = this.renderedLines.get(clickedLineIndex);
        int lineY = this.textAreaY + PADDING - this.scrollOffset + (clickedLineIndex * this.fontRendererObj.FONT_HEIGHT);
        
        // 为了定位，我们需要在后台计算这一行的坐标缓存
        // 我们传入一个假的颜色(0)并且不实际绘制，只为了计算坐标
        this.charXPositions.clear();
        float currentX = (float) (this.textAreaX + PADDING);
        this.charXPositions.add((int)currentX); 
        for (int i = 0; i < clickedLine.length(); ++i) {
            currentX += this.fontRendererObj.getStringWidth(String.valueOf(clickedLine.charAt(i)));
            this.charXPositions.add((int)Math.round(currentX));
        }

        // 寻找离鼠标X坐标最近的字符边界
        int bestIndexInLine = 0;
        int minDistance = Integer.MAX_VALUE;
        for (int i = 0; i < this.charXPositions.size(); i++) {
            int charX = this.charXPositions.get(i);
            int distance = Math.abs(mouseX - charX);
            if (distance < minDistance) {
                minDistance = distance;
                bestIndexInLine = i;
            }
        }
        
        return this.lineStartIndices[clickedLineIndex] + bestIndexInLine;
    }
}