package com.zihaomc.ghost.features.notes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;

/**
 * 管理游戏内笔记的加载与保存。 (V2: 优化读写逻辑)
 */
public class NoteManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String NOTE_FILE_NAME = "notes.txt";

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
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            System.err.println("[Ghost-Note] 加载笔记时出错: " + e.getMessage());
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
            System.err.println("[Ghost-Note] 保存笔记时出错: " + e.getMessage());
        }
    }
}