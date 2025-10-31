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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 游戏内笔记的GUI界面。
 * Final Version (V7) - Unified Renderer for Visual Consistency with Optifine
 * 最终版本 (V7) - 使用统一渲染器以确保在Optifine环境下的视觉一致性
 * @version V8.3 - Final Button Position Adjustment
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
    /** 颜色渲染功能开关按钮 */
    private GuiButton colorToggleButton;
    /** 新增：& 颜色代码功能开关按钮 */
    private GuiButton ampersandToggleButton;
    /** 帮助按钮 */
    private GuiButton helpButton;
    /** 撤销按钮 */
    private GuiButton undoButton;
    /** 重做按钮 */
    private GuiButton redoButton;

    // --- 智慧型撤销/重做相关变量 ---
    private long lastEditTime = 0L; // 上次编辑的时间戳
    private long lastForcedSaveTime = 0L; // 上次强制保存的时间戳
    private static final long EDIT_MERGE_INTERVAL = 1000L; // 连续编辑的合并间隔（毫秒），1秒
    private static final long FORCED_SAVE_INTERVAL = 5000L; // 强制保存的最长时间间隔（毫秒），5秒
    private static final int LARGE_INPUT_THRESHOLD = 5; // 单次输入超过该字元数则立即保存
    private boolean isTypingAction = false; // 标记当前是否正在进行连续输入

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
        
        // --- 修正后的加载逻辑 ---
        // 外部的 KeybindHandler 会在调整大小时（如果fix功能开启）预先填充 textContent。
        // 因此，我们只在 textContent 仍然为空时执行加载逻辑。
        if (this.textContent.isEmpty()) {
            // 如果历史记录不为空，说明是延续上一次的编辑会话，从历史记录恢复
            if (!NoteManager.undoStack.isEmpty()) {
                this.textContent = NoteManager.undoStack.peek();
            } else {
                // 否则，这是一个全新的会话（例如游戏刚启动），从文件加载
                this.textContent = NoteManager.loadNote();
            }
        }
        
        // 如果修复功能被强制关闭，则无论如何都从文件重新加载，以模拟原版的状态丢失行为
        if (!GhostConfig.GuiTweaks.fixGuiStateLossOnResize) {
            this.textContent = NoteManager.loadNote();
        }
        
        updateLinesAndIndices(); // 根据加载的文本内容计算换行
        setCursorPosition(this.textContent.length()); // 将光标置于末尾
        
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 25, LangUtil.translate("ghost.gui.note.save_and_close")));

        // 创建功能开关按钮
        int buttonWidth = 120;
        int buttonHeight = 20;
        
        // 左侧按钮
        int leftButtonX = this.textAreaX - buttonWidth - 5;
        this.markdownToggleButton = new GuiButton(1, leftButtonX, this.textAreaY, buttonWidth, buttonHeight, "");
        updateMarkdownButtonText();
        this.buttonList.add(this.markdownToggleButton);
        
        this.colorToggleButton = new GuiButton(2, leftButtonX, this.textAreaY + buttonHeight + 5, buttonWidth, buttonHeight, "");
        updateColorButtonText();
        this.buttonList.add(this.colorToggleButton);
        
        this.ampersandToggleButton = new GuiButton(6, leftButtonX, this.textAreaY + (buttonHeight + 5) * 2, buttonWidth, buttonHeight, "");
        updateAmpersandButtonText();
        this.buttonList.add(this.ampersandToggleButton);

        // 右侧按钮
        int rightButtonX = this.textAreaX + this.textAreaWidth + 5;
        this.helpButton = new GuiButton(3, rightButtonX, this.textAreaY, 20, 20, "?");
        this.buttonList.add(this.helpButton);

        // 新增撤销/重做按钮，调整了按钮间距
        int smallButtonGap = 2; // 右侧小按钮之间的间距
        this.undoButton = new GuiButton(4, rightButtonX, this.textAreaY + buttonHeight + 5, 20, 20, "<");
        this.redoButton = new GuiButton(5, rightButtonX + 20 + smallButtonGap, this.textAreaY + buttonHeight + 5, 20, 20, ">");
        this.buttonList.add(this.undoButton);
        this.buttonList.add(this.redoButton);
        updateUndoRedoButtonState();
    }

    /**
     * GUI关闭时调用。
     * 保存笔记内容并清理资源。
     */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false); // 关闭键盘连续输入
        // 确保在关闭前，将最后一次连续输入的状态保存到历史记录中
        commitTypingAction();
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

        long now = System.currentTimeMillis();
        // 检查连续输入是否超时，如果超时则提交本次输入作为一个历史记录
        if (isTypingAction && now - lastEditTime > EDIT_MERGE_INTERVAL) {
            commitTypingAction();
            updateUndoRedoButtonState();
        }
        
        // 检查是否需要强制保存历史记录
        if (isTypingAction && now - lastForcedSaveTime > FORCED_SAVE_INTERVAL) {
            commitTypingAction();
            updateUndoRedoButtonState();
        }
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
     * 最终修正版的核心渲染方法。
     * 它能正确处理所有格式指令（Markdown, 颜色代码）和普通字符，确保光标定位精确无误。
     */
    private void drawStringAndCachePositions(String text, int x, int y, int color) {
        this.charXPositions.clear();
        float currentX = (float) x;
        
        String lineToRender = text;
        float scale = 1.0f;
        boolean isBoldTitle = false;

        // 仅在 Markdown 开启时处理标题和列表
        if (GhostConfig.NoteTaking.enableMarkdownRendering) {
            if (lineToRender.startsWith("# ")) {
                scale = 1.5f; isBoldTitle = true; lineToRender = lineToRender.substring(2);
                this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX);
            } else if (lineToRender.startsWith("## ")) {
                scale = 1.2f; isBoldTitle = true; lineToRender = lineToRender.substring(3);
                this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX);
            } else if (lineToRender.startsWith("### ")) {
                scale = 1.0f; isBoldTitle = true; lineToRender = lineToRender.substring(4);
                this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX); this.charXPositions.add((int)currentX);
            } else if (lineToRender.startsWith("- ") || lineToRender.startsWith("* ")) {
                String bullet = "• ";
                this.fontRendererObj.drawStringWithShadow(bullet, currentX, (float)y, color);
                currentX += this.fontRendererObj.getStringWidth(bullet);
                lineToRender = lineToRender.substring(2);
                this.charXPositions.add((int)x); this.charXPositions.add((int)x);
            }
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(currentX, y, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-currentX, -y, 0);

        String activeMinecraftFormat = "";
        boolean isBold = false;
        boolean isItalic = false;
        boolean isStrikethrough = false;
        
        for (int i = 0; i < lineToRender.length();) {
            this.charXPositions.add((int)Math.round(currentX));
            
            char currentChar = lineToRender.charAt(i);
            char nextChar = (i + 1 < lineToRender.length()) ? lineToRender.charAt(i + 1) : '\0';

            // 检查是否是有效的格式化指令组合
            boolean isColorPrefix = (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&') || currentChar == '§';
            boolean isColorCode = GhostConfig.NoteTaking.enableColorRendering && isColorPrefix && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(nextChar)) != -1;
            boolean isBoldMarkdown = GhostConfig.NoteTaking.enableMarkdownRendering && currentChar == '*' && nextChar == '*';
            boolean isStrikeMarkdown = GhostConfig.NoteTaking.enableMarkdownRendering && currentChar == '~' && nextChar == '~';
            boolean isItalicMarkdown = GhostConfig.NoteTaking.enableMarkdownRendering && currentChar == '*';

            if (isColorCode) {
                if (Character.toLowerCase(nextChar) == 'r') {
                    activeMinecraftFormat = ""; isBold = isItalic = isStrikethrough = false;
                } else {
                    activeMinecraftFormat += "§" + nextChar;
                }
                i += 2;
                this.charXPositions.add((int)Math.round(currentX));
            } else if (isBoldMarkdown) {
                isBold = !isBold; i += 2;
                this.charXPositions.add((int)Math.round(currentX));
            } else if (isStrikeMarkdown) {
                isStrikethrough = !isStrikethrough; i += 2;
                this.charXPositions.add((int)Math.round(currentX));
            } else if (isItalicMarkdown) {
                isItalic = !isItalic; i += 1;
            } else {
                StringBuilder finalFormat = new StringBuilder(activeMinecraftFormat);
                if (isItalic) finalFormat.append(EnumChatFormatting.ITALIC);
                if (isBold || isBoldTitle) finalFormat.append(EnumChatFormatting.BOLD);
                if (isStrikethrough) finalFormat.append(EnumChatFormatting.STRIKETHROUGH);
                
                String formatPrefix = finalFormat.toString();
                String charToRenderWithFormat = formatPrefix + currentChar;

                this.fontRendererObj.drawStringWithShadow(charToRenderWithFormat, currentX, (float)y, color);
                
                int charWidth;
                // 对 § 和 & 符号进行特殊宽度处理，以确保光标正常移动
                if (currentChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&')) {
                    charWidth = this.fontRendererObj.getCharWidth('s');
                } else {
                    charWidth = this.fontRendererObj.getStringWidth(charToRenderWithFormat) - this.fontRendererObj.getStringWidth(formatPrefix);
                }
                
                currentX += charWidth;
                i++;
            }
        }
        
        this.charXPositions.add((int)Math.round(currentX));
        
        GlStateManager.popMatrix();
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
            
            // 修正光标的Y坐标以适应缩放
            String line = this.renderedLines.get(lineIndex);
            float scale = 1.0f;
            if (GhostConfig.NoteTaking.enableMarkdownRendering) {
                if (line.startsWith("# ")) scale = 1.5f;
                else if (line.startsWith("## ")) scale = 1.2f;
            }
            float scaledFontHeight = this.fontRendererObj.FONT_HEIGHT * scale;

            drawRect(cursorX, cursorY - 1, cursorX + 1, (int)(cursorY -1 + scaledFontHeight), 0xFFFFFFFF);
        }
    }

    // MARK: - 输入处理方法

    /**
     * 覆写 handleKeyboardInput 来拦截更高优先级的键盘事件。
     */
    @Override
    public void handleKeyboardInput() throws IOException {
        // 检查按键是否被按下
        if (Keyboard.getEventKeyState()) {
            // 根据配置拦截 @ 键，阻止 Twitch 窗口
            if (GhostConfig.ChatFeatures.disableTwitchAtKey && Keyboard.getEventCharacter() == '@') {
                this.saveStateForUndo(true); // @ 也是一种输入
                this.insertText("@");
                return; // 消耗此事件，不让 super 处理
            }
            // 优先处理回车键，因为它在 keyTyped 中行为不一致
            if (Keyboard.getEventKey() == Keyboard.KEY_RETURN) {
                this.saveStateForUndo(false); // 回车视为“大动作”
                this.insertText("\n");
                return;
            }
        }
        
        // 对于所有其他按键，调用父类的方法来处理
        super.handleKeyboardInput();
    }
    
    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 注意：@ 和回车键的逻辑已经移动到 handleKeyboardInput 中，这里不再需要处理它们
        
        if (keyCode == Keyboard.KEY_ESCAPE) { this.mc.displayGuiScreen(null); return; }
        // 处理Ctrl+A/C/V/X等组合键，以及新增的撤销/重做
        if (GhostConfig.NoteTaking.enableAdvancedEditing && GuiScreen.isCtrlKeyDown()) {
            if (keyCode == Keyboard.KEY_A) { selectAll(); return; }
            if (keyCode == Keyboard.KEY_C) { GuiScreen.setClipboardString(getSelectedText()); return; }
            if (keyCode == Keyboard.KEY_X) {
                saveStateForUndo(false); // 剪切是“大动作”，立即保存
                GuiScreen.setClipboardString(getSelectedText());
                deleteSelection();
                return;
            }
            if (keyCode == Keyboard.KEY_V) {
                String clipboard = GuiScreen.getClipboardString();
                // 如果粘贴的内容很多，则视为“大动作”，立即保存
                saveStateForUndo(clipboard.length() < LARGE_INPUT_THRESHOLD);
                insertText(clipboard);
                return;
            }
            // 处理撤销 (Ctrl+Z)
            if (keyCode == Keyboard.KEY_Z && !isShiftKeyDown()) {
                handleUndo();
                return;
            }
            // 处理重做 (Ctrl+Y 或 Ctrl+Shift+Z)
            if (keyCode == Keyboard.KEY_Y || (keyCode == Keyboard.KEY_Z && isShiftKeyDown())) {
                handleRedo();
                return;
            }
        }
        // 处理功能键
        switch (keyCode) {
            case Keyboard.KEY_BACK:
                saveStateForUndo(true); // 退格属于连续输入
                if (hasSelection()) deleteSelection();
                else if (this.cursorPosition > 0) deleteCharBackwards();
                return;
            case Keyboard.KEY_DELETE:
                saveStateForUndo(true); // 删除键也属于连续输入
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
            // 回车键已在 handleKeyboardInput 中处理
        }
        // 处理可打印字符，并允许输入 § 和 & 符号
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) || typedChar == '§' || typedChar == '&') {
            saveStateForUndo(true); // 正常打字属于连续输入
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
                GhostConfig.setEnableMarkdownRendering(!GhostConfig.NoteTaking.enableMarkdownRendering);
                updateMarkdownButtonText();
            }
            else if (button.id == 2) {
                // 切换颜色代码配置
                GhostConfig.setEnableColorRendering(!GhostConfig.NoteTaking.enableColorRendering);
                updateColorButtonText();
            }
            else if (button.id == 3) {
                // 打开帮助界面
                commitTypingAction(); // 在切换界面前，提交当前输入
                this.mc.displayGuiScreen(new GuiNoteHelp(this));
            }
            else if (button.id == 4) {
                // 撤销按钮
                handleUndo();
            }
            else if (button.id == 5) {
                // 重做按钮
                handleRedo();
            }
            else if (button.id == 6) {
                // 切换 & 颜色代码配置
                GhostConfig.setEnableAmpersandColorCodes(!GhostConfig.NoteTaking.enableAmpersandColorCodes);
                updateAmpersandButtonText();
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
            String status = GhostConfig.NoteTaking.enableMarkdownRendering ?
                    LangUtil.translate("ghost.generic.enabled") :
                    LangUtil.translate("ghost.generic.disabled");
            this.markdownToggleButton.displayString = prefix + status;
        }
    }

    /**
     * 更新颜色代码开关按钮的显示文本。
     */
    private void updateColorButtonText() {
        if (this.colorToggleButton != null) {
            String prefix = LangUtil.translate("ghost.gui.note.color.prefix");
            String status = GhostConfig.NoteTaking.enableColorRendering ?
                    LangUtil.translate("ghost.generic.enabled") :
                    LangUtil.translate("ghost.generic.disabled");
            this.colorToggleButton.displayString = prefix + status;
        }
    }
    
    /**
     * 更新 & 颜色代码开关按钮的显示文本。
     */
    private void updateAmpersandButtonText() {
        if (this.ampersandToggleButton != null) {
            String prefix = LangUtil.translate("ghost.gui.note.ampersand.prefix");
            String status = GhostConfig.NoteTaking.enableAmpersandColorCodes ?
                    LangUtil.translate("ghost.generic.enabled") :
                    LangUtil.translate("ghost.generic.disabled");
            this.ampersandToggleButton.displayString = prefix + status;
        }
    }

    /**
     * 更新撤销和重做按钮的可用状态。
     */
    private void updateUndoRedoButtonState() {
        if (this.undoButton != null) {
            // 只有当撤销堆叠中有超过一个状态（基底状态之外）时，才能撤销
            this.undoButton.enabled = NoteManager.undoStack.size() > 1;
        }
        if (this.redoButton != null) {
            this.redoButton.enabled = !NoteManager.redoStack.isEmpty();
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
            String sub = text.substring(0, chars);
            if (this.fontRendererObj.getStringWidth(sub) > width) {
                // 如果超宽了，需要确保我们没有在颜色代码（§或&）和它的代码之间断开
                if (chars > 1) {
                    char potentialPrefix = text.charAt(chars - 2);
                    if (potentialPrefix == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && potentialPrefix == '&')) {
                        return chars - 2;
                    }
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
        
        String textToInsert;
        if (text.equals("\n")) {
            textToInsert = "\n";
        } else {
            // 修正了过滤器，以正确允许单个 § 和 & 字符的输入
            StringBuilder filtered = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c == '§' || c == '&' || ChatAllowedCharacters.isAllowedCharacter(c)) {
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
            // 如果光标前是颜色代码（§c 或 &c），则一次性删除两个字符
            int numToDelete = 1;
            if (this.cursorPosition > 1) {
                char precedingChar = this.textContent.charAt(this.cursorPosition - 2);
                char lastChar = this.textContent.charAt(this.cursorPosition - 1);
                boolean isColorPrefix = precedingChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && precedingChar == '&');
                if (isColorPrefix && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(lastChar)) != -1) {
                    numToDelete = 2;
                }
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
            // 如果目标位置的前一个字符是颜色前缀，则再向左移动一格
            if (newPos > 0) {
                 char precedingChar = this.textContent.charAt(newPos - 1);
                 if(precedingChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && precedingChar == '&')) {
                    newPos--;
                 }
            }
        } else { // 向右移动
            newPos = Math.min(this.textContent.length(), this.cursorPosition + amount);
            // 如果目标位置是颜色前缀，则再向右移动一格
            if (newPos < this.textContent.length() - 1) {
                char currentChar = this.textContent.charAt(newPos);
                if(currentChar == '§' || (GhostConfig.NoteTaking.enableAmpersandColorCodes && currentChar == '&')) {
                    newPos++;
                }
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
    
    // --- 撤销/重做 逻辑 ---
    
    /**
     * 在即将修改文本内容前，根据操作类型决定如何保存状态。
     * @param isTypingAction 如果是连续输入（如打字、退格），则为true；如果是“大动作”（如粘贴、剪切），则为false。
     */
    private void saveStateForUndo(boolean isTypingAction) {
        long now = System.currentTimeMillis();
        // 如果是“大动作”，或者距离上次编辑时间太长，或者之前不是连续输入状态
        // 那么就立即提交之前的连续输入（如果有），并为本次操作创建一个新的历史记录
        if (!isTypingAction || now - lastEditTime > EDIT_MERGE_INTERVAL || !this.isTypingAction) {
            commitTypingAction(); // 先提交旧的
            
            // 为当前的新操作保存状态
            if (NoteManager.undoStack.isEmpty() || !NoteManager.undoStack.peek().equals(this.textContent)) {
                NoteManager.undoStack.push(this.textContent);
                if (NoteManager.undoStack.size() > NoteManager.HISTORY_LIMIT) {
                    NoteManager.undoStack.removeLast();
                }
                this.lastForcedSaveTime = now; // 新的独立操作重置强制保存计时器
            }
        }
        
        this.lastEditTime = now;
        this.isTypingAction = isTypingAction;
        NoteManager.redoStack.clear(); // 任何新的编辑都会让重做历史失效
        updateUndoRedoButtonState();
    }
    
    /**
     * 将最后一次的文本状态提交到撤销堆叠。
     * 这通常在连续输入停止时调用。
     */
    private void commitTypingAction() {
        if (isTypingAction) {
            // 确保与堆叠顶部的内容不同才添加
            if (NoteManager.undoStack.isEmpty() || !NoteManager.undoStack.peek().equals(this.textContent)) {
                 NoteManager.undoStack.push(this.textContent);
                 if (NoteManager.undoStack.size() > NoteManager.HISTORY_LIMIT) {
                    NoteManager.undoStack.removeLast();
                }
            }
            isTypingAction = false;
            this.lastForcedSaveTime = System.currentTimeMillis(); // 提交后重置强制保存计时器
        }
    }

    /**
     * 处理撤销操作 (Ctrl+Z)。
     */
    private void handleUndo() {
        commitTypingAction(); // 在执行撤销前，先提交当前可能正在进行的输入
        
        if (NoteManager.undoStack.size() > 1) { // 至少要保留一个“基底”状态
            String currentState = NoteManager.undoStack.pop();
            NoteManager.redoStack.push(currentState);
            
            this.textContent = NoteManager.undoStack.peek();
            
            updateLinesAndIndices();
            setCursorPosition(this.textContent.length());
            updateUndoRedoButtonState();
            
            // 撤销/重做是“大动作”，需要重置计时器和状态
            this.isTypingAction = false;
            this.lastEditTime = 0L;
        }
    }

    /**
     * 处理重做操作 (Ctrl+Y / Ctrl+Shift+Z)。
     */
    private void handleRedo() {
        commitTypingAction(); // 在执行重做前也提交
        
        if (!NoteManager.redoStack.isEmpty()) {
            String stateToRestore = NoteManager.redoStack.pop();
            NoteManager.undoStack.push(stateToRestore);
            
            this.textContent = stateToRestore;
            
            updateLinesAndIndices();
            setCursorPosition(this.textContent.length());
            updateUndoRedoButtonState();

            // 撤销/重做是“大动作”，需要重置计时器和状态
            this.isTypingAction = false;
            this.lastEditTime = 0L;
        }
    }
}