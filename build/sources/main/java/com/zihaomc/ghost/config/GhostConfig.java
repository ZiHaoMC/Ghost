package com.zihaomc.ghost.config;

import com.zihaomc.ghost.LangUtil;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;

public class GhostConfig {

    // Forge 配置对象
    private static Configuration config = null;

    // 配置类别
    public static final String CATEGORY_FILL = "fill_command";
    public static final String CATEGORY_SAVE = "save_options";
    public static final String CATEGORY_CHAT = "chat_features"; // 聊天功能类别
    public static final String CATEGORY_AUTO_PLACE = "auto_place_feature"; // 新增类别

    // --- 填充命令配置 ---
    public static boolean alwaysBatchFill = false;
    public static int forcedBatchSize = 100;

    // --- 自动保存配置 ---
    public static boolean enableAutoSave = false;
    public static String defaultSaveFileName = "";

    // --- 聊天建议配置 ---
    public static boolean enableChatSuggestions = true; // 是否启用聊天命令建议按钮（默认为 true）

    // --- 新增：自动放置配置 ---
    public static boolean enableAutoPlaceOnJoin = false; // 默认禁用

    /**
     * 初始化配置
     * @param configFile 配置文件对象 (通常在 FMLPreInitializationEvent 中获取)
     */
    public static void init(File configFile) {
        if (config == null) {
            config = new Configuration(configFile);
            loadConfig(); // 首次加载或创建配置
        }
    }

    /**
     * 加载配置，如果文件不存在则创建并写入默认值
     */
    public static void loadConfig() {
        if (config == null) {
            System.err.println("[GhostBlock-Config] 配置尚未初始化！");
            return;
        }

        config.load(); // Ensure config is loaded before reading/writing

        // --- 加载/生成填充配置值 ---
        String alwaysBatchComment = LangUtil.translate("ghostblock.config.alwaysBatchFill.tooltip");
        alwaysBatchFill = config.getBoolean("alwaysBatchFill", CATEGORY_FILL, alwaysBatchFill, alwaysBatchComment);

        String forcedSizeComment = LangUtil.translate("ghostblock.config.forcedBatchSize.tooltip");
        forcedBatchSize = config.getInt("forcedBatchSize", CATEGORY_FILL, forcedBatchSize, 0, Integer.MAX_VALUE, forcedSizeComment);
         if (forcedBatchSize <= 0) {
             forcedBatchSize = 100;
             Property prop = config.get(CATEGORY_FILL, "forcedBatchSize", 100);
             if (prop.getInt() <= 0) { // Only set if current value is invalid
                 prop.set(100);
                 System.out.println("[GhostBlock-Config] forcedBatchSize 配置值无效 (<= 0)，已重置为 100。");
             }
         }

        // --- 加载/生成自动保存配置值 ---
        String enableAutoSaveComment = LangUtil.translate("ghostblock.config.enableAutoSave.tooltip");
        enableAutoSave = config.getBoolean("enableAutoSave", CATEGORY_SAVE, enableAutoSave, enableAutoSaveComment);

        String defaultFileNameComment = LangUtil.translate("ghostblock.config.defaultSaveFileName.tooltip");
        defaultSaveFileName = config.getString("defaultSaveFileName", CATEGORY_SAVE, defaultSaveFileName, defaultFileNameComment);

        // --- 加载/生成聊天建议配置值 ---
        String enableChatSuggestComment = LangUtil.translate("ghostblock.config.enableChatSuggestions.tooltip");
        enableChatSuggestions = config.getBoolean("enableChatSuggestions", CATEGORY_CHAT, enableChatSuggestions, enableChatSuggestComment);

        // --- 新增：加载/生成自动放置配置值 ---
        String enableAutoPlaceComment = LangUtil.translate("ghostblock.config.enableAutoPlaceOnJoin.tooltip");
        enableAutoPlaceOnJoin = config.getBoolean("enableAutoPlaceOnJoin", CATEGORY_AUTO_PLACE, enableAutoPlaceOnJoin, enableAutoPlaceComment);


        // 如果配置被修改，则保存
        if (config.hasChanged()) {
            config.save();
            System.out.println("[GhostBlock-Config] 配置已加载或创建并保存更改。");
        }
    }

    // --- 设置器 ---

    public static void setAlwaysBatchFill(boolean value) {
        if (config == null) return;
        config.load(); // Load before changing
        Property prop = config.get(CATEGORY_FILL, "alwaysBatchFill", false);
        prop.set(value);
        alwaysBatchFill = value;
        config.save();
        System.out.println("[GhostBlock-Config] 配置项 alwaysBatchFill 已更新为: " + value);
    }

    public static boolean setForcedBatchSize(int value) {
        if (config == null) return false;
        if (value <= 0) {
             System.err.println("[GhostBlock-Config] 尝试设置无效的 forcedBatchSize: " + value);
             return false;
        }
        config.load(); // Load before changing
        Property prop = config.get(CATEGORY_FILL, "forcedBatchSize", 100);
        prop.set(value);
        forcedBatchSize = value;
        config.save();
         System.out.println("[GhostBlock-Config] 配置项 forcedBatchSize 已更新为: " + value);
        return true;
    }

    public static void setEnableAutoSave(boolean value) {
        if (config == null) return;
        config.load(); // Load before changing
        Property prop = config.get(CATEGORY_SAVE, "enableAutoSave", false);
        prop.set(value);
        enableAutoSave = value;
        config.save();
        System.out.println("[GhostBlock-Config] 配置项 enableAutoSave 已更新为: " + value);
    }

    public static boolean setDefaultSaveFileName(String value) {
        if (config == null) return false;
        config.load(); // Load before changing
        String processedValue = (value != null) ? value.trim() : "";
        Property prop = config.get(CATEGORY_SAVE, "defaultSaveFileName", "");
        prop.set(processedValue);
        defaultSaveFileName = processedValue;
        config.save();
        System.out.println("[GhostBlock-Config] 配置项 defaultSaveFileName 已更新为: '" + processedValue + "'");
        return true;
    }

    /**
     * 设置 enableChatSuggestions 的值并保存配置
     * @param value 新的值
     */
    public static void setEnableChatSuggestions(boolean value) {
        if (config == null) return;
        config.load(); // Load before changing
        Property prop = config.get(CATEGORY_CHAT, "enableChatSuggestions", true); // 使用新类别和默认值
        prop.set(value);
        enableChatSuggestions = value;
        config.save();
        System.out.println("[GhostBlock-Config] 配置项 enableChatSuggestions 已更新为: " + value);
    }

    // --- 新增：自动放置设置器 ---
    public static void setEnableAutoPlaceOnJoin(boolean value) {
        if (config == null) return;
        config.load(); // Load before changing
        Property prop = config.get(CATEGORY_AUTO_PLACE, "enableAutoPlaceOnJoin", false); // 使用新类别
        prop.set(value);
        enableAutoPlaceOnJoin = value;
        config.save();
        System.out.println("[GhostBlock-Config] 配置项 enableAutoPlaceOnJoin 已更新为: " + value);
    }

     /**
      * 获取原始的 Configuration 对象，主要用于调试或特殊情况
      * @return Configuration 对象
      */
     public static Configuration getConfig() {
         return config;
     }
}