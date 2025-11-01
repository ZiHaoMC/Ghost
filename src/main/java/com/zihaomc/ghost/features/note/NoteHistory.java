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
    private long lastForcedSaveTime = 0L;
    private static final long EDIT_MERGE_INTERVAL = 1000L; // 1秒
    private static final long FORCED_SAVE_INTERVAL = 5000L; // 5秒
    private boolean isTypingAction = false;

    /**
     * 在文本即将被修改时调用，以智能地保存状态。
     * @param currentText 当前的文本内容。
     * @param isTyping 是否是连续输入（打字、退格）。
     */
    public void saveState(String currentText, boolean isTyping) {
        long now = System.currentTimeMillis();
        if (!isTyping || now - lastEditTime > EDIT_MERGE_INTERVAL || !this.isTypingAction) {
            commitTypingAction(currentText); // 提交之前的

            if (undoStack.isEmpty() || !undoStack.peek().equals(currentText)) {
                undoStack.push(currentText);
                if (undoStack.size() > HISTORY_LIMIT) {
                    undoStack.removeLast();
                }
                this.lastForcedSaveTime = now;
            }
        }

        this.lastEditTime = now;
        this.isTypingAction = isTyping;
        redoStack.clear();
    }

    /**
     * 提交当前的输入状态到历史记录中。
     * @param currentText 当前文本，用于在提交时保存。
     */
    public void commitTypingAction(String currentText) {
        if (isTypingAction) {
            if (undoStack.isEmpty() || !undoStack.peek().equals(currentText)) {
                undoStack.push(currentText);
                if (undoStack.size() > HISTORY_LIMIT) {
                    undoStack.removeLast();
                }
            }
            isTypingAction = false;
            this.lastForcedSaveTime = System.currentTimeMillis();
        }
    }

    /**
     * 检查是否有可撤销的操作。
     */
    public boolean canUndo() {
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
            resetTimers();
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
            resetTimers();
            return stateToRestore;
        }
        return null;
    }
    
    /**
     * 重置编辑计时器，用于在撤销/重做后开始新的编辑会话。
     */
    private void resetTimers() {
        this.isTypingAction = false;
        this.lastEditTime = 0L;
    }

    /**
     * 检查是否需要因为超时而提交当前编辑。
     * @return 如果需要提交，则返回true。
     */
    public boolean needsCommit() {
        long now = System.currentTimeMillis();
        return isTypingAction && (now - lastEditTime > EDIT_MERGE_INTERVAL || now - lastForcedSaveTime > FORCED_SAVE_INTERVAL);
    }
    
    /**
     * 从文件加载时，用加载的内容重置历史记录。
     */
    public static void resetWithContent(String content) {
        undoStack.clear();
        redoStack.clear();
        undoStack.push(content);
    }
}