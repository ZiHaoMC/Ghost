package com.zihaomc.ghost.features.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.util.BlockPos;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理 AutoMine 目标列表的持久化（加载和保存）。
 */
public class AutoMineTargetManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String TARGETS_FILE_NAME = "automine_targets.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 内存中的目标列表，所有操作都围绕这个列表进行。
     */
    public static final List<BlockPos> targetBlocks = new ArrayList<>();

    /**
     * 用于Gson序列化的简单坐标对象。
     */
    private static class AutoMineTargetEntry {
        int x, y, z;
        public AutoMineTargetEntry(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }
    }

    private static File getTargetsFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, TARGETS_FILE_NAME);
    }

    /**
     * 在游戏启动时从文件加载目标列表。
     */
    public static void loadTargets() {
        File targetsFile = getTargetsFile();
        if (!targetsFile.exists()) {
            return;
        }

        try (Reader reader = new FileReader(targetsFile)) {
            Type type = new TypeToken<List<AutoMineTargetEntry>>(){}.getType();
            List<AutoMineTargetEntry> loadedEntries = GSON.fromJson(reader, type);

            if (loadedEntries != null) {
                targetBlocks.clear();
                for (AutoMineTargetEntry entry : loadedEntries) {
                    targetBlocks.add(new BlockPos(entry.x, entry.y, entry.z));
                }
                LogUtil.info("log.automine.targets.loaded", targetBlocks.size());
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            LogUtil.error("log.automine.targets.load_failed", e.getMessage());
        }
    }

    /**
     * 将当前的目标列表保存到文件。
     */
    public static void saveTargets() {
        File targetsFile = getTargetsFile();
        
        // 如果列表为空，删除文件以保持整洁
        if (targetBlocks.isEmpty()) {
            if (targetsFile.exists()) {
                targetsFile.delete();
            }
            return;
        }

        try (Writer writer = new FileWriter(targetsFile)) {
            // 将 BlockPos 列表转换为可序列化的 Entry 列表
            List<AutoMineTargetEntry> entriesToSave = targetBlocks.stream()
                    .map(AutoMineTargetEntry::new)
                    .collect(Collectors.toList());
            GSON.toJson(entriesToSave, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.save_failed", e.getMessage());
        }
    }
}