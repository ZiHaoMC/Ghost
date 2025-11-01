package com.zihaomc.ghost.features.note;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/**
 * 游戏内笔记的GUI界面 (最终优化版)。
 * - 作为协调者，将状态管理、渲染和输入处理委托给专门的辅助类。
 * - 实现了类似Minecraft原生体验的可视化滚动条。
 * - 使用布局常量提高代码的可维护性。
 */
public class GuiNote extends GuiScreen {

    // --- 辅助类实例 ---
    private NoteEditor editor;
    private NoteRenderer renderer;
    private NoteInputHandler inputHandler;
    private NoteHistory history;

    // --- 布局常量 ---
    private static final int TEXT_AREA_WIDTH = 300;
    private static final int TEXT_AREA_HEIGHT_MARGIN_TOP = 40;
    // <<< 布局修复：底部边距应为50，以保证总高度正确 >>>
    private static final int TEXT_AREA_HEIGHT_MARGIN_BOTTOM = 50; 
    private static final int TEXT_PADDING = 4;
    private static final int SCROLLBAR_WIDTH = 6;

    // --- GUI 状态 ---
    private int textAreaX, textAreaY, textAreaWidth, textAreaHeight;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int cursorBlink;

    // <<< 滚动条交互逻辑重构：新增变量 >>>
    private boolean isDraggingScrollbar = false;
    private float initialMouseY = -1; // 记录拖动开始时鼠标的Y坐标
    private int scrollOffsetAtDragStart = -1; // 记录拖动开始时滚动条的偏移量

    // --- 按钮 ---
    private GuiButton markdownToggleButton, colorToggleButton, ampersandToggleButton;
    private GuiButton helpButton, undoButton, redoButton;

    // --- 构造函数 ---
    public GuiNote() {
        this.editor = new NoteEditor();
        this.history = new NoteHistory();
    }

    // --- GUI 生命周期 ---
    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);

        // 使用常量进行布局，确保真正居中
        this.textAreaWidth = TEXT_AREA_WIDTH;
        this.textAreaX = this.width / 2 - this.textAreaWidth / 2;
        this.textAreaY = TEXT_AREA_HEIGHT_MARGIN_TOP;
        this.textAreaHeight = this.height - (TEXT_AREA_HEIGHT_MARGIN_TOP + TEXT_AREA_HEIGHT_MARGIN_BOTTOM);

        // 初始化渲染器和输入处理器
        this.renderer = new NoteRenderer(this.fontRendererObj, this.textAreaX, this.textAreaWidth - (TEXT_PADDING * 2) - SCROLLBAR_WIDTH);
        this.inputHandler = new NoteInputHandler(this, this.editor, this.history);

        if (this.editor.getTextContent().isEmpty()) {
            this.editor.setTextContent(NoteHistory.undoStack.isEmpty() ? NoteManager.loadNote() : NoteHistory.undoStack.peek());
        }
        
        if (!GhostConfig.GuiTweaks.fixGuiStateLossOnResize) {
            this.editor.setTextContent(NoteManager.loadNote());
        }

        this.renderer.updateLines(editor.getTextContent());
        this.editor.setCursorPosition(editor.getTextContent().length(), false);
        
        initButtons();
    }

    private void initButtons() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 25, LangUtil.translate("ghost.gui.note.save_and_close")));

        int btnWidth = 120, btnHeight = 20;
        int leftX = textAreaX - btnWidth - 5;
        int rightX = textAreaX + textAreaWidth + 5;

        markdownToggleButton = new GuiButton(1, leftX, textAreaY, btnWidth, btnHeight, "");
        colorToggleButton = new GuiButton(2, leftX, textAreaY + btnHeight + 5, btnWidth, btnHeight, "");
        ampersandToggleButton = new GuiButton(6, leftX, textAreaY + (btnHeight + 5) * 2, btnWidth, btnHeight, "");
        helpButton = new GuiButton(3, rightX, textAreaY, 20, 20, "?");
        undoButton = new GuiButton(4, rightX, textAreaY + btnHeight + 5, 20, 20, "<");
        redoButton = new GuiButton(5, rightX + 20 + 2, textAreaY + btnHeight + 5, 20, 20, ">");
        
        this.buttonList.add(markdownToggleButton);
        this.buttonList.add(colorToggleButton);
        this.buttonList.add(ampersandToggleButton);
        this.buttonList.add(helpButton);
        this.buttonList.add(undoButton);
        this.buttonList.add(redoButton);

        updateButtonStates();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        history.commitTypingAction(editor.getTextContent());
        NoteManager.saveNote(editor.getTextContent());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.cursorBlink++;
        if (history.needsCommit()) {
            history.commitTypingAction(editor.getTextContent());
            updateUndoRedoButtonState();
        }
    }

    // --- 渲染 ---
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.title"), this.width / 2, 20, 0xFFFFFF);
        drawRect(textAreaX - 1, textAreaY - 1, textAreaX + textAreaWidth + 1, textAreaY + textAreaHeight + 1, 0xFFC0C0C0);
        drawRect(textAreaX, textAreaY, textAreaX + textAreaWidth, textAreaY + textAreaHeight, 0xFF000000);
        
        this.maxScroll = Math.max(0, renderer.getRenderedLines().size() * fontRendererObj.FONT_HEIGHT - textAreaHeight + (TEXT_PADDING * 2));
        
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int scaleFactor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        GL11.glScissor(textAreaX * scaleFactor, mc.displayHeight - (textAreaY + textAreaHeight) * scaleFactor, textAreaWidth * scaleFactor, textAreaHeight * scaleFactor);
        
        int yPos = textAreaY + TEXT_PADDING - scrollOffset;
        for (int i = 0; i < renderer.getRenderedLines().size(); i++) {
            if (yPos + fontRendererObj.FONT_HEIGHT > textAreaY && yPos < textAreaY + textAreaHeight) {
                String line = renderer.getRenderedLines().get(i);
                renderer.drawStringAndCachePositions(line, textAreaX + TEXT_PADDING, yPos, 0xFFFFFF);
                if (editor.hasSelection()) {
                    renderer.drawSelection(yPos, editor.getSelectionStart(), editor.getSelectionEnd(), i);
                }
                if (i == renderer.findLineForPosition(editor.getCursorPosition()) && (cursorBlink / 6) % 2 == 0) {
                    renderer.drawCursor(yPos, editor.getCursorPosition());
                }
            }
            yPos += fontRendererObj.FONT_HEIGHT;
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        
        drawScrollbar();
        
        drawCenteredString(fontRendererObj, LangUtil.translate("ghost.gui.note.scroll_hint"), this.width / 2, this.height - 35, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    
    private void drawScrollbar() {
        if (maxScroll > 0) {
            int scrollbarX = this.textAreaX + this.textAreaWidth - SCROLLBAR_WIDTH;
            int scrollbarY = this.textAreaY;
            int scrollbarHeight = this.textAreaHeight;

            drawRect(scrollbarX, scrollbarY, scrollbarX + (SCROLLBAR_WIDTH - 1), scrollbarY + scrollbarHeight, 0x80000000);

            int handleHeight = Math.max(10, (int) ((float) scrollbarHeight * scrollbarHeight / (float) (maxScroll + scrollbarHeight)));
            int handleY = scrollbarY + (int) ((float) scrollOffset / (float) maxScroll * (scrollbarHeight - handleHeight));
            
            drawRect(scrollbarX, handleY, scrollbarX + (SCROLLBAR_WIDTH - 1), handleY + handleHeight, 0xFF808080);
            drawRect(scrollbarX, handleY, scrollbarX + (SCROLLBAR_WIDTH - 2), handleY + handleHeight - 1, 0xFFC0C0C0);
        }
    }

    // --- 输入处理 ---
    @Override
    public void handleKeyboardInput() throws IOException {
        inputHandler.handleKeyboardInput();
        renderer.updateLines(editor.getTextContent());
        ensureCursorVisible();
    }
    
    public void superHandleKeyboardInput() throws IOException {
        super.handleKeyboardInput();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        inputHandler.keyTyped(typedChar, keyCode);
        renderer.updateLines(editor.getTextContent());
        ensureCursorVisible();
        updateUndoRedoButtonState();
    }

    // <<< 滚动条交互逻辑重构 >>>
    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && maxScroll > 0) {
            int scrollbarX = this.textAreaX + this.textAreaWidth - SCROLLBAR_WIDTH;
            if (mouseX >= scrollbarX && mouseX < scrollbarX + SCROLLBAR_WIDTH && mouseY >= this.textAreaY && mouseY < this.textAreaY + this.textAreaHeight) {
                int scrollbarHeight = this.textAreaHeight;
                int handleHeight = Math.max(10, (int) ((float) scrollbarHeight * scrollbarHeight / (float) (maxScroll + scrollbarHeight)));
                int handleY = this.textAreaY + (int) ((float) scrollOffset / (float) maxScroll * (scrollbarHeight - handleHeight));

                if (mouseY >= handleY && mouseY < handleY + handleHeight) {
                    // 点击在滑块上，开始拖动
                    this.isDraggingScrollbar = true;
                    this.initialMouseY = mouseY;
                    this.scrollOffsetAtDragStart = this.scrollOffset;
                } else {
                    // 点击在轨道空白处，实现翻页
                    this.scrollOffset += (mouseY < handleY ? -1 : 1) * this.textAreaHeight;
                    this.scrollOffset = MathHelper.clamp_int(scrollOffset, 0, maxScroll);
                }
                return; // 消耗点击事件
            }
        }
        
        this.isDraggingScrollbar = false;
        super.mouseClicked(mouseX, mouseY, mouseButton);
        inputHandler.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.isDraggingScrollbar) {
            float deltaY = mouseY - this.initialMouseY;
            int scrollbarHeight = this.textAreaHeight;
            int handleHeight = Math.max(10, (int) ((float) scrollbarHeight * scrollbarHeight / (float) (maxScroll + scrollbarHeight)));
            float tractableHeight = scrollbarHeight - handleHeight;

            if (tractableHeight > 0) {
                float scrollRatio = (float) this.maxScroll / tractableHeight;
                this.scrollOffset = (int) (this.scrollOffsetAtDragStart + (deltaY * scrollRatio));
                this.scrollOffset = MathHelper.clamp_int(scrollOffset, 0, maxScroll);
            }
            return;
        }
        
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        inputHandler.mouseClickMove(mouseX, mouseY);
    }
    
    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0) {
            this.isDraggingScrollbar = false;
            this.initialMouseY = -1;
            this.scrollOffsetAtDragStart = -1;
        }
    }
    
    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            int scrollAmount = fontRendererObj.FONT_HEIGHT * 3 * (dWheel < 0 ? 1 : -1);
            this.maxScroll = Math.max(0, renderer.getRenderedLines().size() * fontRendererObj.FONT_HEIGHT - textAreaHeight + (TEXT_PADDING * 2));
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + scrollAmount));
        }
    }

    // ... (actionPerformed, handleUndo, handleRedo 等方法保持不变) ...
    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) return;
        switch (button.id) {
            case 0: mc.displayGuiScreen(null); break;
            case 1: GhostConfig.setEnableMarkdownRendering(!GhostConfig.NoteTaking.enableMarkdownRendering); break;
            case 2: GhostConfig.setEnableColorRendering(!GhostConfig.NoteTaking.enableColorRendering); break;
            case 3: history.commitTypingAction(editor.getTextContent()); mc.displayGuiScreen(new GuiNoteHelp(this)); break;
            case 4: handleUndo(); break;
            case 5: handleRedo(); break;
            case 6: GhostConfig.setEnableAmpersandColorCodes(!GhostConfig.NoteTaking.enableAmpersandColorCodes); break;
        }
        updateButtonStates();
        super.actionPerformed(button);
    }
    
    public void handleUndo() {
        history.commitTypingAction(editor.getTextContent());
        String undoneText = history.undo();
        if (undoneText != null) {
            editor.setTextContent(undoneText);
            editor.setCursorPosition(undoneText.length(), false);
            renderer.updateLines(undoneText);
            updateUndoRedoButtonState();
        }
    }
    
    public void handleRedo() {
        history.commitTypingAction(editor.getTextContent());
        String redoneText = history.redo();
        if (redoneText != null) {
            editor.setTextContent(redoneText);
            editor.setCursorPosition(redoneText.length(), false);
            renderer.updateLines(redoneText);
            updateUndoRedoButtonState();
        }
    }

    // --- 辅助方法 ---
    public String getTextContent() { return this.editor.getTextContent(); }
    public void setTextContentAndInitialize(String newText) { if (newText != null) this.editor.setTextContent(newText); }
    public boolean isMouseInTextArea(int mouseX, int mouseY) {
        return mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth - (SCROLLBAR_WIDTH + 1) && mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight;
    }
    public int getCharIndexAt(int mouseX, int mouseY) {
        int relativeY = mouseY - this.textAreaY - TEXT_PADDING + this.scrollOffset;
        return renderer.getCharIndexAt(mouseX, mouseY, relativeY);
    }
    public void resetCursorBlink() { this.cursorBlink = 0; }
    public Minecraft getMc() { return this.mc; }
    
    private void ensureCursorVisible() {
        int lineIndex = renderer.findLineForPosition(editor.getCursorPosition());
        int cursorY = lineIndex * fontRendererObj.FONT_HEIGHT;
        if (cursorY < scrollOffset) {
            scrollOffset = cursorY;
        } else if (cursorY + fontRendererObj.FONT_HEIGHT > scrollOffset + textAreaHeight - (TEXT_PADDING * 2)) {
            scrollOffset = cursorY + fontRendererObj.FONT_HEIGHT - textAreaHeight + (TEXT_PADDING * 2);
        }
        this.maxScroll = Math.max(0, renderer.getRenderedLines().size() * fontRendererObj.FONT_HEIGHT - textAreaHeight + (TEXT_PADDING * 2));
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
    }
    
    private void updateButtonStates() {
        markdownToggleButton.displayString = LangUtil.translate("ghost.gui.note.markdown.prefix") + (GhostConfig.NoteTaking.enableMarkdownRendering ? LangUtil.translate("ghost.generic.enabled") : LangUtil.translate("ghost.generic.disabled"));
        colorToggleButton.displayString = LangUtil.translate("ghost.gui.note.color.prefix") + (GhostConfig.NoteTaking.enableColorRendering ? LangUtil.translate("ghost.generic.enabled") : LangUtil.translate("ghost.generic.disabled"));
        ampersandToggleButton.displayString = LangUtil.translate("ghost.gui.note.ampersand.prefix") + (GhostConfig.NoteTaking.enableAmpersandColorCodes ? LangUtil.translate("ghost.generic.enabled") : LangUtil.translate("ghost.generic.disabled"));
        updateUndoRedoButtonState();
    }
    
    private void updateUndoRedoButtonState() {
        undoButton.enabled = history.canUndo();
        redoButton.enabled = history.canRedo();
    }
}