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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 管理 AutoMine 目标（坐标列表、方块类型、方块权重）的持久化。
 */
public class AutoMineTargetManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String TARGETS_COORD_FILE_NAME = "automine_targets.json";
    private static final String TARGET_BLOCKS_FILE_NAME = "automine_blocks.json";
    private static final String TARGET_WEIGHTS_FILE_NAME = "automine_weights.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 内存中的坐标目标列表 */
    public static final List<BlockPos> targetBlocks = new ArrayList<>();
    /** 内存中的方块类型目标集合 (包含 metadata) */
    public static final Set<BlockData> targetBlockTypes = new HashSet<>();
    /** 内存中的方块权重映射 */
    public static final Map<Block, Integer> targetBlockWeights = new ConcurrentHashMap<>();

    /**
     * 一个内部类，用于唯一标识一个方块及其数据值。
     * metadata 为 -1 表示通配符，匹配此方块的所有数据值。
     */
    public static class BlockData {
        public final Block block;
        public final int metadata;

        public BlockData(Block block, int metadata) {
            this.block = block;
            this.metadata = metadata;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockData blockData = (BlockData) o;
            return metadata == blockData.metadata && Objects.equals(block, blockData.block);
        }

        @Override
        public int hashCode() {
            return Objects.hash(block, metadata);
        }

        @Override
        public String toString() {
            String baseId = block.getRegistryName().toString();
            return metadata == -1 ? baseId : baseId + ":" + metadata;
        }
    }

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

    private static File getWeightsFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) configDir.mkdirs();
        return new File(configDir, TARGET_WEIGHTS_FILE_NAME);
    }

    public static void loadTargets() {
        loadCoordinates();
        loadBlockTypes();
        loadBlockWeights();
    }

    private static void loadCoordinates() {
        File targetsFile = getCoordsFile();
        if (!targetsFile.exists()) return;
        try (Reader reader = new FileReader(targetsFile)) {
            Type type = new TypeToken<List<AutoMineTargetEntry>>(){}.getType();
            List<AutoMineTargetEntry> loadedEntries = GSON.fromJson(reader, type);
            if (loadedEntries != null) {
                targetBlocks.clear();
                targetBlocks.addAll(loadedEntries.stream().map(entry -> new BlockPos(entry.x, entry.y, entry.z)).collect(Collectors.toList()));
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
            Type type = new TypeToken<Set<String>>(){}.getType();
            Set<String> loadedBlockIds = GSON.fromJson(reader, type);
            if (loadedBlockIds != null) {
                targetBlockTypes.clear();
                for (String blockIdString : loadedBlockIds) {
                    try {
                        String[] parts = blockIdString.split(":");
                        Block block;
                        int meta = -1;
                        if (parts.length > 2) { // e.g., minecraft:wool:7
                            block = Block.blockRegistry.getObject(new ResourceLocation(parts[0] + ":" + parts[1]));
                            meta = Integer.parseInt(parts[2]);
                        } else {
                            block = Block.blockRegistry.getObject(new ResourceLocation(blockIdString));
                        }
                        if (block != null) {
                            targetBlockTypes.add(new BlockData(block, meta));
                        } else {
                            LogUtil.warn("log.automine.block.id.invalid", blockIdString);
                        }
                    } catch (Exception e) {
                        LogUtil.warn("log.automine.block.id.invalid", blockIdString);
                    }
                }
                LogUtil.info("log.automine.blocks.loaded", targetBlockTypes.size());
            }
        } catch (Exception e) {
            LogUtil.error("log.automine.targets.load_failed", e.getMessage());
        }
    }

    private static void loadBlockWeights() {
        File weightsFile = getWeightsFile();
        if (!weightsFile.exists()) return;
        try (Reader reader = new FileReader(weightsFile)) {
            Type type = new TypeToken<Map<String, Integer>>(){}.getType();
            Map<String, Integer> loadedWeights = GSON.fromJson(reader, type);
            if (loadedWeights != null) {
                targetBlockWeights.clear();
                for (Map.Entry<String, Integer> entry : loadedWeights.entrySet()) {
                    Block block = Block.blockRegistry.getObject(new ResourceLocation(entry.getKey()));
                    if (block != null) {
                        targetBlockWeights.put(block, entry.getValue());
                    } else {
                        LogUtil.warn("log.automine.block.id.invalid", entry.getKey());
                    }
                }
                LogUtil.info("log.automine.weights.loaded", targetBlockWeights.size());
            }
        } catch (Exception e) {
            LogUtil.error("log.automine.weights.load_failed", e.getMessage());
        }
    }

    public static void saveCoordinates() {
        File targetsFile = getCoordsFile();
        if (targetBlocks.isEmpty()) {
            if (targetsFile.exists()) targetsFile.delete();
            return;
        }
        try (Writer writer = new FileWriter(targetsFile)) {
            List<AutoMineTargetEntry> entriesToSave = targetBlocks.stream().map(AutoMineTargetEntry::new).collect(Collectors.toList());
            GSON.toJson(entriesToSave, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.save_failed", e.getMessage());
        }
    }

    public static void saveBlockTypes() {
        File blocksFile = getBlocksFile();
        if (targetBlockTypes.isEmpty()) {
            if (blocksFile.exists()) blocksFile.delete();
            return;
        }
        Set<String> blockIdsToSave = targetBlockTypes.stream().map(BlockData::toString).collect(Collectors.toSet());
        try (Writer writer = new FileWriter(blocksFile)) {
            GSON.toJson(blockIdsToSave, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.targets.save_failed", e.getMessage());
        }
    }

    public static void saveBlockWeights() {
        File weightsFile = getWeightsFile();
        if (targetBlockWeights.isEmpty()) {
            if (weightsFile.exists()) weightsFile.delete();
            return;
        }
        Map<String, Integer> weightsToSave = targetBlockWeights.entrySet().stream().collect(Collectors.toMap(entry -> entry.getKey().getRegistryName().toString(), Map.Entry::getValue));
        try (Writer writer = new FileWriter(weightsFile)) {
            GSON.toJson(weightsToSave, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.weights.save_failed", e.getMessage());
        }
    }
}