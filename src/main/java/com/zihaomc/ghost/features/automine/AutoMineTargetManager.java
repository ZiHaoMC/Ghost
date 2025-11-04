package com.zihaomc.ghost.features.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理 AutoMine 目标（坐标列表或方块类型）的持久化。
 */
public class AutoMineTargetManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String TARGETS_COORD_FILE_NAME = "automine_targets.json";
    private static final String TARGET_BLOCK_FILE_NAME = "automine_block.txt";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 内存中的坐标目标列表 */
    public static final List<BlockPos> targetBlocks = new ArrayList<>();
    /** 内存中的方块类型目标 */
    public static Block targetBlockType = null;

    /** 用于Gson序列化的简单坐标对象 */
    private static class AutoMineTargetEntry {
        int x, y, z;
        public AutoMineTargetEntry(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }
    }

    private static File getCoordsFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) configDir.mkdirs();
        return new File(configDir, TARGETS_COORD_FILE_NAME);
    }
    
    private static File getBlockFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) configDir.mkdirs();
        return new File(configDir, TARGET_BLOCK_FILE_NAME);
    }

    /**
     * 在游戏启动时加载所有持久化的目标数据。
     */
    public static void loadTargets() {
        loadCoordinates();
        loadBlockType();
    }

    private static void loadCoordinates() {
        File targetsFile = getCoordsFile();
        if (!targetsFile.exists()) return;

        try (Reader reader = new FileReader(targetsFile)) {
            Type type = new TypeToken<List<AutoMineTargetEntry>>(){}.getType();
            List<AutoMineTargetEntry> loadedEntries = GSON.fromJson(reader, type);

            if (loadedEntries != null) {
                targetBlocks.clear();
                targetBlocks.addAll(loadedEntries.stream()
                        .map(entry -> new BlockPos(entry.x, entry.y, entry.z))
                        .collect(Collectors.toList()));
                LogUtil.info("log.automine.targets.loaded", targetBlocks.size());
            }
        } catch (Exception e) {
            LogUtil.error("log.automine.targets.load_failed", e.getMessage());
        }
    }
    
    private static void loadBlockType() {
        File blockFile = getBlockFile();
        if (!blockFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(blockFile))) {
            String blockId = reader.readLine();
            if (blockId != null && !blockId.trim().isEmpty()) {
                targetBlockType = Block.blockRegistry.getObject(new ResourceLocation(blockId.trim()));
                if (targetBlockType != null) {
                    LogUtil.info("log.automine.block.loaded", blockId);
                }
            }
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.load_failed", e.getMessage());
        }
    }

    /**
     * 保存坐标列表到文件。
     */
    public static void saveCoordinates() {
        File targetsFile = getCoordsFile();
        if (targetBlocks.isEmpty()) {
            if (targetsFile.exists()) targetsFile.delete();
            return;
        }

        try (Writer writer = new FileWriter(targetsFile)) {
            List<AutoMineTargetEntry> entriesToSave = targetBlocks.stream()
                    .map(AutoMineTargetEntry::new)
                    .collect(Collectors.toList());
            GSON.toJson(entriesToSave, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.save_failed", e.getMessage());
        }
    }

    /**
     * 保存方块类型到文件。
     */
    public static void saveBlockType() {
        File blockFile = getBlockFile();
        if (targetBlockType == null) {
            if (blockFile.exists()) blockFile.delete();
            return;
        }

        try (Writer writer = new FileWriter(blockFile)) {
            writer.write(targetBlockType.getRegistryName().toString());
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.save_failed", e.getMessage());
        }
    }
}