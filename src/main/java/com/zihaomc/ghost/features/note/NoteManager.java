package com.zihaomc.ghost.features.note;

import com.zihaomc.ghost.utils.LogUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;

/**
 * 管理游戏内笔记的文件加载与保存。
 */
public class NoteManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String NOTE_FILE_NAME = "notes.txt";

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
            String content = reader.lines().collect(Collectors.joining("\n"));
            // 当从文件加载新内容时，重置历史记录
            NoteHistory.resetWithContent(content);
            return content;
        } catch (IOException e) {
            LogUtil.error("log.error.note.loadFailed", e.getMessage());
            NoteHistory.resetWithContent(""); // 出错时也重置
            return "";
        }
    }

    /**
     * 将指定的文本保存到笔记文件。
     * @param text 要保存的文本内容。
     */
    public static void saveNote(String text) {
        if (text == null) return;

        File noteFile = getNoteFile();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(noteFile), StandardCharsets.UTF_8)) {
            writer.write(text);
        } catch (IOException e) {
            LogUtil.error("log.error.note.saveFailed", e.getMessage());
        }
    }
}