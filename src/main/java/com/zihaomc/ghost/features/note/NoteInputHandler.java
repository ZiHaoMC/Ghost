package com.zihaomc.ghost.features.note;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 负责处理笔记GUI中的所有用户输入（键盘和鼠标）。
 * - 支持 Tab 键缩进。
 */
public class NoteInputHandler {
    
    private final GuiNote guiNote;
    private final NoteEditor editor;
    private final NoteHistory history;

    public NoteInputHandler(GuiNote guiNote, NoteEditor editor, NoteHistory history) {
        this.guiNote = guiNote;
        this.editor = editor;
        this.history = history;
    }

    public void handleKeyboardInput() throws IOException {
        if (Keyboard.getEventKeyState()) {
            if (GhostConfig.ChatFeatures.disableTwitchAtKey && Keyboard.getEventCharacter() == '@') {
                // 手动处理 @ 键以绕过 Twitch 功能
                editor.insertText("@");
                history.saveState(editor.getTextContent(), true);
                return;
            }
        }
        // 让父类处理其他按键，最终会调用下面的 keyTyped 方法
        guiNote.superHandleKeyboardInput();
    }

    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            guiNote.getMc().displayGuiScreen(null);
            return;
        }

        if (GhostConfig.NoteTaking.enableAdvancedEditing && GuiScreen.isCtrlKeyDown()) {
            handleCtrlKeys(keyCode);
            return;
        }

        // 保存修改前的文本，用于之后判断文本是否真的发生了变化
        String originalText = editor.getTextContent();

        switch (keyCode) {
            case Keyboard.KEY_BACK:
                if (editor.hasSelection()) editor.deleteSelection();
                else editor.deleteCharBackwards();
                // 只有当文本确实被修改时，才保存历史记录
                if (!originalText.equals(editor.getTextContent())) {
                    history.saveState(editor.getTextContent(), true); // true表示这是一个可合并的打字操作
                }
                break;
            case Keyboard.KEY_DELETE:
                if (editor.hasSelection()) editor.deleteSelection();
                else editor.deleteCharForwards();
                if (!originalText.equals(editor.getTextContent())) {
                    history.saveState(editor.getTextContent(), true);
                }
                break;
            // 以下按键不修改文本，所以不需要保存历史
            case Keyboard.KEY_LEFT: editor.moveCursorBy(-1, GuiScreen.isShiftKeyDown()); break;
            case Keyboard.KEY_RIGHT: editor.moveCursorBy(1, GuiScreen.isShiftKeyDown()); break;
            case Keyboard.KEY_HOME: editor.setCursorPosition(0, GuiScreen.isShiftKeyDown()); break;
            case Keyboard.KEY_END: editor.setCursorPosition(editor.getTextContent().length(), GuiScreen.isShiftKeyDown()); break;
            
            case Keyboard.KEY_TAB:
                editor.insertText("    ");
                history.saveState(editor.getTextContent(), false); // false表示这是一个独立操作，不可合并
                break;
            
            case Keyboard.KEY_RETURN:
                editor.insertText("\n");
                history.saveState(editor.getTextContent(), false);
                break;

            default:
                if (typedChar == '§' || typedChar == '&' || net.minecraft.util.ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
                    editor.insertText(Character.toString(typedChar));
                    history.saveState(editor.getTextContent(), true);
                }
        }
    }

    private void handleCtrlKeys(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_A:
                editor.selectAll();
                break;
            case Keyboard.KEY_C:
                GuiScreen.setClipboardString(editor.getSelectedText());
                break;
            case Keyboard.KEY_X: // 剪切
                GuiScreen.setClipboardString(editor.getSelectedText());
                editor.deleteSelection();
                history.saveState(editor.getTextContent(), false);
                break;
            case Keyboard.KEY_V: // 粘贴
                String clipboard = GuiScreen.getClipboardString();
                editor.insertText(clipboard);
                history.saveState(editor.getTextContent(), false);
                break;
            case Keyboard.KEY_Z: // 撤销/重做
                if (!GuiScreen.isShiftKeyDown()) {
                    guiNote.handleUndo();
                } else {
                    guiNote.handleRedo();
                }
                break;
            case Keyboard.KEY_Y: // 重做
                guiNote.handleRedo();
                break;
        }
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0 && guiNote.isMouseInTextArea(mouseX, mouseY)) {
            int charIndex = guiNote.getCharIndexAt(mouseX, mouseY);
            editor.setCursorPosition(charIndex, false);
            guiNote.resetCursorBlink();
        }
    }

    public void mouseClickMove(int mouseX, int mouseY) {
         if (guiNote.isMouseInTextArea(mouseX, mouseY)) {
            int charIndex = guiNote.getCharIndexAt(mouseX, mouseY);
            editor.setCursorPosition(charIndex, true);
            guiNote.resetCursorBlink();
        }
    }
}