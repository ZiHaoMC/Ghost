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
                history.saveState(editor.getTextContent(), true);
                editor.insertText("@");
                return;
            }
            if (Keyboard.getEventKey() == Keyboard.KEY_RETURN) {
                history.saveState(editor.getTextContent(), false);
                editor.insertText("\n");
                return;
            }
        }
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

        switch (keyCode) {
            case Keyboard.KEY_BACK:
                history.saveState(editor.getTextContent(), true);
                if (editor.hasSelection()) editor.deleteSelection();
                else editor.deleteCharBackwards();
                break;
            case Keyboard.KEY_DELETE:
                history.saveState(editor.getTextContent(), true);
                if (editor.hasSelection()) editor.deleteSelection();
                else editor.deleteCharForwards();
                break;
            case Keyboard.KEY_LEFT: editor.moveCursorBy(-1, GuiScreen.isShiftKeyDown()); break;
            case Keyboard.KEY_RIGHT: editor.moveCursorBy(1, GuiScreen.isShiftKeyDown()); break;
            case Keyboard.KEY_HOME: editor.setCursorPosition(0, GuiScreen.isShiftKeyDown()); break;
            case Keyboard.KEY_END: editor.setCursorPosition(editor.getTextContent().length(), GuiScreen.isShiftKeyDown()); break;
            
            // --- 新增：处理Tab键 ---
            case Keyboard.KEY_TAB:
                history.saveState(editor.getTextContent(), false); // Tab 视为一个独立操作
                editor.insertText("    "); // 插入四个空格
                break;

            default:
                if (typedChar == '§' || typedChar == '&' || net.minecraft.util.ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
                    history.saveState(editor.getTextContent(), true);
                    editor.insertText(Character.toString(typedChar));
                }
        }
    }

    private void handleCtrlKeys(int keyCode) {
        if (keyCode == Keyboard.KEY_A) editor.selectAll();
        else if (keyCode == Keyboard.KEY_C) GuiScreen.setClipboardString(editor.getSelectedText());
        else if (keyCode == Keyboard.KEY_X) {
            history.saveState(editor.getTextContent(), false);
            GuiScreen.setClipboardString(editor.getSelectedText());
            editor.deleteSelection();
        } else if (keyCode == Keyboard.KEY_V) {
            String clipboard = GuiScreen.getClipboardString();
            history.saveState(editor.getTextContent(), clipboard.length() < 5);
            editor.insertText(clipboard);
        } else if (keyCode == Keyboard.KEY_Z && !GuiScreen.isShiftKeyDown()) {
            guiNote.handleUndo();
        } else if (keyCode == Keyboard.KEY_Y || (keyCode == Keyboard.KEY_Z && GuiScreen.isShiftKeyDown())) {
            guiNote.handleRedo();
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