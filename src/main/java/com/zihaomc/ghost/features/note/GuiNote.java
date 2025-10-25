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
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/**
 * 游戏内笔记的GUI界面。
 */
public class GuiNote extends GuiScreen {

    // MARK: - 成员变量

    /** 存储笔记的完整文本内容 */
    private String textContent = "";
    /** 光标在textContent中的字符索引位置 */
    private int cursorPosition = 0;
    /** 文本选择的锚点位置，用于实现Shift+方向键选择文本 */
    private int selectionAnchor = 0;
    
    /** 文本区域的垂直滚动偏移量 */
    private int scrollOffset = 0;
    /** 文本内容可滚动的最大距离 */
    private int maxScroll = 0;

    /** 文本区域在屏幕上的坐标和尺寸 */
    private int textAreaX, textAreaY, textAreaWidth, textAreaHeight;
    /** 用于控制光标闪烁的计时器 */
    private int cursorBlink;

    /** 文本区域的内边距 */
    private static final int PADDING = 4;
    /** 文本自动换行的宽度，等于文本区域宽度减去两倍的内边距 */
    private int wrappingWidth;
    
    /** 经过自动换行处理后，用于渲染的每一行文本的列表 */
    private List<String> renderedLines;
    /** 一个数组，存储renderedLines中每一行在原始textContent中的起始字符索引 */
    private int[] lineStartIndices;

    /** 
     * V6/V7核心：用于缓存当前渲染行中每个字符的精确屏幕X坐标。
     * 这是对抗Optifine渲染不一致性的关键。
     */
    private final List<Integer> charXPositions = new ArrayList<>();
    
    /** Markdown 渲染功能的开关按钮 */
    private GuiButton markdownToggleButton;

    // MARK: - GUI生命周期方法

    /**
     * GUI初始化时调用。
     * 设置尺寸、加载笔记内容、创建按钮。
     */
    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true); // 允许长按键盘时连续输入
        this.textAreaX = this.width / 2 - 150;
        this.textAreaY = 40;
        this.textAreaWidth = 300;
        this.textAreaHeight = this.height - 90;
        this.wrappingWidth = this.textAreaWidth - PADDING * 2; 
        
        // 根据配置项决定加载逻辑
        if (!GhostConfig.fixGuiStateLossOnResize) {
            // 配置关闭时：强制从文件重新加载，这会覆盖掉任何未保存的修改，从而“实现”了状态丢失。
            this.textContent = NoteManager.loadNote();
        } else {
            // 配置开启时：只有在文本内容确实为空（例如首次打开）时才加载。
            // 状态的恢复将由外部的事件处理器来完成。
            if (this.textContent.isEmpty()) {
                this.textContent = NoteManager.loadNote();
            }
        }
        
        updateLinesAndIndices(); // 根据加载的文本内容计算换行
        setCursorPosition(this.textContent.length()); // 将光标置于末尾
        
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 25, LangUtil.translate("ghost.gui.note.save_and_close")));

        // 创建 Markdown 开关按钮
        int buttonWidth = 120;
        int buttonHeight = 20;
        // X 坐标：文本框左侧 - 按钮宽度 - 5像素间距
        // Y 坐标：与文本框的 Y 坐标 (textAreaY) 对齐
        this.markdownToggleButton = new GuiButton(1, this.textAreaX - buttonWidth - 5, this.textAreaY, buttonWidth, buttonHeight, "");
        updateMarkdownButtonText(); // 根据当前配置设置按钮文本
        this.buttonList.add(this.markdownToggleButton);
    }

    /**
     * GUI关闭时调用。
     * 保存笔记内容并清理资源。
     */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false); // 关闭键盘连续输入
        NoteManager.saveNote(this.textContent);
    }
    
    /**
     * 每tick更新一次，用于动画等。
     * 这里只用于更新光标的闪烁计时器。
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        this.cursorBlink++;
    }

    // MARK: - 渲染方法

    /**
     * 每一帧都调用的主绘制方法。
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 绘制背景和UI框架
        this.drawDefaultBackground();
        // 确保标题始终在屏幕宽度的一半位置，实现真正的居中
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.title"), this.width / 2, 20, 0xFFFFFF);
        drawRect(textAreaX - 1, textAreaY - 1, textAreaX + textAreaWidth + 1, textAreaY + textAreaHeight + 1, 0xFFC0C0C0);
        drawRect(textAreaX, textAreaY, textAreaX + textAreaWidth, textAreaY + textAreaHeight, 0xFF000000);
        
        // 开启剪裁测试，只在文本区域内绘制文本
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int scaleFactor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        GL11.glScissor(textAreaX * scaleFactor, mc.displayHeight - (textAreaY + textAreaHeight) * scaleFactor, textAreaWidth * scaleFactor, textAreaHeight * scaleFactor);
        
        if (this.renderedLines != null) {
            // 计算滚动条范围
            this.maxScroll = Math.max(0, this.renderedLines.size() * this.fontRendererObj.FONT_HEIGHT - this.textAreaHeight + PADDING);
            this.scrollOffset = Math.min(this.maxScroll, Math.max(0, this.scrollOffset));

            int yPos = this.textAreaY + PADDING - this.scrollOffset;
            int selStart = getSelectionStart();
            int selEnd = getSelectionEnd();
            int cursorLineIndex = findLineForPosition(this.cursorPosition);

            // 遍历所有需要渲染的行
            for (int i = 0; i < this.renderedLines.size(); i++) {
                String line = this.renderedLines.get(i);
                
                // 优化：只对屏幕上可见的行进行绘制和计算
                if (yPos + this.fontRendererObj.FONT_HEIGHT > this.textAreaY && yPos < this.textAreaY + this.textAreaHeight) {
                    
                    // --- V7核心改动：统一渲染路径 ---
                    // 无论如何，都使用我们自定义的逐字渲染方法来绘制文本并缓存坐标。
                    // 这确保了所有行的视觉外观都是一致的，解决了切换行时光标/字体间距变化的BUG。
                    drawStringAndCachePositions(line, this.textAreaX + PADDING, yPos, 0xFFFFFF);

                    // --- 绘制选区 (如果需要) ---
                    if (hasSelection()) {
                        int lineStart = this.lineStartIndices[i];
                        int lineEnd = (i + 1 < this.lineStartIndices.length) ? this.lineStartIndices[i+1] : this.textContent.length();
                        
                        // 检查当前行是否与选区有交集
                        if (selEnd > lineStart && selStart < lineEnd) {
                            int highlightStartInText = Math.max(selStart, lineStart);
                            int highlightEndInText = Math.min(selEnd, lineEnd);
                            int highlightStartInLine = highlightStartInText - lineStart;
                            int highlightEndInLine = highlightEndInText - lineStart;

                            if (highlightStartInLine < highlightEndInLine && highlightEndInLine < this.charXPositions.size()) {
                                int x1 = this.charXPositions.get(highlightStartInLine);
                                int x2 = this.charXPositions.get(highlightEndInLine);
                                drawSelectionBox(x1, yPos, x2, yPos + this.fontRendererObj.FONT_HEIGHT);
                            }
                        }
                    }

                    // --- 绘制光标 (如果需要) ---
                    if (i == cursorLineIndex && (this.cursorBlink / 6) % 2 == 0) {
                        drawCursor(yPos);
                    }
                }
                
                yPos += this.fontRendererObj.FONT_HEIGHT;
            }
        }
        
        // 关闭剪裁测试
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        
        // 绘制底部提示文字和按钮
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.scroll_hint"), this.width / 2, this.height - 40, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    
    /**
     * V7核心方法：通过逐个字符绘制来获取每个字符的精确屏幕X坐标，并将它们缓存起来。
     * 此方法现在是所有行文本的唯一渲染器，以保证视觉一致性。
     * V8更新：添加了对Markdown格式的支持，同时保持了精确的光标位置计算。
     */
    private void drawStringAndCachePositions(String text, int x, int y, int color) {
        // 渲染逻辑重构，以同时支持 Minecraft 颜色代码 (§) 和 Markdown
        this.charXPositions.clear();
        float currentX = (float) x;

        String activeMinecraftFormat = ""; // 用于追踪 § 颜色/格式代码
        boolean isBold = false;
        boolean isItalic = false;
        boolean isStrikethrough = false;
        
        for (int i = 0; i < text.length(); ++i) {
            // 缓存每个字符（包括格式符）的起始X坐标
            this.charXPositions.add((int)Math.round(currentX));
            
            char currentChar = text.charAt(i);
            
            // 优先处理 Minecraft 颜色代码
            if (currentChar == '§' && i + 1 < text.length()) {
                char formatChar = text.toLowerCase().charAt(i + 1);
                if ("0123456789abcdefklmnor".indexOf(formatChar) != -1) {
                    if (formatChar == 'r') { // 重置代码
                        activeMinecraftFormat = "";
                        isBold = isItalic = isStrikethrough = false;
                    } else {
                        activeMinecraftFormat += "§" + formatChar;
                    }
                    i++; // 跳过格式字符
                    this.charXPositions.add((int)Math.round(currentX)); // 格式符本身是零宽度的
                    continue;
                }
            }
            
            // 如果禁用了 Markdown，则跳过 Markdown 解析
            if (GhostConfig.enableMarkdownRendering) {
                char nextChar = (i + 1 < text.length()) ? text.charAt(i + 1) : '\0';
                if (currentChar == '*' && nextChar == '*') {
                    isBold = !isBold;
                    i++; 
                    this.charXPositions.add((int)Math.round(currentX));
                    continue;
                } else if (currentChar == '~' && nextChar == '~') {
                    isStrikethrough = !isStrikethrough;
                    i++;
                    this.charXPositions.add((int)Math.round(currentX));
                    continue;
                } else if (currentChar == '*') {
                    isItalic = !isItalic;
                    continue;
                }
            }

            // 构建最终的格式化字符串
            StringBuilder finalFormat = new StringBuilder(activeMinecraftFormat);
            if (isItalic) finalFormat.append(EnumChatFormatting.ITALIC);
            if (isBold) finalFormat.append(EnumChatFormatting.BOLD);
            if (isStrikethrough) finalFormat.append(EnumChatFormatting.STRIKETHROUGH);
            
            String charToRender = finalFormat.toString() + currentChar;

            // 绘制带格式的单个字符
            this.fontRendererObj.drawStringWithShadow(charToRender, currentX, (float)y, color);
            
            // 关键：计算字符宽度时要排除格式化代码本身的影响，以保证定位准确
            int fullWidth = this.fontRendererObj.getStringWidth(charToRender);
            int formatWidth = this.fontRendererObj.getStringWidth(finalFormat.toString());
            currentX += (fullWidth - formatWidth);
        }
        
        // 为字符串末尾的光标位置添加最后的坐标
        this.charXPositions.add((int)Math.round(currentX));
    }

    /**
     * V7版光标绘制：直接从缓存的精确坐标中读取位置。
     */
    private void drawCursor(int cursorY) {
        int lineIndex = findLineForPosition(this.cursorPosition);
        if (lineIndex < 0 || lineIndex >= this.renderedLines.size()) return;
        
        int lineStart = this.lineStartIndices[lineIndex];
        int posInLine = this.cursorPosition - lineStart;
        
        // 从缓存中获取精确的X坐标
        if (posInLine < this.charXPositions.size()) {
            int cursorX = this.charXPositions.get(posInLine);
            drawRect(cursorX, cursorY - 1, cursorX + 1, cursorY + this.fontRendererObj.FONT_HEIGHT, 0xFFFFFFFF);
        }
    }

    // MARK: - 输入处理方法

    @Override
    public void handleKeyboardInput() throws IOException {
        // 优先处理回车键，因为它在 keyTyped 中行为不一致
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_RETURN) {
            this.insertText("\n");
            return;
        }
        super.handleKeyboardInput();
    }
    
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { this.mc.displayGuiScreen(null); return; }
        // 处理Ctrl+A/C/V/X等组合键
        if (GhostConfig.enableAdvancedEditing && GuiScreen.isCtrlKeyDown()) {
            if (keyCode == Keyboard.KEY_A) { selectAll(); return; }
            if (keyCode == Keyboard.KEY_C) { GuiScreen.setClipboardString(getSelectedText()); return; }
            if (keyCode == Keyboard.KEY_X) { GuiScreen.setClipboardString(getSelectedText()); deleteSelection(); return; }
            if (keyCode == Keyboard.KEY_V) { insertText(GuiScreen.getClipboardString()); return; }
        }
        // 处理功能键
        switch (keyCode) {
            case Keyboard.KEY_BACK:
                if (hasSelection()) deleteSelection();
                else if (this.cursorPosition > 0) deleteCharBackwards();
                return;
            case Keyboard.KEY_DELETE:
                if (hasSelection()) deleteSelection();
                else if (this.cursorPosition < this.textContent.length()) {
                    this.textContent = new StringBuilder(this.textContent).deleteCharAt(this.cursorPosition).toString();
                    updateLinesAndIndices();
                }
                return;
            case Keyboard.KEY_LEFT: moveCursorBy(-1, GuiScreen.isShiftKeyDown()); return;
            case Keyboard.KEY_RIGHT: moveCursorBy(1, GuiScreen.isShiftKeyDown()); return;
            case Keyboard.KEY_HOME: setCursorPosition(0, GuiScreen.isShiftKeyDown()); return;
            case Keyboard.KEY_END: setCursorPosition(this.textContent.length(), GuiScreen.isShiftKeyDown()); return;
            case Keyboard.KEY_RETURN: insertText("\n"); return;
        }
        // 处理可打印字符，并允许输入 § 符号
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) || typedChar == '§') {
            insertText(Character.toString(typedChar));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0 && mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth && mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
            // 将鼠标点击的屏幕坐标转换为文本索引
            int charIndex = getCharIndexAt(mouseX, mouseY);
            this.selectionAnchor = charIndex;
            this.cursorPosition = charIndex;
            this.cursorBlink = 0;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton == 0 && mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth && mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight) {
            // 鼠标拖动时更新光标位置以实现选择
            int charIndex = getCharIndexAt(mouseX, mouseY);
            this.cursorPosition = charIndex;
            this.cursorBlink = 0;
        }
    }
    
    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        // 处理鼠标滚轮事件
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            int scrollAmount = this.fontRendererObj.FONT_HEIGHT * 3 * (dWheel < 0 ? 1 : -1);
            this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset + scrollAmount));
        }
    }
    
    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        // 处理按钮点击事件
        if(button.enabled) {
            if (button.id == 0) {
                this.mc.displayGuiScreen(null);
            }
            else if (button.id == 1) {
                // 切换 Markdown 配置
                GhostConfig.setEnableMarkdownRendering(!GhostConfig.enableMarkdownRendering);
                // 更新按钮文本以反映新状态
                updateMarkdownButtonText();
            }
        }
        super.actionPerformed(button);
    }
    
    /**
     * 更新 Markdown 开关按钮的显示文本，以反映当前的配置状态。
     */
    private void updateMarkdownButtonText() {
        if (this.markdownToggleButton != null) {
            String prefix = LangUtil.translate("ghost.gui.note.markdown.prefix");
            String status = GhostConfig.enableMarkdownRendering ?
                    LangUtil.translate("ghost.generic.enabled") :
                    LangUtil.translate("ghost.generic.disabled");
            this.markdownToggleButton.displayString = prefix + status;
        }
    }
    
    // MARK: - 文本/光标/换行核心逻辑

    /**
     * 将原始textContent字符串根据宽度分割成多行，并计算每行的起始索引。
     * 这是“逻辑”上的换行，为渲染做准备。
     */
    private void updateLinesAndIndices() {
        if (this.textContent == null || this.fontRendererObj == null) return;
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
                if (remainingText.charAt(0) == '\n') lineLength = 0;
                else lineLength = 1;
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
    
    /**
     * 一个辅助方法，计算在给定宽度内，一个字符串从头开始最多能容纳多少个字符。
     * @return 字符数量
     */
    private int computeMaxCharsForWidth(String text, int width) {
        if (text.isEmpty()) return 0;
        int manualNewlinePos = text.indexOf('\n');
        for (int chars = 1; chars <= text.length(); ++chars) {
            if (manualNewlinePos != -1 && chars > manualNewlinePos) return manualNewlinePos;
            // 计算宽度时，需要考虑 § 颜色代码，它们不计入宽度
            String sub = text.substring(0, chars);
            if (this.fontRendererObj.getStringWidth(sub) > width) {
                // 如果超宽了，需要确保我们没有在 § 符号和它的代码之间断开
                if (chars > 1 && text.charAt(chars - 2) == '§') {
                    return chars - 2;
                }
                return chars - 1;
            }
        }
        if (manualNewlinePos != -1) return manualNewlinePos;
        return text.length();
    }
    
    /**
     * V7版，将屏幕坐标(x,y)转换为textContent中的字符索引。
     * 依赖于实时计算的字符坐标缓存。
     */
    private int getCharIndexAt(int mouseX, int mouseY) {
        if (this.renderedLines == null || this.renderedLines.isEmpty()) return 0;
        int relativeY = mouseY - this.textAreaY - PADDING + this.scrollOffset;
        int clickedLineIndex = relativeY / this.fontRendererObj.FONT_HEIGHT;
        if (clickedLineIndex < 0) return 0;
        if (clickedLineIndex >= this.renderedLines.size()) return this.textContent.length();
        String clickedLine = this.renderedLines.get(clickedLineIndex);
        
        // 在后台计算被点击行的字符坐标，以找到最近的插入点
        // (调用这个方法会填充 charXPositions 缓存)
        drawStringAndCachePositions(clickedLine, this.textAreaX + PADDING, -9999, 0); // Y坐标不重要，我们只需要X坐标
        
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

    /**
     * 根据给定的字符索引，找到它属于哪一行。
     * @return 行的索引
     */
    private int findLineForPosition(int position) {
        if (this.lineStartIndices == null) return 0;
        for (int i = 0; i < this.lineStartIndices.length - 1; i++) {
            if (position >= this.lineStartIndices[i] && position < this.lineStartIndices[i + 1]) return i;
        }
        if (this.renderedLines != null && !this.renderedLines.isEmpty() && position >= this.lineStartIndices[this.lineStartIndices.length - 1]) {
            return this.renderedLines.size() - 1;
        }
        return 0;
    }

    // MARK: - 文本和光标操作方法

    private void insertText(String text) {
        if (hasSelection()) deleteSelection();
        // 修改过滤器，以允许 § 符号及其后的格式代码
        String textToInsert;
        if (text.equals("\n")) {
            textToInsert = "\n";
        } else {
            StringBuilder filtered = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '§' && i + 1 < text.length()) {
                    char formatChar = text.toLowerCase().charAt(i + 1);
                    if ("0123456789abcdefklmnor".indexOf(formatChar) != -1) {
                        filtered.append('§').append(text.charAt(i + 1));
                        i++;
                    }
                } else if (ChatAllowedCharacters.isAllowedCharacter(c)) {
                    filtered.append(c);
                }
            }
            textToInsert = filtered.toString();
        }
        if (textToInsert.isEmpty()) return;
        
        StringBuilder sb = new StringBuilder(this.textContent);
        sb.insert(this.cursorPosition, textToInsert);
        this.textContent = sb.toString();
        this.cursorPosition += textToInsert.length();
        this.selectionAnchor = this.cursorPosition;
        this.cursorBlink = 0;
        updateLinesAndIndices();
    }
    
    private void deleteCharBackwards() {
        if (hasSelection()) deleteSelection();
        else if (this.cursorPosition > 0) {
            // 如果光标前是颜色代码，则一次性删除两个字符 (§ 和代码)
            int numToDelete = 1;
            if (this.cursorPosition > 1 && this.textContent.charAt(this.cursorPosition - 2) == '§') {
                numToDelete = 2;
            }
            StringBuilder sb = new StringBuilder(this.textContent);
            sb.delete(this.cursorPosition - numToDelete, this.cursorPosition);
            this.textContent = sb.toString();
            this.cursorPosition -= numToDelete;
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
        // 调整光标移动，使其能跳过颜色代码
        int newPos = this.cursorPosition;
        if (amount < 0) { // 向左移动
            newPos = Math.max(0, this.cursorPosition + amount);
            if (newPos > 0 && this.textContent.charAt(newPos - 1) == '§') {
                newPos--;
            }
        } else { // 向右移动
            newPos = Math.min(this.textContent.length(), this.cursorPosition + amount);
            if (newPos < this.textContent.length() - 1 && this.textContent.charAt(newPos) == '§') {
                newPos++;
            }
        }
        setCursorPosition(newPos, extendSelection);
    }
    
    private void setCursorPosition(int newPosition) { setCursorPosition(newPosition, false); }
    
    private void setCursorPosition(int newPosition, boolean extendSelection) {
        newPosition = Math.max(0, Math.min(this.textContent.length(), newPosition));
        this.cursorPosition = newPosition;
        if (!extendSelection) this.selectionAnchor = this.cursorPosition;
        this.cursorBlink = 0;
        ensureCursorVisible();
    }
    
    /** 确保光标始终在可见的文本区域内，如果超出则自动滚动 */
    private void ensureCursorVisible() {
        int lineIndex = findLineForPosition(this.cursorPosition);
        if (lineIndex < 0) return;
        int cursorY = lineIndex * this.fontRendererObj.FONT_HEIGHT;
        int visibleTop = this.scrollOffset;
        int visibleBottom = this.scrollOffset + this.textAreaHeight - PADDING * 2;
        if (cursorY < visibleTop) this.scrollOffset = cursorY;
        else if (cursorY + this.fontRendererObj.FONT_HEIGHT > visibleBottom) {
            this.scrollOffset = cursorY + this.fontRendererObj.FONT_HEIGHT - this.textAreaHeight + PADDING * 2;
        }
        this.scrollOffset = Math.max(0, Math.min(this.maxScroll, this.scrollOffset));
    }
    
    private void selectAll() {
        this.selectionAnchor = 0;
        this.cursorPosition = this.textContent.length();
        this.cursorBlink = 0;
    }
    
    private boolean hasSelection() { return this.cursorPosition != this.selectionAnchor; }
    
    private int getSelectionStart() { return Math.min(this.cursorPosition, this.selectionAnchor); }
    
    private int getSelectionEnd() { return Math.max(this.cursorPosition, this.selectionAnchor); }
    
    private String getSelectedText() {
        return hasSelection() ? this.textContent.substring(getSelectionStart(), getSelectionEnd()) : "";
    }

    /** 使用Tessellator绘制蓝色半透明的选区矩形 */
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

    /**
     * [新增] 获取当前笔记的完整文本内容。
     * @return 笔记的字符串内容。
     */
    public String getTextContent() {
        return this.textContent;
    }

    /**
     * [新增] 设置笔记的文本内容并刷新GUI状态。
     * 用于在GUI重建后恢复文本。
     * @param newText 要设置的新文本。
     */
    public void setTextContentAndInitialize(String newText) {
        if (newText != null) {
            this.textContent = newText;
            // 这两个调用对于在设置文本后正确更新视图和光标至关重要
            updateLinesAndIndices();
            setCursorPosition(this.textContent.length());
        }
    }
}