package com.zihaomc.ghost.config;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
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
    public static final String CATEGORY_TRANSLATION = "translation_api";
    public static final String CATEGORY_NOTE = "note_taking";
    public static final String CATEGORY_GUI_TWEAKS = "gui_tweaks";

    // --- 功能开关 ---
    public static boolean alwaysBatchFill = false;
    public static boolean enableAutoSave = false;
    public static boolean enableChatSuggestions = true;
    public static boolean enableCommandHistoryScroll = true;
    public static boolean enableChatTranslation = false;
    public static boolean enableSignTranslation = false;
    public static boolean enableItemTranslation = false;
    public static boolean enableAutomaticTranslation = false;
    public static boolean autoShowCachedTranslation = true;
    public static boolean showTranslationOnly = false;
    public static boolean hideTranslationKeybindTooltip = false; 
    public static boolean enableAutoPlaceOnJoin = false;
    public static boolean enableAutoSneakAtEdge = false;
    public static boolean enablePlayerESP = false;
    public static boolean enableBedrockMiner = false;
    public static boolean fastPistonBreaking = false;
    public static boolean hideArrowsOnPlayers = false;
    public static boolean enableNoteFeature = true;
    public static boolean enableAdvancedEditing = true;
    public static boolean enableMarkdownRendering = true;
    public static boolean fixGuiStateLossOnResize = true;

    // --- 功能数值 ---
    public static int forcedBatchSize = 100;
    public static String defaultSaveFileName = "";
    public static double autoSneakForwardOffset = 0.10;
    public static double autoSneakVerticalCheckDepth = 2.0;

    // --- 破基岩配置 ---
    public static int pingSpikeThreshold = 2;
    public static boolean headlessPistonMode = true;
    public static boolean blinkDuringTasksTick = true;
    private static Set<Block> blockWhitelist = new HashSet<>();
    private static Set<Block> dependBlockWhitelist = new HashSet<>();
    
    // --- 在线翻译配置 ---
    public static String niuTransApiKey = "";
    public static String translationSourceLang = "auto";
    public static String translationTargetLang = "zh";

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

        // --- 填充命令 ---
        String alwaysBatchComment = LangUtil.translate("ghostblock.config.alwaysBatchFill.tooltip");
        alwaysBatchFill = config.getBoolean("alwaysBatchFill", CATEGORY_FILL, false, alwaysBatchComment);

        String forcedSizeComment = LangUtil.translate("ghostblock.config.forcedBatchSize.tooltip");
        forcedBatchSize = config.getInt("forcedBatchSize", CATEGORY_FILL, 100, 1, Integer.MAX_VALUE, forcedSizeComment);

        // --- 保存选项 ---
        String enableAutoSaveComment = LangUtil.translate("ghostblock.config.enableAutoSave.tooltip");
        enableAutoSave = config.getBoolean("enableAutoSave", CATEGORY_SAVE, false, enableAutoSaveComment);

        String defaultFileNameComment = LangUtil.translate("ghostblock.config.defaultSaveFileName.tooltip");
        defaultSaveFileName = config.getString("defaultSaveFileName", CATEGORY_SAVE, "", defaultFileNameComment);

        // --- 聊天功能 ---
        String enableChatSuggestComment = LangUtil.translate("ghostblock.config.enableChatSuggestions.tooltip");
        enableChatSuggestions = config.getBoolean("enableChatSuggestions", CATEGORY_CHAT, true, enableChatSuggestComment);

        String enableCmdHistoryScrollComment = LangUtil.translate("ghostblock.config.enableCommandHistoryScroll.tooltip");
        enableCommandHistoryScroll = config.getBoolean("enableCommandHistoryScroll", CATEGORY_CHAT, true, enableCmdHistoryScrollComment);
        
        // --- 自动放置 ---
        String enableAutoPlaceComment = LangUtil.translate("ghostblock.config.enableAutoPlaceOnJoin.tooltip");
        enableAutoPlaceOnJoin = config.getBoolean("enableAutoPlaceOnJoin", CATEGORY_AUTO_PLACE, false, enableAutoPlaceComment);
        
        // --- 自动蹲伏 ---
        String enableAutoSneakComment = LangUtil.translate("ghostblock.config.enableAutoSneakAtEdge.tooltip");
        enableAutoSneakAtEdge = config.getBoolean("enableAutoSneakAtEdge", CATEGORY_AUTO_SNEAK, false, enableAutoSneakComment);

        Property propForwardOffset = config.get(CATEGORY_AUTO_SNEAK, "autoSneakForwardOffset", 0.15, LangUtil.translate("ghostblock.config.autoSneakForwardOffset.tooltip"), 0.05, 1.0);
        autoSneakForwardOffset = propForwardOffset.getDouble();

        Property propVerticalDepth = config.get(CATEGORY_AUTO_SNEAK, "autoSneakVerticalCheckDepth", 1.0, LangUtil.translate("ghostblock.config.autoSneakVerticalCheckDepth.tooltip"), 0.1, 3.0);
        autoSneakVerticalCheckDepth = propVerticalDepth.getDouble();

        // --- 玩家透视 ---
        String enablePlayerESPComment = LangUtil.translate("ghostblock.config.enablePlayerESP.tooltip");
        enablePlayerESP = config.getBoolean("enablePlayerESP", CATEGORY_PLAYER_ESP, false, enablePlayerESPComment);

        // --- 游戏玩法调整 ---
        String fastPistonBreakingComment = LangUtil.translate("ghostblock.config.fastPistonBreaking.tooltip");
        fastPistonBreaking = config.getBoolean("fastPistonBreaking", CATEGORY_GAMEPLAY_TWEAKS, false, fastPistonBreakingComment);

        String hideArrowsComment = LangUtil.translate("ghost.config.comment.hideArrowsOnPlayers");
        hideArrowsOnPlayers = config.getBoolean("hideArrowsOnPlayers", CATEGORY_GAMEPLAY_TWEAKS, false, hideArrowsComment);

        // --- 笔记功能 ---
        String enableNoteComment = LangUtil.translate("ghost.config.comment.enableNoteFeature");
        enableNoteFeature = config.getBoolean("enableNoteFeature", CATEGORY_NOTE, true, enableNoteComment);
        
        String enableAdvancedEditingComment = LangUtil.translate("ghost.config.comment.enableAdvancedEditing");
        enableAdvancedEditing = config.getBoolean("enableAdvancedEditing", CATEGORY_NOTE, true, enableAdvancedEditingComment);
        
        String enableMarkdownRenderingComment = LangUtil.translate("ghost.config.comment.enableMarkdownRendering");
        enableMarkdownRendering = config.getBoolean("enableMarkdownRendering", CATEGORY_NOTE, true, enableMarkdownRenderingComment);

        // --- GUI 调整 ---
        String fixGuiStateLossComment = LangUtil.translate("ghost.config.comment.fixGuiStateLossOnResize");
        fixGuiStateLossOnResize = config.getBoolean("fixGuiStateLossOnResize", CATEGORY_GUI_TWEAKS, true, fixGuiStateLossComment);

        // --- 破基岩 ---
        String enableBedrockMinerComment = LangUtil.translate("ghostblock.config.enableBedrockMiner.tooltip");
        enableBedrockMiner = config.getBoolean("enableBedrockMiner", CATEGORY_BEDROCK_MINER, false, enableBedrockMinerComment);
        
        String pingSpikeComment = LangUtil.translate("ghost.config.comment.pingSpike");
        pingSpikeThreshold = config.getInt("pingSpikeThreshold", CATEGORY_BEDROCK_MINER, 2, 0, 100, pingSpikeComment);

        String headlessComment = LangUtil.translate("ghost.config.comment.headlessPiston");
        headlessPistonMode = config.getBoolean("headlessPistonMode", CATEGORY_BEDROCK_MINER, true, headlessComment);

        String blinkComment = LangUtil.translate("ghost.config.comment.blink");
        blinkDuringTasksTick = config.getBoolean("blinkDuringTasksTick", CATEGORY_BEDROCK_MINER, true, blinkComment);

        String whitelistComment = LangUtil.translate("ghost.config.comment.blockWhitelist");
        String[] whitelistArr = config.getStringList("blockWhitelist", CATEGORY_BEDROCK_MINER, new String[]{"minecraft:bedrock"}, whitelistComment);
        blockWhitelist = Arrays.stream(whitelistArr).map(s -> Block.blockRegistry.getObject(new ResourceLocation(s))).filter(Objects::nonNull).collect(Collectors.toSet());

        String dependComment = LangUtil.translate("ghost.config.comment.dependWhitelist");
        String[] dependArr = config.getStringList("dependBlockWhitelist", CATEGORY_BEDROCK_MINER, new String[]{"minecraft:slime_block"}, dependComment);
        dependBlockWhitelist = Arrays.stream(dependArr).map(s -> Block.blockRegistry.getObject(new ResourceLocation(s))).filter(Objects::nonNull).collect(Collectors.toSet());

        // --- 在线翻译 ---
        String enableChatTransComment = LangUtil.translate("ghost.config.comment.enableChatTranslation");
        enableChatTranslation = config.getBoolean("enableChatTranslation", CATEGORY_TRANSLATION, false, enableChatTransComment);

        String enableSignTransComment = LangUtil.translate("ghost.config.comment.enableSignTranslation");
        enableSignTranslation = config.getBoolean("enableSignTranslation", CATEGORY_TRANSLATION, false, enableSignTransComment);

        String enableItemTransComment = LangUtil.translate("ghost.config.comment.enableItemTranslation");
        enableItemTranslation = config.getBoolean("enableItemTranslation", CATEGORY_TRANSLATION, false, enableItemTransComment);
        
        String enableAutoTransComment = LangUtil.translate("ghost.config.comment.enableAutomaticTranslation");
        enableAutomaticTranslation = config.getBoolean("enableAutomaticTranslation", CATEGORY_TRANSLATION, false, enableAutoTransComment);

        String autoShowComment = LangUtil.translate("ghost.config.comment.autoShowCachedTranslation");
        autoShowCachedTranslation = config.getBoolean("autoShowCachedTranslation", CATEGORY_TRANSLATION, true, autoShowComment);

        String showOnlyComment = LangUtil.translate("ghost.config.comment.showTranslationOnly");
        showTranslationOnly = config.getBoolean("showTranslationOnly", CATEGORY_TRANSLATION, false, showOnlyComment);

        String hideKeybindComment = LangUtil.translate("ghost.config.comment.hideTranslationKeybindTooltip");
        hideTranslationKeybindTooltip = config.getBoolean("hideTranslationKeybindTooltip", CATEGORY_TRANSLATION, false, hideKeybindComment);

        String apiKeyComment = LangUtil.translate("ghostblock.config.niuTransApiKey.tooltip");
        niuTransApiKey = config.getString("niuTransApiKey", CATEGORY_TRANSLATION, "", apiKeyComment);

        String sourceLangComment = LangUtil.translate("ghostblock.config.translationSourceLang.tooltip");
        translationSourceLang = config.getString("translationSourceLang", CATEGORY_TRANSLATION, "auto", sourceLangComment);
        
        String targetLangComment = LangUtil.translate("ghostblock.config.translationTargetLang.tooltip");
        translationTargetLang = config.getString("translationTargetLang", CATEGORY_TRANSLATION, "zh", targetLangComment);

        if (config.hasChanged()) {
            config.save();
            LogUtil.info("log.config.loaded");
        }
    }

    // --- Getter 方法 ---
    public static int getPingSpikeThreshold() { return pingSpikeThreshold; }
    public static boolean isHeadlessPistonMode() { return headlessPistonMode; }
    public static Set<Block> getDependBlockWhitelist() { return dependBlockWhitelist; }
    public static boolean isBlinkDuringTasksTick() { return blinkDuringTasksTick; }
    public static Set<Block> getBlockWhitelist() { return blockWhitelist; }
    
    // --- Setter 方法 ---
    public static void setAlwaysBatchFill(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_FILL, "alwaysBatchFill", false); prop.set(value); alwaysBatchFill = value; config.save(); }
    public static boolean setForcedBatchSize(int value) { if (config == null || value <= 0) return false; Property prop = config.get(CATEGORY_FILL, "forcedBatchSize", 100); prop.set(value); forcedBatchSize = value; config.save(); return true; }
    public static void setEnableAutoSave(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_SAVE, "enableAutoSave", false); prop.set(value); enableAutoSave = value; config.save(); }
    public static boolean setDefaultSaveFileName(String value) { if (config == null) return false; String processedValue = (value != null) ? value.trim() : ""; Property prop = config.get(CATEGORY_SAVE, "defaultSaveFileName", ""); prop.set(processedValue); defaultSaveFileName = processedValue; config.save(); return true; }
    public static void setEnableChatSuggestions(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_CHAT, "enableChatSuggestions", true); prop.set(value); enableChatSuggestions = value; config.save(); }
    public static void setEnableCommandHistoryScroll(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_CHAT, "enableCommandHistoryScroll", true); prop.set(value); enableCommandHistoryScroll = value; config.save(); }
    public static void setEnableChatTranslation(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "enableChatTranslation", false); prop.set(value); enableChatTranslation = value; config.save(); }
    public static void setEnableSignTranslation(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "enableSignTranslation", false); prop.set(value); enableSignTranslation = value; config.save(); }
    public static void setEnableItemTranslation(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "enableItemTranslation", false); prop.set(value); enableItemTranslation = value; config.save(); }
    public static void setEnableAutomaticTranslation(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "enableAutomaticTranslation", false); prop.set(value); enableAutomaticTranslation = value; config.save(); }
    public static void setAutoShowCachedTranslation(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "autoShowCachedTranslation", true); prop.set(value); autoShowCachedTranslation = value; config.save(); }
    public static void setShowTranslationOnly(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "showTranslationOnly", false); prop.set(value); showTranslationOnly = value; config.save(); }
    public static void setHideTranslationKeybindTooltip(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "hideTranslationKeybindTooltip", false); prop.set(value); hideTranslationKeybindTooltip = value; config.save(); }
    public static void setEnableAutoPlaceOnJoin(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_AUTO_PLACE, "enableAutoPlaceOnJoin", false); prop.set(value); enableAutoPlaceOnJoin = value; config.save(); }
    public static void setEnableAutoSneakAtEdge(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_AUTO_SNEAK, "enableAutoSneakAtEdge", false); prop.set(value); enableAutoSneakAtEdge = value; config.save(); }
    public static void setAutoSneakForwardOffset(double value) { if (config == null || value < 0.05 || value > 1.0) return; Property prop = config.get(CATEGORY_AUTO_SNEAK, "autoSneakForwardOffset", 0.35); prop.set(value); autoSneakForwardOffset = value; config.save(); }
    public static void setAutoSneakVerticalCheckDepth(double value) { if (config == null || value < 0.1 || value > 3.0) return; Property prop = config.get(CATEGORY_AUTO_SNEAK, "autoSneakVerticalCheckDepth", 1.0); prop.set(value); autoSneakVerticalCheckDepth = value; config.save(); }
    public static void setEnablePlayerESP(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_PLAYER_ESP, "enablePlayerESP", false); prop.set(value); enablePlayerESP = value; config.save(); }
    public static void setEnableBedrockMiner(boolean value) { if (config == null) return; if (value) { setFastPistonBreaking(true, false); } Property prop = config.get(CATEGORY_BEDROCK_MINER, "enableBedrockMiner", false); prop.set(value); enableBedrockMiner = value; config.save(); }
    public static void setFastPistonBreaking(boolean value, boolean saveImmediately) { if (config == null) return; Property prop = config.get(CATEGORY_GAMEPLAY_TWEAKS, "fastPistonBreaking", false); prop.set(value); fastPistonBreaking = value; if (saveImmediately) config.save(); }
    public static void setHideArrowsOnPlayers(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_GAMEPLAY_TWEAKS, "hideArrowsOnPlayers", false); prop.set(value); hideArrowsOnPlayers = value; config.save(); }
    public static void setEnableNoteFeature(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_NOTE, "enableNoteFeature", true); prop.set(value); enableNoteFeature = value; config.save(); }
    public static void setNiuTransApiKey(String value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "niuTransApiKey", ""); prop.set(value); niuTransApiKey = value; config.save(); }
    public static void setTranslationSourceLang(String value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "translationSourceLang", "auto"); prop.set(value); translationSourceLang = value; config.save(); }
    public static void setTranslationTargetLang(String value) { if (config == null) return; Property prop = config.get(CATEGORY_TRANSLATION, "translationTargetLang", "zh"); prop.set(value); translationTargetLang = value; config.save(); }
    public static void setEnableAdvancedEditing(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_NOTE, "enableAdvancedEditing", true); prop.set(value); enableAdvancedEditing = value; config.save(); }
    public static void setEnableMarkdownRendering(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_NOTE, "enableMarkdownRendering", true); prop.set(value); enableMarkdownRendering = value; config.save(); }
    public static void setFixGuiStateLossOnResize(boolean value) { if (config == null) return; Property prop = config.get(CATEGORY_GUI_TWEAKS, "fixGuiStateLossOnResize", true); prop.set(value); fixGuiStateLossOnResize = value; config.save(); }

    public static Configuration getConfig() {
        return config;
    }
}