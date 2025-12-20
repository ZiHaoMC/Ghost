package com.zihaomc.ghost.features.skyblock;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.List;

public class DungeonChestHandler {

    private static final Pattern BOOK_PATTERN = Pattern.compile("Enchanted Book \\((.+) ([IVXLCDM]+)\\)");
    private static final Pattern ITEM_PATTERN = Pattern.compile("^(.+?)(?: x(\\d+))?$");
    private static final Pattern COINS_PATTERN = Pattern.compile("([0-9,]+) Coins");

    private static final List<String> CHEST_NAMES = Arrays.asList(
        "Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock", "Open Reward Chest"
    );

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!GhostConfig.Skyblock.enableDungeonProfit) {
            return;
        }

        ItemStack stack = event.itemStack;
        if (stack == null || event.toolTip.size() < 3) return;

        String displayName = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        boolean nameMatch = false;
        for (String validName : CHEST_NAMES) {
            if (displayName.contains(validName)) {
                nameMatch = true;
                break;
            }
        }
        if (!nameMatch) return;

        double totalValue = 0;
        long openCost = 0;
        boolean readingContents = false;
        boolean foundContentsTitle = false;
        boolean foundCostTitle = false;

        for (String line : event.toolTip) {
            String clean = EnumChatFormatting.getTextWithoutFormattingCodes(line).trim();
            if (clean.isEmpty()) continue;

            if (clean.equalsIgnoreCase("Contents")) {
                readingContents = true;
                foundContentsTitle = true;
                continue;
            }
            if (clean.equalsIgnoreCase("Cost")) {
                readingContents = false;
                foundCostTitle = true;
                continue;
            }
            
            if (clean.startsWith("NOTE:") || clean.startsWith("Requires") || clean.contains("Click to open")) {
                readingContents = false;
            }

            if (readingContents) {
                Matcher bookM = BOOK_PATTERN.matcher(clean);
                if (bookM.find()) {
                    String enchantName = bookM.group(1).toUpperCase().replace(" ", "_");
                    int level = romanToInt(bookM.group(2));
                    totalValue += SkyblockPriceManager.getPrice("ENCHANTMENT_" + enchantName + "_" + level);
                    continue;
                }

                Matcher itemM = ITEM_PATTERN.matcher(clean);
                if (itemM.find()) {
                    String itemName = itemM.group(1).trim();
                    if (itemName.equalsIgnoreCase("Contents") || itemName.equalsIgnoreCase("Cost")) continue;
                    
                    int amount = itemM.group(2) != null ? Integer.parseInt(itemM.group(2)) : 1;
                    totalValue += getItemPrice(itemName) * amount;
                }
            }

            if (foundCostTitle && openCost == 0) {
                Matcher coinsM = COINS_PATTERN.matcher(clean);
                if (coinsM.find()) {
                    openCost = Long.parseLong(coinsM.group(1).replace(",", ""));
                }
            }
        }

        if (foundContentsTitle && foundCostTitle) {
            renderProfitTooltip(event, totalValue, openCost);
        }
    }

    private double getItemPrice(String name) {
        String cleanName = name.replace("✪", "").replace("Recombobulated ", "").trim();
        String id = cleanName.toUpperCase().replace(" ", "_");
        
        if (id.contains("WITHER_ESSENCE")) return SkyblockPriceManager.getPrice("ESSENCE_WITHER");
        if (id.contains("UNDEAD_ESSENCE")) return SkyblockPriceManager.getPrice("ESSENCE_UNDEAD");
        if (id.contains("DRAGON_ESSENCE")) return SkyblockPriceManager.getPrice("ESSENCE_DRAGON");
        if (id.contains("RECOMBOBULATOR")) return SkyblockPriceManager.getPrice("RECOMBOBULATOR_3000");
        if (id.contains("FUMING_POTATO")) return SkyblockPriceManager.getPrice("FUMING_POTATO_BOOK");

        return SkyblockPriceManager.getPrice(id);
    }

    private void renderProfitTooltip(ItemTooltipEvent event, double value, long cost) {
        double profit = value - cost;
        event.toolTip.add("");
        event.toolTip.add("§6§lGhost Dungeon Helper");
        event.toolTip.add("§7Contents Value: §e" + format(value));
        event.toolTip.add("§7Opening Cost: §c-" + format(cost));
        
        EnumChatFormatting color = profit >= 0 ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
        String sign = profit >= 0 ? "+" : "";
        event.toolTip.add("§fEstimated Profit: " + color + EnumChatFormatting.BOLD + sign + format(profit));
    }

    private String format(double n) {
        if (n >= 1000000) return String.format("%.2fM", n / 1000000.0);
        if (n >= 1000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.0f", n);
    }

    private int romanToInt(String s) {
        Map<Character, Integer> map = new HashMap<>();
        map.put('I', 1); map.put('V', 5); map.put('X', 10);
        map.put('L', 50); map.put('C', 100); map.put('M', 1000);
        int res = 0;
        for (int i = 0; i < s.length(); i++) {
            int val = map.get(s.charAt(i));
            if (i < s.length() - 1 && val < map.get(s.charAt(i + 1))) res -= val;
            else res += val;
        }
        return res;
    }
}