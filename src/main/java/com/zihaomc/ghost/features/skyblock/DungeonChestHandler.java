package com.zihaomc.ghost.features.skyblock;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

public class DungeonChestHandler {

    private static final Pattern BOOK_PATTERN = Pattern.compile("Enchanted Book \\((.+) ([IVXLCDM]+)\\)");
    private static final Pattern ITEM_PATTERN = Pattern.compile("^(.+?)(?: x(\\d+))?$");
    private static final Pattern COINS_PATTERN = Pattern.compile("([0-9,]+) Coins");

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (event.itemStack == null || event.toolTip.isEmpty()) return;

        double totalValue = 0;
        long openCost = 0;
        boolean readingContents = false;

        for (String line : event.toolTip) {
            String clean = EnumChatFormatting.getTextWithoutFormattingCodes(line).trim();
            if (clean.isEmpty()) continue;

            // 1. 定位区域
            if (clean.equals("Contents")) {
                readingContents = true;
                continue;
            }
            // 看到 Cost 或 NOTE 或 Requires，停止读取内容
            if (clean.equals("Cost") || clean.startsWith("NOTE:") || clean.startsWith("Requires")) {
                readingContents = false;
            }

            // 2. 解析奖励内容
            if (readingContents) {
                // 优先尝试匹配附魔书
                Matcher bookM = BOOK_PATTERN.matcher(clean);
                if (bookM.find()) {
                    String enchantName = bookM.group(1).toUpperCase().replace(" ", "_");
                    int level = romanToInt(bookM.group(2));
                    // 修正：某些附魔在API里的ID前缀
                    String apiId = "ENCHANTMENT_" + enchantName + "_" + level;
                    totalValue += SkyblockPriceManager.getPrice(apiId);
                    continue;
                }

                // 匹配通用物品（装备、Essence、卷轴等）
                Matcher itemM = ITEM_PATTERN.matcher(clean);
                if (itemM.find()) {
                    String itemName = itemM.group(1).trim();
                    int amount = itemM.group(2) != null ? Integer.parseInt(itemM.group(2)) : 1;
                    totalValue += getItemPrice(itemName) * amount;
                }
            }

            // 3. 解析成本 (Cost)
            Matcher coinsM = COINS_PATTERN.matcher(clean);
            if (coinsM.find()) {
                openCost = Long.parseLong(coinsM.group(1).replace(",", ""));
            }
        }

        if (totalValue > 0 || openCost > 0) {
            renderTooltip(event, totalValue, openCost);
        }
    }

    /**
     * 智能获取价格：清洗名字并转换 ID
     */
    private double getItemPrice(String name) {
        // --- 步骤 1: 清洗名字 ---
        // 删掉星级 ✪
        String cleanName = name.replace("✪", "").trim();
        // 删掉常见的状态前缀（这些前缀不属于 API ID）
        cleanName = cleanName.replace("Recombobulated ", "");
        cleanName = cleanName.replace("Tier Boosted ", "");
        cleanName = cleanName.replace("Awakened ", "");

        // --- 步骤 2: 转换 ID ---
        String id = cleanName.toUpperCase().replace(" ", "_");
        
        // --- 步骤 3: 特殊映射 (针对名字和ID完全不符的物品) ---
        if (id.contains("WITHER_ESSENCE")) return SkyblockPriceManager.getPrice("ESSENCE_WITHER");
        if (id.contains("UNDEAD_ESSENCE")) return SkyblockPriceManager.getPrice("ESSENCE_UNDEAD");
        if (id.contains("DRAGON_ESSENCE")) return SkyblockPriceManager.getPrice("ESSENCE_DRAGON");
        if (id.contains("RECOMBOBULATOR")) return SkyblockPriceManager.getPrice("RECOMBOBULATOR_3000");
        if (id.contains("FUMING_POTATO")) return SkyblockPriceManager.getPrice("FUMING_POTATO_BOOK");
        
        // 如果是 Necron's Handle 这种带撇号的，也要处理
        id = id.replace("'", "");

        // --- 步骤 4: 查询 ---
        double price = SkyblockPriceManager.getPrice(id);

        // 如果查不到，尝试在末尾补全 (部分装备在API里可能带特殊后缀，但通常LBIN只需ID)
        return price;
    }

    private void renderTooltip(ItemTooltipEvent event, double value, long cost) {
        double profit = value - cost;
        event.toolTip.add("");
        event.toolTip.add("§6§lGhost Dungeon Helper");
        event.toolTip.add("§7Contents Value: §e" + format(value));
        event.toolTip.add("§7Open Cost: §c-" + format(cost));
        
        EnumChatFormatting color = profit >= 0 ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
        String sign = profit >= 0 ? "+" : "";
        event.toolTip.add("§fTotal Profit: " + color + EnumChatFormatting.BOLD + sign + format(profit));
    }

    private String format(double n) {
        if (n >= 1000000) return String.format("%.2fM", n / 1000000.0);
        if (n >= 1000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.0f", n);
    }

    private int romanToInt(String s) {
        Map<Character, Integer> map = new HashMap<>();
        map.put('I', 1); map.put('V', 5); map.put('X', 10);
        map.put('L', 50); map.put('C', 100);
        int res = 0;
        for (int i = 0; i < s.length(); i++) {
            int val = map.get(s.charAt(i));
            if (i < s.length() - 1 && val < map.get(s.charAt(i + 1))) res -= val;
            else res += val;
        }
        return res;
    }
}