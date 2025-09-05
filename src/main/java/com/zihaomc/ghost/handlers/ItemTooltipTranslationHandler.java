package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.data.TranslationCacheManager;
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
    
    // 新增：用于跟踪哪些物品的翻译是可见的
    public static final Set<String> visiblyTranslatedItems = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    public static String lastHoveredItemName = null;
    public static List<String> lastHoveredItemLore = null;

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");
    
    /**
     * 从文件加载持久化缓存。应在 Mod 启动时调用。
     */
    public static void loadCacheFromFile() {
        translationCache = TranslationCacheManager.loadCache();
        System.out.println("[Ghost-Cache] 已加载 " + translationCache.size() + " 条翻译缓存。");
    }

    /**
     * 将当前内存中的缓存保存到文件。应在游戏关闭时调用。
     */
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

        // 2. 检查是否正在翻译中（最高优先级）
        if (pendingTranslations.contains(unformattedItemName)) {
            event.toolTip.add(EnumChatFormatting.GRAY + "翻译中...");
            return;
        }
        
        // 3. 检查翻译是否应该可见
        if (visiblyTranslatedItems.contains(unformattedItemName)) {
            List<String> cachedLines = translationCache.get(unformattedItemName);
            
            // 如果缓存中找到了记录
            if (cachedLines != null) {
                event.toolTip.add(""); // 添加分隔
                // 检查是成功还是失败记录
                if (!cachedLines.isEmpty() && cachedLines.get(0).startsWith("§c")) {
                    event.toolTip.add(cachedLines.get(0)); // 显示错误
                } else {
                    event.toolTip.add(EnumChatFormatting.GOLD + "--- 翻译 ---");
                    for (String line : cachedLines) {
                        event.toolTip.add(EnumChatFormatting.AQUA + line);
                    }
                }
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + "按 " + keyName + " 键隐藏");
            }
            // 如果在可见列表但缓存找不到（理论上不应发生），则不做任何事，让按键逻辑处理
            
        } else {
            // 4. 如果翻译不可见，根据缓存状态显示不同提示
            if (translationCache.containsKey(unformattedItemName)) {
                // 有缓存但被隐藏了
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + "按 " + keyName + " 键显示翻译");
            } else {
                // 从未翻译过
                event.toolTip.add(EnumChatFormatting.DARK_GRAY + "按 " + keyName + " 键翻译");
            }
        }
        // ^-- 修改结束 --^
    }
    
    /**
     * 当GUI关闭时，重置所有悬停和可见性信息。
     */
    @SubscribeEvent
    public void onGuiClosed(GuiOpenEvent event) {
        if (event.gui == null) {
            resetHoveredItem();
            // 关闭GUI时，重置所有物品的可见状态，下次打开时默认都是隐藏的
            visiblyTranslatedItems.clear();
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