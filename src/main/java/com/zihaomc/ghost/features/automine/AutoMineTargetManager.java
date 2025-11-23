package com.zihaomc.ghost.features.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 管理 AutoMine 目标（坐标、方块类型、权重、自定义组）的持久化。
 */
public class AutoMineTargetManager {

    private static final String CONFIG_DIR = "config/Ghost/";
    private static final String TARGETS_COORD_FILE_NAME = "automine_targets.json";
    private static final String TARGET_BLOCKS_FILE_NAME = "automine_blocks.json";
    private static final String TARGET_WEIGHTS_FILE_NAME = "automine_weights.json";
    private static final String TARGET_GROUPS_FILE_NAME = "automine_groups.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final Map<String, List<BlockPos>> worldTargetBlocks = new ConcurrentHashMap<>();
    public static final Set<BlockData> targetBlockTypes = new HashSet<>();
    public static final Map<Block, Integer> targetBlockWeights = new ConcurrentHashMap<>();
    public static final Map<String, List<String>> customBlockGroups = new ConcurrentHashMap<>();

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
            return metadata == blockData.metadata && Objects.equals(block.getRegistryName(), blockData.block.getRegistryName());
        }

        @Override
        public int hashCode() {
            return Objects.hash(block.getRegistryName(), metadata);
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
    
    private static File getGroupsFile() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists()) configDir.mkdirs();
        return new File(configDir, TARGET_GROUPS_FILE_NAME);
    }

    public static void loadTargets() {
        loadCoordinates();
        loadBlockTypes();
        loadBlockWeights();
        loadBlockGroups();
    }

    private static String getCurrentWorldId() {
        if (Minecraft.getMinecraft() != null && Minecraft.getMinecraft().theWorld != null) {
            return GhostBlockData.getWorldIdentifier(Minecraft.getMinecraft().theWorld);
        }
        return null;
    }

    public static List<BlockPos> getCurrentTargetBlocks() {
        String worldId = getCurrentWorldId();
        if (worldId == null) {
            return Collections.emptyList();
        }
        return worldTargetBlocks.computeIfAbsent(worldId, k -> new ArrayList<>());
    }
    
    private static void loadCoordinates() {
        File targetsFile = getCoordsFile();
        if (!targetsFile.exists()) return;
        try (Reader reader = new FileReader(targetsFile)) {
            Type type = new TypeToken<Map<String, List<AutoMineTargetEntry>>>(){}.getType();
            Map<String, List<AutoMineTargetEntry>> loadedMap = GSON.fromJson(reader, type);
            if (loadedMap != null) {
                worldTargetBlocks.clear();
                for (Map.Entry<String, List<AutoMineTargetEntry>> worldEntry : loadedMap.entrySet()) {
                    List<BlockPos> blockPosList = worldEntry.getValue().stream()
                        .map(entry -> new BlockPos(entry.x, entry.y, entry.z))
                        .collect(Collectors.toList());
                    worldTargetBlocks.put(worldEntry.getKey(), blockPosList);
                }
                long totalTargets = worldTargetBlocks.values().stream().mapToLong(List::size).sum();
                LogUtil.info("log.automine.targets.loaded", totalTargets);
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
                        String blockName = blockIdString;
                        int meta = -1;
                        int lastColon = blockIdString.lastIndexOf(':');
                        if (lastColon > 0 && lastColon > blockIdString.indexOf(':')) {
                             try {
                                meta = Integer.parseInt(blockIdString.substring(lastColon + 1));
                                blockName = blockIdString.substring(0, lastColon);
                            } catch (NumberFormatException e) {
                            }
                        }
                        Block block = Block.blockRegistry.getObject(new ResourceLocation(blockName));
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

    private static void loadBlockGroups() {
        File groupsFile = getGroupsFile();
        if (!groupsFile.exists()) {
            PredefinedGroupManager.initializePredefinedGroups();
            return;
        }
        try (Reader reader = new FileReader(groupsFile)) {
            Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
            Map<String, List<String>> loadedGroups = GSON.fromJson(reader, type);
            if (loadedGroups != null) {
                customBlockGroups.clear();
                customBlockGroups.putAll(loadedGroups);
                LogUtil.info("log.automine.groups.loaded", customBlockGroups.size());
            }
        } catch (Exception e) {
            LogUtil.error("log.automine.groups.load_failed", e.getMessage());
        }
    }

    public static void saveCoordinates() {
        File targetsFile = getCoordsFile();
        worldTargetBlocks.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());

        if (worldTargetBlocks.isEmpty()) {
            if (targetsFile.exists()) targetsFile.delete();
            return;
        }
        try (Writer writer = new FileWriter(targetsFile)) {
            Map<String, List<AutoMineTargetEntry>> mapToSave = new HashMap<>();
            for (Map.Entry<String, List<BlockPos>> worldEntry : worldTargetBlocks.entrySet()) {
                List<AutoMineTargetEntry> targetEntries = worldEntry.getValue().stream()
                    .map(AutoMineTargetEntry::new)
                    .collect(Collectors.toList());
                mapToSave.put(worldEntry.getKey(), targetEntries);
            }
            GSON.toJson(mapToSave, writer);
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

    public static void saveBlockGroups() {
        File groupsFile = getGroupsFile();
        if (customBlockGroups.isEmpty()) {
            if (groupsFile.exists()) {
                groupsFile.delete();
            }
            return;
        }
        try (Writer writer = new FileWriter(groupsFile)) {
            GSON.toJson(customBlockGroups, writer);
        } catch (IOException e) {
            LogUtil.error("log.automine.groups.save_failed", e.getMessage());
        }
    }
}
