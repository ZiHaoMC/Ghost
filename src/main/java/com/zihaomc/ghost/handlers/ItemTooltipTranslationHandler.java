package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.data.TranslationCacheManager;
import com.zihaomc.ghost.LangUtil;
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

/**
 * 处理物品提示框（Tooltip）的翻译功能。
 * 1. 记录当前鼠标悬停的物品名称和描述。
 * 2. 在提示框中显示已缓存的完整翻译结果或“翻译中”的状态。
 * 3. 实际的翻译请求由 KeybindHandler 中的快捷键触发。
 */
public class ItemTooltipTranslationHandler {

    public static Map<String, List<String>> translationCache;
    public static final Set<String> pendingTranslations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    public static final Set<String> temporarilyHiddenItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    public static String lastHoveredItemName = null;
    public static List<String> lastHoveredItemLore = null;

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");
    
    public static void loadCacheFromFile() {
        translationCache = TranslationCacheManager.loadCache();
        System.out.println("[Ghost-Cache] 已加载 " + translationCache.size() + " 条翻译缓存。");
    }

    public static void saveCacheToFile() {
        System.out.println("[Ghost-Cache] 正在保存 " + translationCache.size() + " 条翻译缓存...");
        TranslationCacheManager.saveCache(translationCache);
        System.out.println("[Ghost-Cache] 翻译缓存已保存。");
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!GhostConfig.enableItemTranslation || event.itemStack == null || event.toolTip.isEmpty()) {
            resetHoveredItem();
            return;
        }

        String unformattedItemName = STRIP_COLOR_PATTERN.matcher(event.toolTip.get(0)).replaceAll("");
        if (unformattedItemName.trim().isEmpty()) {
            resetHoveredItem();
            return;
        }
        lastHoveredItemName = unformattedItemName;
        
        if (event.toolTip.size() > 1) {
            lastHoveredItemLore = event.toolTip.subList(1, event.toolTip.size()).stream()
                .map(line -> STRIP_COLOR_PATTERN.matcher(line).replaceAll(""))
                .collect(Collectors.toList());
        } else {
            lastHoveredItemLore = new ArrayList<>();
        }

        String keyName = Keyboard.getKeyName(KeybindHandler.translateItemKey.getKeyCode());

        if (pendingTranslations.contains(unformattedItemName)) {
            event.toolTip.add(EnumChatFormatting.GRAY + LangUtil.translate("ghost.tooltip.translating"));
            return;
        }
        
        if (!translationCache.containsKey(unformattedItemName)) {
            event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.translate", keyName));
            return;
        }

        List<String> cachedLines = translationCache.get(unformattedItemName);
        if (cachedLines != null && !cachedLines.isEmpty() && cachedLines.get(0).startsWith(EnumChatFormatting.RED.toString())) {
            event.toolTip.add("");
            event.toolTip.add(cachedLines.get(0));
            event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.retryAndClear", keyName, keyName, keyName));
            return;
        }

        boolean isHidden = temporarilyHiddenItems.contains(unformattedItemName);
        boolean shouldBeVisible = GhostConfig.autoShowCachedTranslation ? !isHidden : isHidden;

        if (shouldBeVisible) {
            if (GhostConfig.showTranslationOnly) {
                event.toolTip.clear();

                if (cachedLines != null && !cachedLines.isEmpty()) {
                    event.toolTip.add(event.itemStack.getRarity().rarityColor + cachedLines.get(0));

                    if (cachedLines.size() > 1) {
                        for (int i = 1; i < cachedLines.size(); i++) {
                            event.toolTip.add(EnumChatFormatting.AQUA + cachedLines.get(i));
                        }
                    }
                }
                event.toolTip.add("");
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.hideAndClear", keyName, keyName, keyName));
            } else {
                displayTranslation(event, cachedLines, keyName);
            }
        } else {
            event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.showAndClear", keyName, keyName, keyName));
        }
    }

    private void displayTranslation(ItemTooltipEvent event, List<String> translatedLines, String keyName) {
        event.toolTip.add("");
        event.toolTip.add(EnumChatFormatting.GOLD + LangUtil.translate("ghost.tooltip.header"));
        if (translatedLines != null) {
            for (String line : translatedLines) {
                event.toolTip.add(EnumChatFormatting.AQUA + line);
            }
        }
        event.toolTip.add(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.tooltip.hideAndClear", keyName, keyName, keyName));
    }
    
    @SubscribeEvent
    public void onGuiClosed(GuiOpenEvent event) {
        if (event.gui == null) {
            resetHoveredItem();
            temporarilyHiddenItems.clear();
        }
    }

    private void resetHoveredItem() {
        lastHoveredItemName = null;
        lastHoveredItemLore = null;
    }
}