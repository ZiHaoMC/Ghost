package com.zihaomc.ghost.config;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 重构后的配置类。
 * - 使用静态内部类对配置项进行分组。
 * - 简化了加载和保存逻辑，减少了重复代码。
 * - 为 GhostConfigCommand 提供了统一的更新接口。
 */
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
    public static final String CATEGORY_TRANSLATION = "translation_api";
    public static final String CATEGORY_NOTE = "note_taking";
    public static final String CATEGORY_GUI_TWEAKS = "gui_tweaks";
    public static final String CATEGORY_AUTO_MINE = "auto_mine_feature";
    public static final String CATEGORY_AUTO_CRAFT = "auto_craft_feature";
    public static final String CATEGORY_SKYBLOCK = "skyblock_features";
    public static final String CATEGORY_BUILD_GUESS = "build_guess_feature";

    // --- 用于命令的统一配置更新器 ---
    public static final Map<String, BiConsumer<String, String>> settingUpdaters = new HashMap<>();

    // --- 分组后的配置项 ---
    public static class FillCommand {
        public static boolean alwaysBatchFill;
        public static int forcedBatchSize;
    }

    public static class SaveOptions {
        public static boolean enableAutoSave;
        public static String defaultSaveFileName;
    }

    public static class ChatFeatures {
        public static boolean enableChatSuggestions;
        public static boolean enableCommandHistoryScroll;
        public static boolean disableTwitchAtKey;
    }

    public static class AutoPlace {
        public static boolean enableAutoPlaceOnJoin;
    }

    public static class AutoSneak {
        public static boolean enableAutoSneakAtEdge;
        public static double autoSneakForwardOffset;
        public static double autoSneakVerticalCheckDepth;
    }

    public static class PlayerESP {
        public static boolean enablePlayerESP;
    }

    public static class BedrockMiner {
        public static boolean enableBedrockMiner;
        public static int pingSpikeThreshold;
        public static boolean headlessPistonMode;
        public static boolean blinkDuringTasksTick;
        public static Set<Block> blockWhitelist = new HashSet<>();
        public static Set<Block> dependBlockWhitelist = new HashSet<>();
        
        public static int getPingSpikeThreshold() { return pingSpikeThreshold; }
        public static boolean isHeadlessPistonMode() { return headlessPistonMode; }
        public static Set<Block> getDependBlockWhitelist() { return dependBlockWhitelist; }
        public static boolean isBlinkDuringTasksTick() { return blinkDuringTasksTick; }
        public static Set<Block> getBlockWhitelist() { return blockWhitelist; }
    }

    public static class GameplayTweaks {
        public static boolean fastPistonBreaking;
        public static boolean hideArrowsOnPlayers;
    }

    public static class Translation {
        public static boolean enableChatTranslation;
        public static boolean enableSignTranslation;
        public static boolean enableItemTranslation;
        public static boolean enableAutomaticTranslation;
        public static boolean autoShowCachedTranslation;
        public static boolean showTranslationOnly;
        public static boolean hideTranslationKeybindTooltip;
        public static boolean showProviderSwitchButtons;
        public static String niuTransApiKey;
        public static String translationSourceLang;
        public static String translationTargetLang;
        public static String translationProvider; 
    }

    public static class NoteTaking {
        public static boolean enableNoteFeature;
        public static boolean enableAdvancedEditing;
        public static boolean enableMarkdownRendering;
        public static boolean enableColorRendering;
        public static boolean enableAmpersandColorCodes;
    }

    public static class GuiTweaks {
        public static boolean fixGuiStateLossOnResize;
    }
    
    public static class AutoMine {
        public static double rotationSpeed;
        public static double maxReachDistance;
        public static int searchRadius;
        public static boolean serverRotation;
        public static boolean instantRotation;
        public static boolean sneakOnMine;
        public static boolean enableRandomMovements;
        public static int mineTimeoutSeconds;
        public static boolean enableRandomRotationSpeed;
        public static double rotationSpeedVariability;
        public static boolean preventDiggingDown;
        public static int randomMoveInterval;
        public static int randomMoveDuration;
        public static int randomMoveIntervalVariability;
        public static boolean enableVeinMining;
        public static String miningMode;
        public static boolean antiCheatCheck;
        public static boolean enableVoidSafetyCheck;
        public static int voidSafetyYLimit;
        public static boolean stopOnTimeout;
        public static boolean enableMithrilOptimization;
        public static String[] titaniumBlockIds;
        public static int mithrilCleanupThreshold;
        public static boolean enableAutomaticToolSwitching;
    }

    public static class AutoCraft {
        public static int autoCraftPlacementDelayTicks;
        public static int autoCraftCycleDelayTicks;
        public static int autoCraftMenuOpenDelayTicks;
        public static int autoCraftTableOpenDelayTicks;
        public static int autoCraftPickupStashWaitTicks;
    }

    public static class Skyblock {
        public static boolean enableDungeonProfit;
    }

    public static class BuildGuess {
        public static boolean enableBuildGuess;
    }

    // --- 核心方法 ---
    public static void init(File configFile) {
        if (config == null) {
            config = new Configuration(configFile);
            loadConfig();
        }
    }

    public static void loadConfig() {
        if (config == null) {
            LogUtil.error("log.config.notInitialized");
            return;
        }
        config.load();

        loadFillCommandSettings();
        loadSaveOptionsSettings();
        loadChatFeaturesSettings();
        loadAutoPlaceSettings();
        loadAutoSneakSettings();
        loadPlayerESPSettings();
        loadBedrockMinerSettings();
        loadGameplayTweaksSettings();
        loadTranslationSettings();
        loadNoteTakingSettings();
        loadGuiTweaksSettings();
        loadAutoMineSettings();
        loadAutoCraftSettings();
        loadSkyblockSettings();
        loadBuildGuessSettings();

        if (config.hasChanged()) {
            config.save();
            LogUtil.info("log.config.loaded");
        }
        
        initializeUpdaters();
    }
    
    // --- 加载逻辑 (分组) ---
    private static void loadFillCommandSettings() {
        FillCommand.alwaysBatchFill = loadBoolean(CATEGORY_FILL, "alwaysBatchFill", false, "ghostblock.config.alwaysBatchFill.tooltip");
        FillCommand.forcedBatchSize = loadInt(CATEGORY_FILL, "forcedBatchSize", 100, 1, Integer.MAX_VALUE, "ghostblock.config.forcedBatchSize.tooltip");
    }
    
    private static void loadSaveOptionsSettings() {
        SaveOptions.enableAutoSave = loadBoolean(CATEGORY_SAVE, "enableAutoSave", false, "ghostblock.config.enableAutoSave.tooltip");
        SaveOptions.defaultSaveFileName = loadString(CATEGORY_SAVE, "defaultSaveFileName", "", "ghostblock.config.defaultSaveFileName.tooltip");
    }

    private static void loadChatFeaturesSettings() {
        ChatFeatures.enableChatSuggestions = loadBoolean(CATEGORY_CHAT, "enableChatSuggestions", true, "ghostblock.config.enableChatSuggestions.tooltip");
        ChatFeatures.enableCommandHistoryScroll = loadBoolean(CATEGORY_CHAT, "enableCommandHistoryScroll", true, "ghostblock.config.enableCommandHistoryScroll.tooltip");
        ChatFeatures.disableTwitchAtKey = loadBoolean(CATEGORY_CHAT, "disableTwitchAtKey", true, "ghost.config.comment.disableTwitchAtKey");
    }

    private static void loadAutoPlaceSettings() {
        AutoPlace.enableAutoPlaceOnJoin = loadBoolean(CATEGORY_AUTO_PLACE, "enableAutoPlaceOnJoin", false, "ghostblock.config.enableAutoPlaceOnJoin.tooltip");
    }

    private static void loadAutoSneakSettings() {
        AutoSneak.enableAutoSneakAtEdge = loadBoolean(CATEGORY_AUTO_SNEAK, "enableAutoSneakAtEdge", false, "ghostblock.config.enableAutoSneakAtEdge.tooltip");
        AutoSneak.autoSneakForwardOffset = loadDouble(CATEGORY_AUTO_SNEAK, "autoSneakForwardOffset", 0.15, 0.05, 1.0, "ghostblock.config.autoSneakForwardOffset.tooltip");
        AutoSneak.autoSneakVerticalCheckDepth = loadDouble(CATEGORY_AUTO_SNEAK, "autoSneakVerticalCheckDepth", 1.0, 0.1, 3.0, "ghostblock.config.autoSneakVerticalCheckDepth.tooltip");
    }

    private static void loadPlayerESPSettings() {
        PlayerESP.enablePlayerESP = loadBoolean(CATEGORY_PLAYER_ESP, "enablePlayerESP", false, "ghostblock.config.enablePlayerESP.tooltip");
    }

    private static void loadBedrockMinerSettings() {
        BedrockMiner.enableBedrockMiner = loadBoolean(CATEGORY_BEDROCK_MINER, "enableBedrockMiner", false, "ghostblock.config.enableBedrockMiner.tooltip");
        BedrockMiner.pingSpikeThreshold = loadInt(CATEGORY_BEDROCK_MINER, "pingSpikeThreshold", 2, 0, 100, "ghost.config.comment.pingSpike");
        BedrockMiner.headlessPistonMode = loadBoolean(CATEGORY_BEDROCK_MINER, "headlessPistonMode", true, "ghost.config.comment.headlessPiston");
        BedrockMiner.blinkDuringTasksTick = loadBoolean(CATEGORY_BEDROCK_MINER, "blinkDuringTasksTick", true, "ghost.config.comment.blink");
        
        String[] whitelistArr = loadStringList(CATEGORY_BEDROCK_MINER, "blockWhitelist", new String[]{"minecraft:bedrock"}, "ghost.config.comment.blockWhitelist");
        BedrockMiner.blockWhitelist = Arrays.stream(whitelistArr).map(s -> Block.blockRegistry.getObject(new ResourceLocation(s))).filter(Objects::nonNull).collect(Collectors.toSet());

        String[] dependArr = loadStringList(CATEGORY_BEDROCK_MINER, "dependBlockWhitelist", new String[]{"minecraft:slime_block"}, "ghost.config.comment.dependWhitelist");
        BedrockMiner.dependBlockWhitelist = Arrays.stream(dependArr).map(s -> Block.blockRegistry.getObject(new ResourceLocation(s))).filter(Objects::nonNull).collect(Collectors.toSet());
    }
    
    private static void loadGameplayTweaksSettings() {
        GameplayTweaks.fastPistonBreaking = loadBoolean(CATEGORY_GAMEPLAY_TWEAKS, "fastPistonBreaking", false, "ghostblock.config.fastPistonBreaking.tooltip");
        GameplayTweaks.hideArrowsOnPlayers = loadBoolean(CATEGORY_GAMEPLAY_TWEAKS, "hideArrowsOnPlayers", false, "ghost.config.comment.hideArrowsOnPlayers");
    }

    private static void loadTranslationSettings() {
        Translation.enableChatTranslation = loadBoolean(CATEGORY_TRANSLATION, "enableChatTranslation", false, "ghost.config.comment.enableChatTranslation");
        Translation.enableSignTranslation = loadBoolean(CATEGORY_TRANSLATION, "enableSignTranslation", false, "ghost.config.comment.enableSignTranslation");
        Translation.enableItemTranslation = loadBoolean(CATEGORY_TRANSLATION, "enableItemTranslation", false, "ghost.config.comment.enableItemTranslation");
        Translation.enableAutomaticTranslation = loadBoolean(CATEGORY_TRANSLATION, "enableAutomaticTranslation", false, "ghost.config.comment.enableAutomaticTranslation");
        Translation.autoShowCachedTranslation = loadBoolean(CATEGORY_TRANSLATION, "autoShowCachedTranslation", true, "ghost.config.comment.autoShowCachedTranslation");
        Translation.showTranslationOnly = loadBoolean(CATEGORY_TRANSLATION, "showTranslationOnly", false, "ghost.config.comment.showTranslationOnly");
        Translation.hideTranslationKeybindTooltip = loadBoolean(CATEGORY_TRANSLATION, "hideTranslationKeybindTooltip", false, "ghost.config.comment.hideTranslationKeybindTooltip");
        Translation.showProviderSwitchButtons = loadBoolean(CATEGORY_TRANSLATION, "showProviderSwitchButtons", true, "ghost.config.comment.showProviderSwitchButtons");
        Translation.niuTransApiKey = loadString(CATEGORY_TRANSLATION, "niuTransApiKey", "", "ghostblock.config.niuTransApiKey.tooltip");
        Translation.translationSourceLang = loadString(CATEGORY_TRANSLATION, "translationSourceLang", "auto", "ghostblock.config.translationSourceLang.tooltip");
        Translation.translationTargetLang = loadString(CATEGORY_TRANSLATION, "translationTargetLang", "zh", "ghostblock.config.translationTargetLang.tooltip");
        Translation.translationProvider = loadString(CATEGORY_TRANSLATION, "translationProvider", "GOOGLE", "ghost.config.comment.translationProvider");
    }
    
    private static void loadNoteTakingSettings() {
        NoteTaking.enableNoteFeature = loadBoolean(CATEGORY_NOTE, "enableNoteFeature", true, "ghost.config.comment.enableNoteFeature");
        NoteTaking.enableAdvancedEditing = loadBoolean(CATEGORY_NOTE, "enableAdvancedEditing", true, "ghost.config.comment.enableAdvancedEditing");
        NoteTaking.enableMarkdownRendering = loadBoolean(CATEGORY_NOTE, "enableMarkdownRendering", true, "ghost.config.comment.enableMarkdownRendering");
        NoteTaking.enableColorRendering = loadBoolean(CATEGORY_NOTE, "enableColorRendering", true, "ghost.config.comment.enableColorRendering");
        NoteTaking.enableAmpersandColorCodes = loadBoolean(CATEGORY_NOTE, "enableAmpersandColorCodes", true, "ghost.config.comment.enableAmpersandColorCodes");
    }

    private static void loadGuiTweaksSettings() {
        GuiTweaks.fixGuiStateLossOnResize = loadBoolean(CATEGORY_GUI_TWEAKS, "fixGuiStateLossOnResize", true, "ghost.config.comment.fixGuiStateLossOnResize");
    }

    private static void loadAutoMineSettings() {
        AutoMine.rotationSpeed = loadDouble(CATEGORY_AUTO_MINE, "rotationSpeed", 10.0, 1.0, 180.0, "ghost.config.comment.autoMineRotationSpeed");
        AutoMine.maxReachDistance = loadDouble(CATEGORY_AUTO_MINE, "maxReachDistance", 4.5, 1.0, 6.0, "ghost.config.comment.autoMineMaxReach");
        AutoMine.searchRadius = loadInt(CATEGORY_AUTO_MINE, "searchRadius", 7, 3, 15, "ghost.config.comment.autoMineSearchRadius");
        AutoMine.mineTimeoutSeconds = loadInt(CATEGORY_AUTO_MINE, "mineTimeoutSeconds", 10, 1, 600, "ghost.config.comment.autoMineTimeout");
        AutoMine.instantRotation = loadBoolean(CATEGORY_AUTO_MINE, "instantRotation", false, "ghost.config.comment.autoMineInstantRotation");
        AutoMine.serverRotation = loadBoolean(CATEGORY_AUTO_MINE, "serverRotation", false, "ghost.config.comment.autoMineServerRotation");
        AutoMine.sneakOnMine = loadBoolean(CATEGORY_AUTO_MINE, "sneakOnMine", false, "ghost.config.comment.autoMineSneak");
        AutoMine.enableRandomMovements = loadBoolean(CATEGORY_AUTO_MINE, "enableRandomMovements", false, "ghost.config.comment.autoMineEnableRandomMove");
        AutoMine.enableRandomRotationSpeed = loadBoolean(CATEGORY_AUTO_MINE, "enableRandomRotationSpeed", true, "ghost.config.comment.autoMineEnableRandomSpeed");
        AutoMine.rotationSpeedVariability = loadDouble(CATEGORY_AUTO_MINE, "rotationSpeedVariability", 5.0, 0.0, 20.0, "ghost.config.comment.autoMineSpeedVariability");
        AutoMine.preventDiggingDown = loadBoolean(CATEGORY_AUTO_MINE, "preventDiggingDown", false, "ghost.config.comment.autoMinePreventDiggingDown");
        AutoMine.randomMoveInterval = loadInt(CATEGORY_AUTO_MINE, "randomMoveInterval", 200, 10, 400, "ghost.config.comment.autoMineRandomMoveInterval");
        AutoMine.randomMoveDuration = loadInt(CATEGORY_AUTO_MINE, "randomMoveDuration", 10, 1, 20, "ghost.config.comment.autoMineRandomMoveDuration");
        AutoMine.randomMoveIntervalVariability = loadInt(CATEGORY_AUTO_MINE, "randomMoveIntervalVariability", 100, 0, 1000, "ghost.config.comment.autoMineRandomMoveIntervalVariability");
        AutoMine.enableVeinMining = loadBoolean(CATEGORY_AUTO_MINE, "enableVeinMining", true, "ghost.config.comment.autoMineEnableVeinMining");
        AutoMine.miningMode = loadString(CATEGORY_AUTO_MINE, "miningMode", "SIMULATE", "ghost.config.comment.autoMineMiningMode");
        AutoMine.antiCheatCheck = loadBoolean(CATEGORY_AUTO_MINE, "antiCheatCheck", true, "ghost.config.comment.autoMineAntiCheatCheck");
        AutoMine.enableVoidSafetyCheck = loadBoolean(CATEGORY_AUTO_MINE, "enableVoidSafetyCheck", true, "ghost.config.comment.autoMineVoidSafetyCheck");
        AutoMine.voidSafetyYLimit = loadInt(CATEGORY_AUTO_MINE, "voidSafetyYLimit", 1, 0, 255, "ghost.config.comment.autoMineVoidSafetyYLimit");
        AutoMine.stopOnTimeout = loadBoolean(CATEGORY_AUTO_MINE, "stopOnTimeout", true, "ghost.config.comment.autoMineStopOnTimeout");
        AutoMine.enableMithrilOptimization = loadBoolean(CATEGORY_AUTO_MINE, "enableMithrilOptimization", false, "ghost.config.comment.autoMineMithrilOptimization");
        AutoMine.titaniumBlockIds = loadStringList(CATEGORY_AUTO_MINE, "titaniumBlockIds", new String[]{"minecraft:stone:4"}, "ghost.config.comment.autoMineTitaniumBlockIds");
        AutoMine.mithrilCleanupThreshold = loadInt(CATEGORY_AUTO_MINE, "mithrilCleanupThreshold", 5, 1, 50, "ghost.config.comment.autoMineMithrilCleanupThreshold");
        AutoMine.enableAutomaticToolSwitching = loadBoolean(CATEGORY_AUTO_MINE, "enableAutomaticToolSwitching", false, "ghost.config.comment.autoMineEnableToolSwitching");
    }

    private static void loadAutoCraftSettings() {
        AutoCraft.autoCraftPlacementDelayTicks = loadInt(CATEGORY_AUTO_CRAFT, "autoCraftPlacementDelayTicks", 1, 1, 100, "ghost.config.comment.autoCraftPlacementDelay");
        AutoCraft.autoCraftCycleDelayTicks = loadInt(CATEGORY_AUTO_CRAFT, "autoCraftCycleDelayTicks", 5, 1, 200, "ghost.config.comment.autoCraftCycleDelay");
        AutoCraft.autoCraftMenuOpenDelayTicks = loadInt(CATEGORY_AUTO_CRAFT, "autoCraftMenuOpenDelayTicks", 5, 1, 200, "ghost.config.comment.autoCraftMenuOpenDelay");
        AutoCraft.autoCraftTableOpenDelayTicks = loadInt(CATEGORY_AUTO_CRAFT, "autoCraftTableOpenDelayTicks", 8, 1, 100, "ghost.config.comment.autoCraftTableOpenDelay");
        AutoCraft.autoCraftPickupStashWaitTicks = loadInt(CATEGORY_AUTO_CRAFT, "autoCraftPickupStashWaitTicks", 5, 1, 200, "ghost.config.comment.autoCraftPickupStashWait");
    }

    private static void loadSkyblockSettings() {
        Skyblock.enableDungeonProfit = loadBoolean(CATEGORY_SKYBLOCK, "enableDungeonProfit", false, "ghost.config.comment.dungeonProfit");
    }

    private static void loadBuildGuessSettings() {
        BuildGuess.enableBuildGuess = loadBoolean(CATEGORY_BUILD_GUESS, "enableBuildGuess", false, "ghost.config.comment.buildGuess");
    }

    // --- 加载辅助方法 ---
    private static boolean loadBoolean(String category, String key, boolean defaultValue, String commentKey) {
        return config.getBoolean(key, category, defaultValue, LangUtil.translate(commentKey));
    }

    private static int loadInt(String category, String key, int defaultValue, int min, int max, String commentKey) {
        return config.getInt(key, category, defaultValue, min, max, LangUtil.translate(commentKey));
    }
    
    private static double loadDouble(String category, String key, double defaultValue, double min, double max, String commentKey) {
        return config.get(category, key, defaultValue, LangUtil.translate(commentKey), min, max).getDouble();
    }
        
    private static String loadString(String category, String key, String defaultValue, String commentKey) {
        return config.getString(key, category, defaultValue, LangUtil.translate(commentKey));
    }

    private static String[] loadStringList(String category, String key, String[] defaultValue, String commentKey) {
        return config.getStringList(key, category, defaultValue, LangUtil.translate(commentKey));
    }
    
    // --- Setter 方法 (用于命令修改配置) ---
    private static void updateAndSave(String category, String key, Object value, Runnable fieldUpdater) {
        if (config == null) return;
        Property prop = config.get(category, key, "");
        prop.set(String.valueOf(value));
        config.save();
        fieldUpdater.run();
    }
    
    public static void setAlwaysBatchFill(boolean value) {
        updateAndSave(CATEGORY_FILL, "alwaysBatchFill", value, () -> FillCommand.alwaysBatchFill = value);
    }

    public static boolean setForcedBatchSize(int value) {
        if (value <= 0) return false;
        updateAndSave(CATEGORY_FILL, "forcedBatchSize", value, () -> FillCommand.forcedBatchSize = value);
        return true;
    }

    public static void setEnableAutoSave(boolean value) {
        updateAndSave(CATEGORY_SAVE, "enableAutoSave", value, () -> SaveOptions.enableAutoSave = value);
    }

    public static boolean setDefaultSaveFileName(String value) {
        String processedValue = (value != null) ? value.trim() : "";
        updateAndSave(CATEGORY_SAVE, "defaultSaveFileName", processedValue, () -> SaveOptions.defaultSaveFileName = processedValue);
        return true;
    }

    public static void setEnableChatSuggestions(boolean value) {
        updateAndSave(CATEGORY_CHAT, "enableChatSuggestions", value, () -> ChatFeatures.enableChatSuggestions = value);
    }

    public static void setEnableCommandHistoryScroll(boolean value) {
        updateAndSave(CATEGORY_CHAT, "enableCommandHistoryScroll", value, () -> ChatFeatures.enableCommandHistoryScroll = value);
    }
    
    public static void setDisableTwitchAtKey(boolean value) {
        updateAndSave(CATEGORY_CHAT, "disableTwitchAtKey", value, () -> ChatFeatures.disableTwitchAtKey = value);
    }

    public static void setEnableChatTranslation(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "enableChatTranslation", value, () -> Translation.enableChatTranslation = value);
    }

    public static void setEnableSignTranslation(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "enableSignTranslation", value, () -> Translation.enableSignTranslation = value);
    }

    public static void setEnableItemTranslation(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "enableItemTranslation", value, () -> Translation.enableItemTranslation = value);
    }

    public static void setEnableAutomaticTranslation(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "enableAutomaticTranslation", value, () -> Translation.enableAutomaticTranslation = value);
    }

    public static void setAutoShowCachedTranslation(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "autoShowCachedTranslation", value, () -> Translation.autoShowCachedTranslation = value);
    }

    public static void setShowTranslationOnly(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "showTranslationOnly", value, () -> Translation.showTranslationOnly = value);
    }

    public static void setHideTranslationKeybindTooltip(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "hideTranslationKeybindTooltip", value, () -> Translation.hideTranslationKeybindTooltip = value);
    }

    public static void setEnableAutoPlaceOnJoin(boolean value) {
        updateAndSave(CATEGORY_AUTO_PLACE, "enableAutoPlaceOnJoin", value, () -> AutoPlace.enableAutoPlaceOnJoin = value);
    }

    public static void setEnableAutoSneakAtEdge(boolean value) {
        updateAndSave(CATEGORY_AUTO_SNEAK, "enableAutoSneakAtEdge", value, () -> AutoSneak.enableAutoSneakAtEdge = value);
    }

    public static void setAutoSneakForwardOffset(double value) {
        if (value < 0.05 || value > 1.0) return;
        updateAndSave(CATEGORY_AUTO_SNEAK, "autoSneakForwardOffset", value, () -> AutoSneak.autoSneakForwardOffset = value);
    }

    public static void setAutoSneakVerticalCheckDepth(double value) {
        if (value < 0.1 || value > 3.0) return;
        updateAndSave(CATEGORY_AUTO_SNEAK, "autoSneakVerticalCheckDepth", value, () -> AutoSneak.autoSneakVerticalCheckDepth = value);
    }

    public static void setEnablePlayerESP(boolean value) {
        updateAndSave(CATEGORY_PLAYER_ESP, "enablePlayerESP", value, () -> PlayerESP.enablePlayerESP = value);
    }

    public static void setEnableBedrockMiner(boolean value) {
        if (value) {
            setFastPistonBreaking(true, false);
        }
        updateAndSave(CATEGORY_BEDROCK_MINER, "enableBedrockMiner", value, () -> BedrockMiner.enableBedrockMiner = value);
    }

    public static void setFastPistonBreaking(boolean value, boolean saveImmediately) {
        if (config == null) return;
        Property prop = config.get(CATEGORY_GAMEPLAY_TWEAKS, "fastPistonBreaking", false);
        prop.set(value);
        GameplayTweaks.fastPistonBreaking = value;
        if (saveImmediately) {
            config.save();
        }
    }

    public static void setHideArrowsOnPlayers(boolean value) {
        updateAndSave(CATEGORY_GAMEPLAY_TWEAKS, "hideArrowsOnPlayers", value, () -> GameplayTweaks.hideArrowsOnPlayers = value);
    }

    public static void setEnableNoteFeature(boolean value) {
        updateAndSave(CATEGORY_NOTE, "enableNoteFeature", value, () -> NoteTaking.enableNoteFeature = value);
    }

    public static void setNiuTransApiKey(String value) {
        updateAndSave(CATEGORY_TRANSLATION, "niuTransApiKey", value, () -> Translation.niuTransApiKey = value);
    }

    public static void setTranslationSourceLang(String value) {
        updateAndSave(CATEGORY_TRANSLATION, "translationSourceLang", value, () -> Translation.translationSourceLang = value);
    }

    public static void setTranslationTargetLang(String value) {
        updateAndSave(CATEGORY_TRANSLATION, "translationTargetLang", value, () -> Translation.translationTargetLang = value);
    }

    public static void setTranslationProvider(String value) {
        updateAndSave(CATEGORY_TRANSLATION, "translationProvider", value, () -> Translation.translationProvider = value);
    }
    
    public static void setShowProviderSwitchButtons(boolean value) {
        updateAndSave(CATEGORY_TRANSLATION, "showProviderSwitchButtons", value, () -> Translation.showProviderSwitchButtons = value);
    }

    public static void setEnableAdvancedEditing(boolean value) {
        updateAndSave(CATEGORY_NOTE, "enableAdvancedEditing", value, () -> NoteTaking.enableAdvancedEditing = value);
    }

    public static void setEnableMarkdownRendering(boolean value) {
        updateAndSave(CATEGORY_NOTE, "enableMarkdownRendering", value, () -> NoteTaking.enableMarkdownRendering = value);
    }

    public static void setEnableColorRendering(boolean value) {
        updateAndSave(CATEGORY_NOTE, "enableColorRendering", value, () -> NoteTaking.enableColorRendering = value);
    }

    public static void setEnableAmpersandColorCodes(boolean value) {
        updateAndSave(CATEGORY_NOTE, "enableAmpersandColorCodes", value, () -> NoteTaking.enableAmpersandColorCodes = value);
    }

    public static void setFixGuiStateLossOnResize(boolean value) {
        updateAndSave(CATEGORY_GUI_TWEAKS, "fixGuiStateLossOnResize", value, () -> GuiTweaks.fixGuiStateLossOnResize = value);
    }
    
    public static void setAutoMineRotationSpeed(double value) {
        if (value < 1.0 || value > 180.0) return;
        updateAndSave(CATEGORY_AUTO_MINE, "rotationSpeed", value, () -> AutoMine.rotationSpeed = value);
    }
    
    public static void setAutoMineMaxReachDistance(double value) {
        if (value < 1.0 || value > 6.0) return;
        updateAndSave(CATEGORY_AUTO_MINE, "maxReachDistance", value, () -> AutoMine.maxReachDistance = value);
    }
    
    public static void setAutoMineSearchRadius(int value) {
        if (value < 3 || value > 15) return;
        updateAndSave(CATEGORY_AUTO_MINE, "searchRadius", value, () -> AutoMine.searchRadius = value);
    }

    public static void setAutoMineServerRotation(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "serverRotation", value, () -> AutoMine.serverRotation = value);
    }

    public static void setAutoMineInstantRotation(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "instantRotation", value, () -> AutoMine.instantRotation = value);
    }

    public static void setAutoMineSneak(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "sneakOnMine", value, () -> AutoMine.sneakOnMine = value);
    }

    public static void setAutoMineEnableRandomMove(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "enableRandomMovements", value, () -> AutoMine.enableRandomMovements = value);
    }

    public static void setAutoMineTimeout(int value) {
        if (value < 1 || value > 600) return;
        updateAndSave(CATEGORY_AUTO_MINE, "mineTimeoutSeconds", value, () -> AutoMine.mineTimeoutSeconds = value);
    }

    public static void setAutoMineEnableRandomSpeed(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "enableRandomRotationSpeed", value, () -> AutoMine.enableRandomRotationSpeed = value);
    }

    public static void setAutoMineSpeedVariability(double value) {
        if (value < 0.0 || value > 20.0) return;
        updateAndSave(CATEGORY_AUTO_MINE, "rotationSpeedVariability", value, () -> AutoMine.rotationSpeedVariability = value);
    }

    public static void setAutoMinePreventDiggingDown(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "preventDiggingDown", value, () -> AutoMine.preventDiggingDown = value);
    }

    public static void setAutoMineRandomMoveInterval(int value) {
        if (value < 10 || value > 400) return;
        updateAndSave(CATEGORY_AUTO_MINE, "randomMoveInterval", value, () -> AutoMine.randomMoveInterval = value);
    }

    public static void setAutoMineRandomMoveDuration(int value) {
        if (value < 1 || value > 20) return;
        updateAndSave(CATEGORY_AUTO_MINE, "randomMoveDuration", value, () -> AutoMine.randomMoveDuration = value);
    }

    public static void setAutoMineRandomMoveIntervalVariability(int value) {
        if (value < 0 || value > 1000) return;
        updateAndSave(CATEGORY_AUTO_MINE, "randomMoveIntervalVariability", value, () -> AutoMine.randomMoveIntervalVariability = value);
    }

    public static void setAutoMineEnableVeinMining(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "enableVeinMining", value, () -> AutoMine.enableVeinMining = value);
    }
    
    public static void setAutoMineMiningMode(String value) {
        String upperValue = value.toUpperCase();
        List<String> validModes = Arrays.asList("SIMULATE", "PACKET_NORMAL", "PACKET_INSTANT");
        if (validModes.contains(upperValue)) {
            updateAndSave(CATEGORY_AUTO_MINE, "miningMode", upperValue, () -> AutoMine.miningMode = upperValue);
        } else {
            throw new RuntimeException(new CommandException("Invalid mining mode. Valid options are: SIMULATE, PACKET_NORMAL, PACKET_INSTANT"));
        }
    }
    
    public static void setAutoMineAntiCheatCheck(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "antiCheatCheck", value, () -> AutoMine.antiCheatCheck = value);
    }

    public static void setAutoMineEnableVoidSafetyCheck(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "enableVoidSafetyCheck", value, () -> AutoMine.enableVoidSafetyCheck = value);
    }

    public static void setAutoMineVoidSafetyYLimit(int value) {
        if (value < 0 || value > 255) return;
        updateAndSave(CATEGORY_AUTO_MINE, "voidSafetyYLimit", value, () -> AutoMine.voidSafetyYLimit = value);
    }
    
    public static void setAutoMineStopOnTimeout(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "stopOnTimeout", value, () -> AutoMine.stopOnTimeout = value);
    }

    public static void setAutoMineEnableMithrilOptimization(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "enableMithrilOptimization", value, () -> AutoMine.enableMithrilOptimization = value);
    }

    public static void setAutoMineMithrilCleanupThreshold(int value) {
        if (value < 1 || value > 50) return;
        updateAndSave(CATEGORY_AUTO_MINE, "mithrilCleanupThreshold", value, () -> AutoMine.mithrilCleanupThreshold = value);
    }

    public static void setAutoMineEnableAutomaticToolSwitching(boolean value) {
        updateAndSave(CATEGORY_AUTO_MINE, "enableAutomaticToolSwitching", value, () -> AutoMine.enableAutomaticToolSwitching = value);
    }
    
    public static void setAutoCraftPlacementDelayTicks(int value) {
        if (value < 1 || value > 100) return;
        updateAndSave(CATEGORY_AUTO_CRAFT, "autoCraftPlacementDelayTicks", value, () -> AutoCraft.autoCraftPlacementDelayTicks = value);
    }

    public static void setAutoCraftCycleDelayTicks(int value) {
        if (value < 1 || value > 200) return;
        updateAndSave(CATEGORY_AUTO_CRAFT, "autoCraftCycleDelayTicks", value, () -> AutoCraft.autoCraftCycleDelayTicks = value);
    }

    public static void setAutoCraftMenuOpenDelayTicks(int value) {
        if (value < 1 || value > 100) return;
        updateAndSave(CATEGORY_AUTO_CRAFT, "autoCraftMenuOpenDelayTicks", value, () -> AutoCraft.autoCraftMenuOpenDelayTicks = value);
    }
    
    public static void setAutoCraftTableOpenDelayTicks(int value) {
        if (value < 1 || value > 100) return;
        updateAndSave(CATEGORY_AUTO_CRAFT, "autoCraftTableOpenDelayTicks", value, () -> AutoCraft.autoCraftTableOpenDelayTicks = value);
    }

    public static void setAutoCraftPickupStashWaitTicks(int value) {
        if (value < 1 || value > 200) return;
        updateAndSave(CATEGORY_AUTO_CRAFT, "autoCraftPickupStashWaitTicks", value, () -> AutoCraft.autoCraftPickupStashWaitTicks = value);
    }

    public static void setEnableDungeonProfit(boolean value) {
        updateAndSave(CATEGORY_SKYBLOCK, "enableDungeonProfit", value, () -> Skyblock.enableDungeonProfit = value);
    }

    public static void setEnableBuildGuess(boolean value) {
        updateAndSave(CATEGORY_BUILD_GUESS, "enableBuildGuess", value, () -> BuildGuess.enableBuildGuess = value);
    }

    public static Configuration getConfig() {
        return config;
    }
    
    // --- 命令更新器注册 ---
    private static void initializeUpdaters() {
        settingUpdaters.clear();
        
        settingUpdaters.put("alwaysbatchfill", (k, v) -> setAlwaysBatchFill(parseBoolean(v)));
        settingUpdaters.put("forcedbatchsize", (k, v) -> setForcedBatchSize(parseInt(v)));
        settingUpdaters.put("enableautosave", (k, v) -> setEnableAutoSave(parseBoolean(v)));
        settingUpdaters.put("defaultsavename", (k, v) -> setDefaultSaveFileName(v));
        settingUpdaters.put("enablechatsuggestions", (k, v) -> setEnableChatSuggestions(parseBoolean(v)));
        settingUpdaters.put("enablecommandhistoryscroll", (k, v) -> setEnableCommandHistoryScroll(parseBoolean(v)));
        settingUpdaters.put("disabletwitchatkey", (k, v) -> setDisableTwitchAtKey(parseBoolean(v)));
        settingUpdaters.put("enableautoplaceonjoin", (k, v) -> setEnableAutoPlaceOnJoin(parseBoolean(v)));
        settingUpdaters.put("enableautosneakatedge", (k, v) -> setEnableAutoSneakAtEdge(parseBoolean(v)));
        settingUpdaters.put("autosneakforwardoffset", (k, v) -> setAutoSneakForwardOffset(parseDouble(v)));
        settingUpdaters.put("autosneakverticalcheckdepth", (k, v) -> setAutoSneakVerticalCheckDepth(parseDouble(v)));
        settingUpdaters.put("enableplayeresp", (k, v) -> setEnablePlayerESP(parseBoolean(v)));
        settingUpdaters.put("enablebedrockminer", (k, v) -> setEnableBedrockMiner(parseBoolean(v)));
        settingUpdaters.put("fastpistonbreaking", (k, v) -> setFastPistonBreaking(parseBoolean(v), true));
        settingUpdaters.put("hidearrowsonplayers", (k, v) -> setHideArrowsOnPlayers(parseBoolean(v)));
        settingUpdaters.put("enablechattranslation", (k, v) -> setEnableChatTranslation(parseBoolean(v)));
        settingUpdaters.put("enablesigntranslation", (k, v) -> setEnableSignTranslation(parseBoolean(v)));
        settingUpdaters.put("enableitemtranslation", (k, v) -> setEnableItemTranslation(parseBoolean(v)));
        settingUpdaters.put("enableautomatictranslation", (k, v) -> setEnableAutomaticTranslation(parseBoolean(v)));
        settingUpdaters.put("autoshowcachedtranslation", (k, v) -> setAutoShowCachedTranslation(parseBoolean(v)));
        settingUpdaters.put("showtranslationonly", (k, v) -> setShowTranslationOnly(parseBoolean(v)));
        settingUpdaters.put("hidetranslationkeybindtooltip", (k, v) -> setHideTranslationKeybindTooltip(parseBoolean(v)));
        settingUpdaters.put("niutransapikey", (k, v) -> setNiuTransApiKey(v));
        settingUpdaters.put("translationsourcelang", (k, v) -> setTranslationSourceLang(v));
        settingUpdaters.put("translationtargetlang", (k, v) -> setTranslationTargetLang(v));
        settingUpdaters.put("translationprovider", (k, v) -> setTranslationProvider(v));
        settingUpdaters.put("showproviderswitchbuttons", (k, v) -> setShowProviderSwitchButtons(parseBoolean(v)));
        settingUpdaters.put("enablenotefeature", (k, v) -> setEnableNoteFeature(parseBoolean(v)));
        settingUpdaters.put("enableadvancedediting", (k, v) -> setEnableAdvancedEditing(parseBoolean(v)));
        settingUpdaters.put("enablemarkdownrendering", (k, v) -> setEnableMarkdownRendering(parseBoolean(v)));
        settingUpdaters.put("enablecolorrendering", (k, v) -> setEnableColorRendering(parseBoolean(v)));
        settingUpdaters.put("enableampersandcolorcodes", (k, v) -> setEnableAmpersandColorCodes(parseBoolean(v)));
        settingUpdaters.put("fixguistatelossonresize", (k, v) -> setFixGuiStateLossOnResize(parseBoolean(v)));
        settingUpdaters.put("autominerotationspeed", (k, v) -> setAutoMineRotationSpeed(parseDouble(v)));
        settingUpdaters.put("automaxreachdistance", (k, v) -> setAutoMineMaxReachDistance(parseDouble(v)));
        settingUpdaters.put("autominerearchradius", (k, v) -> setAutoMineSearchRadius(parseInt(v)));
        settingUpdaters.put("automineserverrotation", (k, v) -> setAutoMineServerRotation(parseBoolean(v)));
        settingUpdaters.put("automineinstantrotation", (k, v) -> setAutoMineInstantRotation(parseBoolean(v)));
        settingUpdaters.put("autominesneak", (k, v) -> setAutoMineSneak(parseBoolean(v)));
        settingUpdaters.put("automineenablerandommove", (k, v) -> setAutoMineEnableRandomMove(parseBoolean(v)));
        settingUpdaters.put("autominetimeout", (k, v) -> setAutoMineTimeout(parseInt(v)));
        settingUpdaters.put("automineenablerandomspeed", (k, v) -> setAutoMineEnableRandomSpeed(parseBoolean(v)));
        settingUpdaters.put("autominespeedvariability", (k, v) -> setAutoMineSpeedVariability(parseDouble(v)));
        settingUpdaters.put("autominepreventdiggingdown", (k, v) -> setAutoMinePreventDiggingDown(parseBoolean(v)));
        settingUpdaters.put("autominerandommoveinterval", (k, v) -> setAutoMineRandomMoveInterval(parseInt(v)));
        settingUpdaters.put("autominerandommoveduration", (k, v) -> setAutoMineRandomMoveDuration(parseInt(v)));
        settingUpdaters.put("autominerandommoveintervalvariability", (k, v) -> setAutoMineRandomMoveIntervalVariability(parseInt(v)));
        settingUpdaters.put("automineenableveinmining", (k, v) -> setAutoMineEnableVeinMining(parseBoolean(v)));
        settingUpdaters.put("automineminingmode", (k, v) -> setAutoMineMiningMode(v));
        settingUpdaters.put("automineanticheatcheck", (k, v) -> setAutoMineAntiCheatCheck(parseBoolean(v)));
        settingUpdaters.put("autominevoidsafetycheck", (k, v) -> setAutoMineEnableVoidSafetyCheck(parseBoolean(v)));
        settingUpdaters.put("autominevoidsafetylimit", (k, v) -> setAutoMineVoidSafetyYLimit(parseInt(v)));
        settingUpdaters.put("autominestopontimeout", (k, v) -> setAutoMineStopOnTimeout(parseBoolean(v)));
        settingUpdaters.put("autominemithriloptimization", (k, v) -> setAutoMineEnableMithrilOptimization(parseBoolean(v)));
        settingUpdaters.put("autominemithrilcleanupthreshold", (k, v) -> setAutoMineMithrilCleanupThreshold(parseInt(v)));
        settingUpdaters.put("automineenabletoolswitching", (k,v) -> setAutoMineEnableAutomaticToolSwitching(parseBoolean(v)));
        settingUpdaters.put("autocraftplacementdelay", (k, v) -> setAutoCraftPlacementDelayTicks(parseInt(v)));
        settingUpdaters.put("autocraftcycledelay", (k, v) -> setAutoCraftCycleDelayTicks(parseInt(v)));
        settingUpdaters.put("autocraftmenuopendelay", (k, v) -> setAutoCraftMenuOpenDelayTicks(parseInt(v)));
        settingUpdaters.put("autocrafttableopendelay", (k, v) -> setAutoCraftTableOpenDelayTicks(parseInt(v)));
        settingUpdaters.put("autocraftpickupstashwait", (k, v) -> setAutoCraftPickupStashWaitTicks(parseInt(v)));
        settingUpdaters.put("enabledungeonprofit", (k, v) -> setEnableDungeonProfit(parseBoolean(v)));
        settingUpdaters.put("enablebuildguess", (k, v) -> setEnableBuildGuess(parseBoolean(v)));
    }
    
    // --- 解析辅助方法 ---
    private static boolean parseBoolean(String input) {
        try { return CommandBase.parseBoolean(input); }
        catch (CommandException e) { throw new RuntimeException(e); }
    }
    private static int parseInt(String input) {
        try { return CommandBase.parseInt(input); }
        catch (CommandException e) { throw new RuntimeException(e); }
    }
    private static double parseDouble(String input) {
        try { return CommandBase.parseDouble(input); }
        catch (CommandException e) { throw new RuntimeException(e); }
    }
}