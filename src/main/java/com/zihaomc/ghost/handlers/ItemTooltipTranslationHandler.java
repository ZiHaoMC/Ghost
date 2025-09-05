package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
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

    // 缓存的键是物品原始名称，值是翻译后的完整行列表
    public static final Map<String, List<String>> translationCache = new ConcurrentHashMap<>();
    public static final Set<String> pendingTranslations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // 用于记录当前悬停的物品的名称和描述
    public static String lastHoveredItemName = null;
    public static List<String> lastHoveredItemLore = null;

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!GhostConfig.enableItemTranslation || event.itemStack == null || event.toolTip.isEmpty()) {
            resetHoveredItem();
            return;
        }

        // 1. 更新当前悬停的物品信息
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

        // v-- 这里是修改的核心 --v

        // 2. 检查缓存并显示结果
        if (translationCache.containsKey(unformattedItemName)) {
            List<String> cachedLines = translationCache.get(unformattedItemName);
            
            // 检查缓存的是否是失败记录
            if (cachedLines != null && !cachedLines.isEmpty() && cachedLines.get(0).startsWith("§c")) {
                // 是失败记录：显示错误信息 和 重试提示
                event.toolTip.add("");
                event.toolTip.add(cachedLines.get(0)); // 显示错误
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + "按 " + keyName + " 键重试");
            } else {
                // 是成功记录：显示翻译结果
                event.toolTip.add("");
                event.toolTip.add(EnumChatFormatting.GOLD + "--- 翻译 ---");
                if (cachedLines != null) {
                    for (String line : cachedLines) {
                        event.toolTip.add(EnumChatFormatting.AQUA + line);
                    }
                }
            }
            return;
        }

        // 3. 检查是否正在翻译中
        if (pendingTranslations.contains(unformattedItemName)) {
            event.toolTip.add(EnumChatFormatting.GRAY + "翻译中...");
        } else {
            // 4. 显示初次翻译的操作提示
            event.toolTip.add(EnumChatFormatting.DARK_GRAY + "按 " + keyName + " 键翻译");
        }
        // ^-- 修改结束 --^
    }
    
    /**
     * 当GUI关闭时，重置所有悬停信息。
     */
    @SubscribeEvent
    public void onGuiClosed(GuiOpenEvent event) {
        if (event.gui == null) {
            resetHoveredItem();
        }
    }

    /**
     * 辅助方法，用于清空当前悬停的物品信息。
     */
    private void resetHoveredItem() {
        lastHoveredItemName = null;
        lastHoveredItemLore = null;
    }
}