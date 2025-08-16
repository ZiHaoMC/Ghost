package com.zihaomc.ghost.config;

import com.zihaomc.ghost.LangUtil;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class GhostConfig {

    private static Configuration config = null;

    // --- 配置类别 ---
    public static final String CATEGORY_FILL = "fill_command";
    public static final String CATEGORY_SAVE = "save_options";
    public static final String CATEGORY_CHAT = "chat_features";
    public static final String CATEGORY_AUTO_PLACE = "auto_place_feature";
    public static final String CATEGORY_AUTO_SNEAK = "auto_sneak_feature";
    public static final String CATEGORY_PLAYER_ESP = "player_esp_feature";
    public static final String CATEGORY_BEDROCK_MINER = "bedrock_miner_feature";
    public static final String CATEGORY_GAMEPLAY_TWEAKS = "gameplay_tweaks";

    // --- 旧配置项 (保持原样) ---
    public static boolean enableAutoSneakAtEdge = false;
    public static double autoSneakForwardOffset = 0.10;
    public static double autoSneakVerticalCheckDepth = 2.0;
    public static boolean alwaysBatchFill = false;
    public static int forcedBatchSize = 100;
    public static boolean enableAutoSave = false;
    public static String defaultSaveFileName = "";
    public static boolean enableChatSuggestions = true;
    public static boolean enableCommandHistoryScroll = true; // 新增：控制命令历史滚动
    public static boolean enableAutoPlaceOnJoin = false;
    public static boolean enablePlayerESP = false;
    public static boolean fastPistonBreaking = false;

    // --- 破基岩配置项 (新旧整合) ---
    public static boolean enableBedrockMiner = false;
    public static int pingSpikeThreshold = 2;
    public static boolean headlessPistonMode = true;
    public static boolean blinkDuringTasksTick = true;
    // 使用 Set<Block> 提高性能
    private static Set<Block> blockWhitelist = new HashSet<>();
    private static Set<Block> dependBlockWhitelist = new HashSet<>();


    public static void init(File configFile) {
        if (config == null) {
            config = new Configuration(configFile);
            loadConfig();
        }
    }

    public static void loadConfig() {
        if (config == null) {
            System.err.println("[Ghost-Config] 配置尚未初始化！");
            return;
        }

        config.load();

        // --- 加载/生成旧配置值 (完整保留) ---
        String alwaysBatchComment = LangUtil.translate("ghostblock.config.alwaysBatchFill.tooltip");
        alwaysBatchFill = config.getBoolean("alwaysBatchFill", CATEGORY_FILL, false, alwaysBatchComment);

        String forcedSizeComment = LangUtil.translate("ghostblock.config.forcedBatchSize.tooltip");
        forcedBatchSize = config.getInt("forcedBatchSize", CATEGORY_FILL, 100, 1, Integer.MAX_VALUE, forcedSizeComment);
        
        String enableAutoSaveComment = LangUtil.translate("ghostblock.config.enableAutoSave.tooltip");
        enableAutoSave = config.getBoolean("enableAutoSave", CATEGORY_SAVE, false, enableAutoSaveComment);

        String defaultFileNameComment = LangUtil.translate("ghostblock.config.defaultSaveFileName.tooltip");
        defaultSaveFileName = config.getString("defaultSaveFileName", CATEGORY_SAVE, "", defaultFileNameComment);

        String enableChatSuggestComment = LangUtil.translate("ghostblock.config.enableChatSuggestions.tooltip");
        enableChatSuggestions = config.getBoolean("enableChatSuggestions", CATEGORY_CHAT, true, enableChatSuggestComment);

        // 新增：加载命令历史滚动配置
        String enableCmdHistoryScrollComment = LangUtil.translate("ghostblock.config.enableCommandHistoryScroll.tooltip");
        enableCommandHistoryScroll = config.getBoolean("enableCommandHistoryScroll", CATEGORY_CHAT, true, enableCmdHistoryScrollComment);

        String enableAutoPlaceComment = LangUtil.translate("ghostblock.config.enableAutoPlaceOnJoin.tooltip");
        enableAutoPlaceOnJoin = config.getBoolean("enableAutoPlaceOnJoin", CATEGORY_AUTO_PLACE, false, enableAutoPlaceComment);

        String enableAutoSneakComment = LangUtil.translate("ghostblock.config.enableAutoSneakAtEdge.tooltip");
        enableAutoSneakAtEdge = config.getBoolean("enableAutoSneakAtEdge", CATEGORY_AUTO_SNEAK, false, enableAutoSneakComment);

        Property propForwardOffset = config.get(CATEGORY_AUTO_SNEAK, "autoSneakForwardOffset", 0.15, LangUtil.translate("ghostblock.config.autoSneakForwardOffset.tooltip"), 0.05, 1.0);
        autoSneakForwardOffset = propForwardOffset.getDouble();

        Property propVerticalDepth = config.get(CATEGORY_AUTO_SNEAK, "autoSneakVerticalCheckDepth", 1.0, LangUtil.translate("ghostblock.config.autoSneakVerticalCheckDepth.tooltip"), 0.1, 3.0);
        autoSneakVerticalCheckDepth = propVerticalDepth.getDouble();

        String enablePlayerESPComment = LangUtil.translate("ghostblock.config.enablePlayerESP.tooltip");
        enablePlayerESP = config.getBoolean("enablePlayerESP", CATEGORY_PLAYER_ESP, false, enablePlayerESPComment);
        
        String fastPistonBreakingComment = LangUtil.translate("ghostblock.config.fastPistonBreaking.tooltip");
        fastPistonBreaking = config.getBoolean("fastPistonBreaking", CATEGORY_GAMEPLAY_TWEAKS, false, fastPistonBreakingComment);

        // --- 加载/生成破基岩新配置项 (完整添加) ---
        String enableBedrockMinerComment = LangUtil.translate("ghostblock.config.enableBedrockMiner.tooltip");
        enableBedrockMiner = config.getBoolean("enableBedrockMiner", CATEGORY_BEDROCK_MINER, false, enableBedrockMinerComment);

        String pingSpikeComment = "额外等待的 ticks, 用于弥补高延迟. [默认: 2]";
        pingSpikeThreshold = config.getInt("pingSpikeThreshold", CATEGORY_BEDROCK_MINER, 2, 0, 100, pingSpikeComment);

        String headlessComment = "是否启用无头活塞模式 (更高效). [默认: true]";
        headlessPistonMode = config.getBoolean("headlessPistonMode", CATEGORY_BEDROCK_MINER, true, headlessComment);

        String blinkComment = "在执行任务时是否启用Blink (数据包暂存), 强烈建议开启. [默认: true]";
        blinkDuringTasksTick = config.getBoolean("blinkDuringTasksTick", CATEGORY_BEDROCK_MINER, true, blinkComment);

        String whitelistComment = "可以作为目标的方块列表 (例如 'minecraft:bedrock').";
        String[] whitelistArr = config.getStringList("blockWhitelist", CATEGORY_BEDROCK_MINER, new String[]{"minecraft:bedrock"}, whitelistComment);
        blockWhitelist = Arrays.stream(whitelistArr).map(s -> Block.blockRegistry.getObject(new ResourceLocation(s))).filter(Objects::nonNull).collect(Collectors.toSet());

        String dependComment = "可以作为支撑/依赖的方块列表 (例如 'minecraft:slime_block').";
        String[] dependArr = config.getStringList("dependBlockWhitelist", CATEGORY_BEDROCK_MINER, new String[]{"minecraft:slime_block"}, dependComment);
        dependBlockWhitelist = Arrays.stream(dependArr).map(s -> Block.blockRegistry.getObject(new ResourceLocation(s))).filter(Objects::nonNull).collect(Collectors.toSet());

        if (config.hasChanged()) {
            config.save();
            System.out.println("[Ghost-Config] 配置已加载或创建并保存更改。");
        }
    }

    // --- Getter 方法, 供 Task.java 调用 ---
    public static int getPingSpikeThreshold() { return pingSpikeThreshold; }
    public static boolean isHeadlessPistonMode() { return headlessPistonMode; }
    public static Set<Block> getDependBlockWhitelist() { return dependBlockWhitelist; }
    public static boolean isBlinkDuringTasksTick() { return blinkDuringTasksTick; }
    public static Set<Block> getBlockWhitelist() { return blockWhitelist; }
    
    // --- 已有的 Setter 方法 ---
    public static void setAlwaysBatchFill(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_FILL, "alwaysBatchFill", false); prop.set(value); alwaysBatchFill = value; config.save(); }
    public static boolean setForcedBatchSize(int value) { if (config == null || value <= 0) return false; Property prop = config.get(CATEGORY_FILL, "forcedBatchSize", 100); prop.set(value); forcedBatchSize = value; config.save(); return true; }
    public static void setEnableAutoSave(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_SAVE, "enableAutoSave", false); prop.set(value); enableAutoSave = value; config.save(); }
    public static boolean setDefaultSaveFileName(String value) { if (config == null) return false; String processedValue = (value != null) ? value.trim() : ""; Property prop = config.get(CATEGORY_SAVE, "defaultSaveFileName", ""); prop.set(processedValue); defaultSaveFileName = processedValue; config.save(); return true; }
    public static void setEnableChatSuggestions(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_CHAT, "enableChatSuggestions", true); prop.set(value); enableChatSuggestions = value; config.save(); }
    public static void setEnableCommandHistoryScroll(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_CHAT, "enableCommandHistoryScroll", true); prop.set(value); enableCommandHistoryScroll = value; config.save(); }
    public static void setEnableAutoPlaceOnJoin(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_AUTO_PLACE, "enableAutoPlaceOnJoin", false); prop.set(value); enableAutoPlaceOnJoin = value; config.save(); }
    public static void setEnableAutoSneakAtEdge(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_AUTO_SNEAK, "enableAutoSneakAtEdge", false); prop.set(value); enableAutoSneakAtEdge = value; config.save(); }
    public static void setAutoSneakForwardOffset(double value) { if (config == null || value < 0.05 || value > 1.0) return; Property prop = config.get(CATEGORY_AUTO_SNEAK, "autoSneakForwardOffset", 0.35); prop.set(value); autoSneakForwardOffset = value; config.save(); }
    public static void setAutoSneakVerticalCheckDepth(double value) { if (config == null || value < 0.1 || value > 3.0) return; Property prop = config.get(CATEGORY_AUTO_SNEAK, "autoSneakVerticalCheckDepth", 1.0); prop.set(value); autoSneakVerticalCheckDepth = value; config.save(); }
    public static void setEnablePlayerESP(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_PLAYER_ESP, "enablePlayerESP", false); prop.set(value); enablePlayerESP = value; config.save(); }
    public static void setEnableBedrockMiner(boolean value) { if (config == null) return; if (value) { setFastPistonBreaking(true, false); } Property prop = config.get(CATEGORY_BEDROCK_MINER, "enableBedrockMiner", false); prop.set(value); enableBedrockMiner = value; config.save(); }
    public static void setFastPistonBreaking(boolean value, boolean saveImmediately) { if (config == null) return; Property prop = config.get(CATEGORY_GAMEPLAY_TWEAKS, "fastPistonBreaking", false); prop.set(value); fastPistonBreaking = value; if (saveImmediately) config.save(); }
    public static Configuration getConfig() { return config; }
}