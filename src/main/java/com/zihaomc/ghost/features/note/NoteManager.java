package com.zihaomc.ghost.features.notes;

import com.zihaomc.ghost.utils.LogUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

/**
 * 管理游戏内笔记的加载与保存。 (V2: 优化读写逻辑)
 * (V3: 新增用于持久化撤销/重做的静态历史堆叠)
 */
public class NoteManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String NOTE_FILE_NAME = "notes.txt";

    // --- 持久化的撤销/重做历史记录 ---
    // 声明为静态，使其生命周期与游戏进程绑定，而不是GUI实例
    public static final Deque<String> undoStack = new ArrayDeque<>();
    public static final Deque<String> redoStack = new ArrayDeque<>();
    public static final int HISTORY_LIMIT = 100; // 限制历史记录步数

    /**
     * 获取笔记文件的 File 对象。
     * @return 笔记文件的 File 对象。
     */
    private static File getNoteFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, NOTE_FILE_NAME);
    }

    /**
     * 从文件加载笔记内容。
     * @return 笔记内容字符串。如果文件不存在或加载失败，则返回空字符串。
     */
    public static String loadNote() {
        File noteFile = getNoteFile();
        if (!noteFile.exists()) {
            return "";
        }

        try (BufferedReader reader = Files.newBufferedReader(noteFile.toPath(), StandardCharsets.UTF_8)) {
            // 使用Stream API一次性读取所有行并用'\n'连接，这是最稳定可靠的方式
            String content = reader.lines().collect(Collectors.joining("\n"));
            
            // 当从文件加载新内容时，意味着开始了一个全新的编辑会话，应该清空历史记录
            undoStack.clear();
            redoStack.clear();
            // 将加载的内容作为历史记录的“基底”
            undoStack.push(content);

            return content;
        } catch (IOException e) {
            LogUtil.error("log.error.note.loadFailed", e.getMessage());
            return ""; // 出错时返回空字符串
        }
    }

    /**
     * 将指定的文本保存到笔记文件。
     * @param text 要保存的文本内容。
     */
    public static void saveNote(String text) {
        if (text == null) {
            return;
        }

        File noteFile = getNoteFile();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(noteFile), StandardCharsets.UTF_8)) {
            writer.write(text);
        } catch (IOException e) {
            LogUtil.error("log.error.note.saveFailed", e.getMessage());
        }
    }
}