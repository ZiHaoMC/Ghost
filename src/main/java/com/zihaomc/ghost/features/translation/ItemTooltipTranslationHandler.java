package com.zihaomc.ghost.features.translation;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.handlers.KeybindHandler;
import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.utils.ColorFormatting;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ItemTooltipTranslationHandler {

    public static Map<String, List<String>> translationCache;
    public static final Set<String> pendingTranslations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static Set<String> hiddenTranslations;

    /** 存储当前悬停物品的原始带格式名称 */
    public static String lastHoveredItemOriginalName = null;
    /** 存储当前悬停物品的原始带格式 Lore */
    public static List<String> lastHoveredItemOriginalLore = null;

    /** 存储当前悬停物品去除了格式的纯文本名称 */
    public static String lastHoveredItemName = null;
    /** 存储当前悬停物品去除了格式的纯文本 Lore */
    public static List<String> lastHoveredItemLore = null;

    /** 用于剥离颜色代码的正则表达式 */
    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

    /**
     * 从文件加载翻译缓存和隐藏列表。
     */
    public static void loadCacheFromFile() {
        translationCache = TranslationCacheManager.loadCache();
        hiddenTranslations = TranslationCacheManager.loadHiddenItems();
        LogUtil.info("log.info.cache.loaded", translationCache.size());
        LogUtil.info("log.info.hidden.loaded", hiddenTranslations.size());
    }

    /**
     * 将翻译缓存和隐藏列表保存到文件。
     */
    public static void saveCacheToFile() {
        LogUtil.info("log.info.cache.saving.count", translationCache.size());
        TranslationCacheManager.saveCache(translationCache);
        TranslationCacheManager.saveHiddenItems(hiddenTranslations);
        LogUtil.info("log.info.cache.saved");
    }

    /**
     * 核心事件：处理物品 Tooltip 的显示。
     */
    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if ((!GhostConfig.Translation.enableItemTranslation && !GhostConfig.Translation.enableAutomaticTranslation) || event.itemStack == null || event.toolTip.isEmpty()) {
            resetHoveredItem();
            return;
        }

        // 步骤 1: 存储原始的、带格式的文本
        lastHoveredItemOriginalName = event.toolTip.get(0);
        
        // 步骤 2: 存储去除格式后的纯文本，用作缓存的 Key 和翻译请求的内容
        String unformattedItemName = STRIP_COLOR_PATTERN.matcher(lastHoveredItemOriginalName).replaceAll("");
        if (unformattedItemName.trim().isEmpty()) {
            resetHoveredItem();
            return;
        }
        lastHoveredItemName = unformattedItemName;
        
        // 同样处理 Lore 部分
        if (event.toolTip.size() > 1) {
            lastHoveredItemOriginalLore = new ArrayList<>(event.toolTip.subList(1, event.toolTip.size()));
            lastHoveredItemLore = lastHoveredItemOriginalLore.stream()
                .map(line -> STRIP_COLOR_PATTERN.matcher(line).replaceAll(""))
                .collect(Collectors.toList());
        } else {
            lastHoveredItemOriginalLore = new ArrayList<>();
            lastHoveredItemLore = new ArrayList<>();
        }

        String keyName = Keyboard.getKeyName(KeybindHandler.translateItemKey.getKeyCode());

        // 如果物品正在翻译中，显示提示
        if (pendingTranslations.contains(unformattedItemName)) {
            event.toolTip.add(EnumChatFormatting.GRAY + LangUtil.translate("ghost.tooltip.translating"));
            return;
        }
        
        // 如果缓存中没有此物品
        if (!translationCache.containsKey(unformattedItemName)) {
            if (GhostConfig.Translation.enableAutomaticTranslation) {
                // 自动翻译模式下，触发翻译流程
                new KeybindHandler().handleToggleOrTranslatePress();
                event.toolTip.add(EnumChatFormatting.GRAY + LangUtil.translate("ghost.tooltip.translating"));
            } else if (!GhostConfig.Translation.hideTranslationKeybindTooltip) {
                // 手动模式下，显示翻译提示
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.translate", keyName));
            }
            return;
        }

        // 如果缓存中存在此物品，获取翻译结果
        List<String> cachedLines = translationCache.get(unformattedItemName);
        // 检查是否是错误信息
        if (cachedLines != null && !cachedLines.isEmpty() && cachedLines.get(0).startsWith(EnumChatFormatting.RED.toString())) {
            event.toolTip.add("");
            event.toolTip.add(cachedLines.get(0));
            if (!GhostConfig.Translation.hideTranslationKeybindTooltip) {
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.retryAndClear", keyName, keyName, keyName));
            }
            return;
        }

        // 决定是否应该显示翻译
        boolean isHidden = hiddenTranslations.contains(unformattedItemName);
        boolean shouldBeVisible = GhostConfig.Translation.autoShowCachedTranslation ? !isHidden : isHidden;

        if (shouldBeVisible) {
            if (GhostConfig.Translation.showTranslationOnly) {
                // 仅显示翻译模式
                event.toolTip.clear();
                if (cachedLines != null && !cachedLines.isEmpty()) {
                    // 直接添加已经格式化好的翻译文本
                    event.toolTip.addAll(cachedLines);
                }
                event.toolTip.add("");
                if (!GhostConfig.Translation.hideTranslationKeybindTooltip) {
                    event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.hideAndClear", keyName, keyName, keyName));
                }
            } else {
                // 在原文下方附加翻译
                displayTranslation(event, cachedLines, keyName);
            }
        } else {
            // 如果翻译被隐藏，显示“显示翻译”的提示
            if (!GhostConfig.Translation.hideTranslationKeybindTooltip) {
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.showAndClear", keyName, keyName, keyName));
            }
        }
    }

    /**
     * 在 Tooltip 的末尾附加翻译内容。
     */
    private void displayTranslation(ItemTooltipEvent event, List<String> translatedLines, String keyName) {
        event.toolTip.add("");
        event.toolTip.add(EnumChatFormatting.GOLD + LangUtil.translate("ghost.tooltip.header"));
        if (translatedLines != null) {
            // 直接添加已经格式化好的翻译文本
            event.toolTip.addAll(translatedLines);
        }
        if (!GhostConfig.Translation.hideTranslationKeybindTooltip) {
            event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.hideAndClear", keyName, keyName, keyName));
        }
    }
    
    /**
     * 当 GUI 关闭时，重置悬停物品信息。
     */
    @SubscribeEvent
    public void onGuiClosed(GuiOpenEvent event) {
        if (event.gui == null) {
            resetHoveredItem();
        }
    }

    /**
     * 清理所有与当前悬停物品相关的信息。
     */
    private void resetHoveredItem() {
        lastHoveredItemName = null;
        lastHoveredItemLore = null;
        lastHoveredItemOriginalName = null;
        lastHoveredItemOriginalLore = null;
    }
}