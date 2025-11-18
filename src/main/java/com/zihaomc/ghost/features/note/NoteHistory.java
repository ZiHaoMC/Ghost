package com.zihaomc.ghost.features.note;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 管理笔记的撤销/重做历史记录。
 */
public class NoteHistory {

    public static final Deque<String> undoStack = new ArrayDeque<>();
    public static final Deque<String> redoStack = new ArrayDeque<>();
    public static final int HISTORY_LIMIT = 100;

    private long lastEditTime = 0L;
    private static final long EDIT_MERGE_INTERVAL = 1000L; // 1秒
    private boolean isTypingAction = false;

    /**
     * 保存一个新的文本状态到历史记录中。
     * 这个方法应该在文本内容被修改 *之后* 调用。
     * @param newText 修改后的新文本内容。
     * @param isTyping 这次修改是否是一次打字输入（用于合并）。
     */
    public void saveState(String newText, boolean isTyping) {
        long now = System.currentTimeMillis();
        redoStack.clear();

        // 如果新文本与历史记录的最新状态相同，则不执行任何操作。
        if (!undoStack.isEmpty() && undoStack.peek().equals(newText)) {
            return;
        }

        // 检查此操作是否应与上一个操作合并（仅适用于连续打字）。
        if (isTyping && this.isTypingAction && (now - lastEditTime) < EDIT_MERGE_INTERVAL) {
            // 是一个合并的打字操作。我们用新文本替换掉栈顶的旧文本。
            if(!undoStack.isEmpty()) {
                undoStack.pop();
            }
            undoStack.push(newText);
        } else {
            // 是一个新的、独立的操作。将其作为新条目推入栈中。
            undoStack.push(newText);
            if (undoStack.size() > HISTORY_LIMIT) {
                undoStack.removeLast();
            }
        }

        // 更新状态以供下一次调用时使用。
        this.isTypingAction = isTyping;
        this.lastEditTime = now;
    }

    /**
     * 提交并结束当前的打字序列。
     * 这会确保下一次按键（无论是什么）都会创建一个新的历史记录条目。
     * @param currentText 当前文本，用于确保最终状态被正确记录。
     */
    public void commitTypingAction(String currentText) {
        // 通过将 isTypingAction 设置为 false，强制下一次 saveState 创建新条目。
        isTypingAction = false;
    }

    /**
     * 检查是否有可撤销的操作。
     * @return 如果可以撤销，则为 true。
     */
    public boolean canUndo() {
        // 如果栈中有多于一个状态（初始状态+至少一个修改），则可以撤销。
        return undoStack.size() > 1;
    }

    /**
     * 检查是否有可重做的操作。
     */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }
    
    /**
     * 执行撤销操作。
     * @return 撤销后的文本内容。
     */
    public String undo() {
        if (canUndo()) {
            String currentState = undoStack.pop();
            redoStack.push(currentState);
            isTypingAction = false; // 撤销操作会中断连续的打字输入
            return undoStack.peek();
        }
        return null;
    }

    /**
     * 执行重做操作。
     * @return 重做后的文本内容。
     */
    public String redo() {
        if (canRedo()) {
            String stateToRestore = redoStack.pop();
            undoStack.push(stateToRestore);
            isTypingAction = false; // 重做操作同样会中断连续的打字输入
            return stateToRestore;
        }
        return null;
    }
    
    /**
     * 检查是否需要因为超时而提交当前编辑。
     * （在新的逻辑中不再需要，但保留以防万一）
     * @return 如果需要提交，则返回true。
     */
    public boolean needsCommit() {
        return false;
    }
    
    /**
     * 从文件加载时，用加载的内容重置历史记录。
     * @param content 从文件加载的初始内容。
     */
    public static void resetWithContent(String content) {
        undoStack.clear();
        redoStack.clear();
        undoStack.push(content);
    }
}