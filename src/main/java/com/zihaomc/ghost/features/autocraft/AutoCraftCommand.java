package com.zihaomc.ghost.features.autocraft;

import com.zihaomc.ghost.LangUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 处理 /autocraft 命令，用于启动、停止、管理和重载自动合成功能。
 */
public class AutoCraftCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "autocraft";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return LangUtil.translate("ghost.autocraft.command.usage");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // 客户端命令，无需权限
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "start":
                handleStart(sender, args);
                break;
            case "stop":
                AutoCraftHandler.stop();
                break;
            case "reload":
                AutoCraftRecipeManager.reloadRecipes();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.autocraft.command.reload.success")));
                break;
            case "add":
                handleAdd(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void handleStart(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.autocraft.command.usage.start"));
        }
        String recipeKey = args[1];
        AutoCraftRecipe recipe = AutoCraftRecipeManager.getRecipe(recipeKey);
        if (recipe == null) {
            throw new CommandException(LangUtil.translate("ghost.autocraft.error.recipe_not_found"), recipeKey);
        }
        AutoCraftHandler.start(recipe);
    }

    private void handleAdd(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 4) {
            String[] usageLines = LangUtil.translate("ghost.autocraft.command.usage.add").split("\n");
            for(String line : usageLines) {
                sender.addChatMessage(new ChatComponentText(line));
            }
            return;
        }
        String key = args[1].toLowerCase();
        if (AutoCraftRecipeManager.getRecipe(key) != null) {
            throw new CommandException(LangUtil.translate("ghost.autocraft.error.recipe_already_exists"), key);
        }

        String ingredientName = args[2].replace('_', ' ');
        int amount = parseAmount(args[3]);

        AutoCraftRecipe newRecipe = new AutoCraftRecipe(key, ingredientName, amount);
        AutoCraftRecipeManager.registerRecipe(newRecipe);
        AutoCraftRecipeManager.saveRecipes();

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.autocraft.command.add.success", key)));
    }

    private void handleRemove(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.autocraft.command.usage.remove"));
        }
        String key = args[1].toLowerCase();
        if (AutoCraftRecipeManager.getRecipe(key) == null) {
            throw new CommandException(LangUtil.translate("ghost.autocraft.error.recipe_not_found"), key);
        }
        
        AutoCraftRecipeManager.removeRecipe(key);
        AutoCraftRecipeManager.saveRecipes();
        
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.autocraft.command.remove.success", key)));
    }

    private void handleList(ICommandSender sender) {
        Collection<AutoCraftRecipe> recipes = AutoCraftRecipeManager.getAllRecipes();
        if (recipes.isEmpty()) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.command.list.empty")));
            return;
        }

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "--- " + LangUtil.translate("ghost.autocraft.command.list.header") + " ---"));
        for (AutoCraftRecipe recipe : recipes) {
            String hoverText = String.format(
                "§e材料: §f%s §ex%d",
                recipe.ingredientDisplayName, recipe.requiredAmount
            );
            ChatComponentText line = new ChatComponentText("§a- " + recipe.recipeKey);
            line.setChatStyle(new ChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hoverText))));
            sender.addChatMessage(line);
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "start", "stop", "add", "remove", "list", "reload");
        }
        
        String action = args[0].toLowerCase();
        
        if ("start".equals(action) || "remove".equals(action)) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, AutoCraftRecipeManager.getAllRecipeKeys());
            }
        }

        if ("add".equals(action)) {
            // 当输入第二个参数 (配方Key) 时，提供一个固定的占位符
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, Collections.singletonList("recipe_key"));
            }
            // 当输入第三个参数 (材料名) 时
            if (args.length == 3) {
                EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
                if (player != null) {
                    Set<String> uniqueItemNames = getUniqueItemNamesFromInventory(player);
                    if (!uniqueItemNames.isEmpty()) {
                        return getListOfStringsMatchingLastWord(args, uniqueItemNames.stream().map(name -> name.replace(' ', '_')).collect(Collectors.toList()));
                    }
                }
                return Collections.emptyList();
            }
            if (args.length == 4) { // requiredAmount
                return getListOfStringsMatchingLastWord(args, "64", "160", "320", "576", "64*");
            }
        }
        
        return null;
    }

    /**
     * 解析数量字符串，支持 'x' 或 '*' 作为乘法符号。
     */
    private int parseAmount(String amountString) throws CommandException {
        amountString = amountString.toLowerCase();
        try {
            if (amountString.contains("*") || amountString.contains("x")) {
                String[] parts = amountString.split("[*x]");
                if (parts.length == 2) {
                    int num1 = Integer.parseInt(parts[0].trim());
                    int num2 = Integer.parseInt(parts[1].trim());
                    return num1 * num2;
                }
            }
            return CommandBase.parseInt(amountString);
        } catch (NumberFormatException e) {
            throw new CommandException(LangUtil.translate("ghost.autocraft.error.invalid_amount_format"), amountString);
        }
    }

    /**
     * 扫描玩家物品栏并返回所有唯一的物品显示名称。
     */
    private Set<String> getUniqueItemNamesFromInventory(EntityPlayerSP player) {
        Set<String> uniqueItemNames = new HashSet<>();
        if (player != null) {
            for (ItemStack itemStack : player.inventory.mainInventory) {
                if (itemStack != null) {
                    String itemName = EnumChatFormatting.getTextWithoutFormattingCodes(itemStack.getDisplayName());
                    if (!itemName.trim().isEmpty()) {
                        uniqueItemNames.add(itemName);
                    }
                }
            }
        }
        return uniqueItemNames;
    }
}