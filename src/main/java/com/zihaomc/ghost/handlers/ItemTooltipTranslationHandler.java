package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard; // <-- 确保导入这个类

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 处理物品提示框（Tooltip）的翻译功能。
 * 1. 记录当前鼠标悬停的物品名称。
 * 2. 在提示框中显示已缓存的翻译结果或“翻译中”的状态。
 * 3. 实际的翻译请求由 KeybindHandler 中的快捷键触发。
 */
public class ItemTooltipTranslationHandler {

    public static final Map<String, String> translationCache = new ConcurrentHashMap<>();
    public static final Set<String> pendingTranslations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    public static String lastHoveredItemName = null;

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!GhostConfig.enableItemTranslation || event.itemStack == null || event.toolTip.isEmpty()) {
            lastHoveredItemName = null;
            return;
        }

        // 1. 更新当前悬停的物品名称
        String originalItemName = event.toolTip.get(0);
        String unformattedItemName = STRIP_COLOR_PATTERN.matcher(originalItemName).replaceAll("");

        if (unformattedItemName.trim().isEmpty()) {
            lastHoveredItemName = null;
            return;
        }
        lastHoveredItemName = unformattedItemName;

        // 2. 检查缓存并显示结果 (成功或失败)
        if (translationCache.containsKey(unformattedItemName)) {
            String cachedValue = translationCache.get(unformattedItemName);
            
            // 检查缓存的值是否是错误信息 (我们的工具类返回的错误以§c开头)
            if (cachedValue.startsWith("§c")) {
                // 如果是错误信息，直接添加（它已经自带红色了）
                event.toolTip.add(cachedValue);
            } else {
                // 否则，就是成功的翻译结果
                event.toolTip.add(EnumChatFormatting.AQUA + "[T] " + cachedValue);
            }
            return; // 显示完缓存后直接返回
        }

        // 3. 检查是否正在翻译中并显示状态
        if (pendingTranslations.contains(unformattedItemName)) {
            event.toolTip.add(EnumChatFormatting.GRAY + "翻译中...");
        } else {
            // 4. 如果既不在缓存中，也不在翻译中，则显示操作提示
            // v-- 这里是修正的地方 --v
            String keyName = Keyboard.getKeyName(KeybindHandler.translateItemKey.getKeyCode());
            event.toolTip.add(EnumChatFormatting.DARK_GRAY + "按 " + keyName + " 键翻译");
            // ^-- 这里是修正的地方 --^
        }
    }
    
    /**
     * 当GUI关闭时，重置当前悬停的物品名称。
     * 这可以防止在关闭背包后，按键仍然尝试翻译最后一个物品。
     */
    @SubscribeEvent
    public void onGuiClosed(GuiOpenEvent event) {
        if (event.gui == null) {
            lastHoveredItemName = null;
        }
    }
}