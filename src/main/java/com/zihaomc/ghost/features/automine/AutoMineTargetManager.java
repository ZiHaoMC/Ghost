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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理 AutoMine 目标（坐标列表或方块类型）的持久化。
 */
public class AutoMineTargetManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String TARGETS_COORD_FILE_NAME = "automine_targets.json";
    private static final String TARGET_BLOCKS_FILE_NAME = "automine_blocks.json"; // 修改为存储多个方块
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 内存中的坐标目标列表 */
    public static final List<BlockPos> targetBlocks = new ArrayList<>();
    /** 内存中的方块类型目标集合 */
    public static final Set<Block> targetBlockTypes = new HashSet<>();

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
    
    private static File getBlocksFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) configDir.mkdirs();
        return new File(configDir, TARGET_BLOCKS_FILE_NAME);
    }

    /**
     * 在游戏启动时加载所有持久化的目标数据。
     */
    public static void loadTargets() {
        loadCoordinates();
        loadBlockTypes();
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
    
    private static void loadBlockTypes() {
        File blocksFile = getBlocksFile();
        if (!blocksFile.exists()) return;

        try (Reader reader = new FileReader(blocksFile)) {
            // 类型令牌用于正确反序列化 Set<String>
            Type type = new TypeToken<Set<String>>(){}.getType();
            Set<String> loadedBlockIds = GSON.fromJson(reader, type);

            if (loadedBlockIds != null) {
                targetBlockTypes.clear();
                for (String blockId : loadedBlockIds) {
                    Block block = Block.blockRegistry.getObject(new ResourceLocation(blockId));
                    if (block != null) {
                        targetBlockTypes.add(block);
                    } else {
                        LogUtil.warn("log.automine.block.id.invalid", blockId);
                    }
                }
                LogUtil.info("log.automine.blocks.loaded", targetBlockTypes.size());
            }
        } catch (Exception e) {
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
     * 保存方块类型集合到文件。
     */
    public static void saveBlockTypes() {
        File blocksFile = getBlocksFile();
        if (targetBlockTypes.isEmpty()) {
            if (blocksFile.exists()) blocksFile.delete();
            return;
        }

        Set<String> blockIdsToSave = targetBlockTypes.stream()
                .map(block -> block.getRegistryName().toString())
                .collect(Collectors.toSet());

        try (Writer writer = new FileWriter(blocksFile)) {
            GSON.toJson(blockIdsToSave, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.save_failed", e.getMessage());
        }
    }
}