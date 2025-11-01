package com.zihaomc.ghost.features.note;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import java.io.IOException;

import com.zihaomc.ghost.features.note.GuiNoteHelp;


/**
 * 游戏内笔记的GUI界面 (重构后)。
 * 这个类现在是协调者，将状态管理、渲染和输入处理委托给专门的辅助类。
 */
public class GuiNote extends GuiScreen {

    // --- 辅助类实例 ---
    private NoteEditor editor;
    private NoteRenderer renderer;
    private NoteInputHandler inputHandler;
    private NoteHistory history;
    
    // --- GUI 状态 ---
    private int textAreaX, textAreaY, textAreaWidth, textAreaHeight;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int cursorBlink;

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

        this.textAreaX = this.width / 2 - 150;
        this.textAreaY = 40;
        this.textAreaWidth = 300;
        this.textAreaHeight = this.height - 90;

        this.renderer = new NoteRenderer(this.fontRendererObj, this.textAreaX, this.textAreaWidth - 8);
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

        int btnWidth = 120, btnHeight = 20, leftX = textAreaX - btnWidth - 5, rightX = textAreaX + textAreaWidth + 5;

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

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawCenteredString(this.fontRendererObj, LangUtil.translate("ghost.gui.note.title"), this.width / 2, 20, 0xFFFFFF);
        drawRect(textAreaX - 1, textAreaY - 1, textAreaX + textAreaWidth + 1, textAreaY + textAreaHeight + 1, 0xFFC0C0C0);
        drawRect(textAreaX, textAreaY, textAreaX + textAreaWidth, textAreaY + textAreaHeight, 0xFF000000);
        
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int scaleFactor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        GL11.glScissor(textAreaX * scaleFactor, mc.displayHeight - (textAreaY + textAreaHeight) * scaleFactor, textAreaWidth * scaleFactor, textAreaHeight * scaleFactor);
        
        int yPos = textAreaY + 4 - scrollOffset;
        for (int i = 0; i < renderer.getRenderedLines().size(); i++) {
            if (yPos + fontRendererObj.FONT_HEIGHT > textAreaY && yPos < textAreaY + textAreaHeight) {
                String line = renderer.getRenderedLines().get(i);
                renderer.drawStringAndCachePositions(line, textAreaX + 4, yPos, 0xFFFFFF);
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
        
        drawCenteredString(fontRendererObj, LangUtil.translate("ghost.gui.note.scroll_hint"), this.width / 2, this.height - 40, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    
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

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        inputHandler.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        inputHandler.mouseClickMove(mouseX, mouseY);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            this.maxScroll = Math.max(0, renderer.getRenderedLines().size() * fontRendererObj.FONT_HEIGHT - textAreaHeight + 8);
            int scrollAmount = fontRendererObj.FONT_HEIGHT * 3 * (dWheel < 0 ? 1 : -1);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + scrollAmount));
        }
    }

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
    public void setTextContentAndInitialize(String newText) {
        if (newText != null) this.editor.setTextContent(newText);
    }
    public boolean isMouseInTextArea(int mouseX, int mouseY) {
        return mouseX >= textAreaX && mouseX <= textAreaX + textAreaWidth && mouseY >= textAreaY && mouseY <= textAreaY + textAreaHeight;
    }
    public int getCharIndexAt(int mouseX, int mouseY) {
        int relativeY = mouseY - this.textAreaY - 4 + this.scrollOffset;
        return renderer.getCharIndexAt(mouseX, mouseY, relativeY);
    }
    public void resetCursorBlink() { this.cursorBlink = 0; }
    
    // <<< 新增的公共 getter 方法
    public Minecraft getMc() {
        return this.mc;
    }
    
    private void ensureCursorVisible() {
        int lineIndex = renderer.findLineForPosition(editor.getCursorPosition());
        int cursorY = lineIndex * fontRendererObj.FONT_HEIGHT;
        if (cursorY < scrollOffset) {
            scrollOffset = cursorY;
        } else if (cursorY + fontRendererObj.FONT_HEIGHT > scrollOffset + textAreaHeight - 8) {
            scrollOffset = cursorY + fontRendererObj.FONT_HEIGHT - textAreaHeight + 8;
        }
        this.maxScroll = Math.max(0, renderer.getRenderedLines().size() * fontRendererObj.FONT_HEIGHT - textAreaHeight + 8);
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