package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.features.automine.AutoMineTargetManager;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /automine 命令的实现类，支持坐标、方块类型和权重模式。
 */
public class AutoMineCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "automine";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return LangUtil.translate("ghost.automine.command.usage");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "add":
                handleAdd(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            case "clear":
                handleClear(sender, args);
                break;
            case "weight":
                handleWeight(sender, args);
                break;
            case "toggle":
            case "start":
            case "stop":
                AutoMineHandler.toggle();
                break;
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void handleList(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "--- " + LangUtil.translate("ghost.automine.command.list.header_main") + " ---"));

        boolean hasCoords = !AutoMineTargetManager.targetBlocks.isEmpty();
        boolean hasBlocks = !AutoMineTargetManager.targetBlockTypes.isEmpty();
        boolean hasWeights = !AutoMineTargetManager.targetBlockWeights.isEmpty();

        if (!hasCoords && !hasBlocks) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.none")));
        }

        // --- 坐标目标 ---
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.automine.command.list.header_coords")));
        if (hasCoords) {
            for (int i = 0; i < AutoMineTargetManager.targetBlocks.size(); i++) {
                BlockPos p = AutoMineTargetManager.targetBlocks.get(i);
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "" + (i + 1) + ". " + EnumChatFormatting.WHITE + String.format("(%d, %d, %d)", p.getX(), p.getY(), p.getZ())));
            }
        } else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.empty_coords")));
        }
        sender.addChatMessage(new ChatComponentText(" "));

        // --- 方块类型目标 (整合显示) ---
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.automine.command.list.header_blocks")));
        if (hasBlocks) {
            Set<AutoMineTargetManager.BlockData> remainingTargets = new HashSet<>(AutoMineTargetManager.targetBlockTypes);
            List<ChatComponentText> groupComponents = new ArrayList<>();
            
            // 检查并处理 Mithril 组合
            List<AutoMineTargetManager.BlockData> mithrilComponents = getMithrilComponents(sender);
            if (remainingTargets.containsAll(mithrilComponents)) {
                remainingTargets.removeAll(mithrilComponents);
                groupComponents.add(createGroupComponent("Mithril", "skyblock:mithril", mithrilComponents));
            }
            
            // 检查并处理 Titanium 组合
            List<AutoMineTargetManager.BlockData> titaniumComponents = getTitaniumComponents(sender);
            if (remainingTargets.containsAll(titaniumComponents)) {
                remainingTargets.removeAll(titaniumComponents);
                groupComponents.add(createGroupComponent("Titanium", "skyblock:titanium", titaniumComponents));
            }

            // 显示整合后的组合
            for (ChatComponentText component : groupComponents) {
                sender.addChatMessage(component);
            }
            
            // 显示剩余的独立方块
            for (AutoMineTargetManager.BlockData blockData : remainingTargets) {
                String displayName = getBlockDisplayName(blockData);
                String id = blockData.toString();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.WHITE + "- " + LangUtil.translate("ghost.automine.command.list.block_entry", displayName, id)));
            }

        } else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.empty_blocks")));
        }
        sender.addChatMessage(new ChatComponentText(" "));

        // --- 权重 ---
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.automine.command.list.header_weights")));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY + LangUtil.translate("ghost.automine.command.list.weights_default_hint")));
        if(hasWeights) {
            for (Map.Entry<Block, Integer> entry : AutoMineTargetManager.targetBlockWeights.entrySet()) {
                String name = entry.getKey().getLocalizedName();
                String id = entry.getKey().getRegistryName().toString();
                Integer weight = entry.getValue();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.WHITE + "- " + LangUtil.translate("ghost.automine.command.list.weight_entry", name, id, weight)));
            }
        } else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.empty_weights")));
        }
    }
    
    private AutoMineTargetManager.BlockData parseBlockData(ICommandSender sender, String input) throws CommandException {
        try {
            String blockId = input;
            int meta = -1; // -1 for wildcard
            int lastColon = input.lastIndexOf(':');
            if (lastColon != -1 && lastColon > input.indexOf(':')) { // Ensure it's not the first colon (e.g., minecraft:stone)
                String potentialMeta = input.substring(lastColon + 1);
                try {
                    meta = Integer.parseInt(potentialMeta);
                    blockId = input.substring(0, lastColon);
                } catch (NumberFormatException e) {
                    // Not a meta, so the whole string is the ID
                }
            }
            Block block = getBlockByText(sender, blockId);
            return new AutoMineTargetManager.BlockData(block, meta);
        } catch (CommandException e) {
            throw new CommandException("commands.generic.block.notFound", input);
        }
    }
    
    private void handleAdd(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
        }
        String addType = args[1].toLowerCase();
        if ("coord".equals(addType)) {
            if (args.length < 5) {
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.coord"));
            }
            BlockPos pos = parseBlockPos(sender, args, 2, false);
            AutoMineTargetManager.targetBlocks.add(pos);
            AutoMineTargetManager.saveCoordinates();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.coord.success", pos.getX(), pos.getY(), pos.getZ())));
        } else if ("block".equals(addType)) {
            if (args.length < 3) {
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.block"));
            }
            String blockIdOrAlias = args[2];
            if ("skyblock:mithril".equalsIgnoreCase(blockIdOrAlias)) {
                List<AutoMineTargetManager.BlockData> mithrilComponents = getMithrilComponents(sender);
                int addedCount = 0;
                for (AutoMineTargetManager.BlockData blockData : mithrilComponents) {
                    if (AutoMineTargetManager.targetBlockTypes.add(blockData)) {
                        addedCount++;
                    }
                }
                if (addedCount > 0) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.group.success", "Mithril", addedCount)));
                } else {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.add.group.already_exists", "Mithril")));
                }
            } else if ("skyblock:titanium".equalsIgnoreCase(blockIdOrAlias)) {
                List<AutoMineTargetManager.BlockData> titaniumComponents = getTitaniumComponents(sender);
                int addedCount = 0;
                for(AutoMineTargetManager.BlockData blockData : titaniumComponents){
                    if(AutoMineTargetManager.targetBlockTypes.add(blockData)){
                        addedCount++;
                    }
                }
                if (addedCount > 0) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.group.success", "Titanium", addedCount)));
                } else {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.add.group.already_exists", "Titanium")));
                }
            } else {
                AutoMineTargetManager.BlockData blockData = parseBlockData(sender, blockIdOrAlias);
                if (AutoMineTargetManager.targetBlockTypes.add(blockData)) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.block.success", getBlockDisplayName(blockData))));
                } else {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.add.block.already_exists", blockData.toString())));
                }
            }
        } else {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
        }
    }
    
    private void handleRemove(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));
        }
        String removeType = args[1].toLowerCase();
        if ("coord".equals(removeType)) {
            if (args.length < 3) {
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove.coord"));
            }
            int indexToRemove = parseInt(args[2], 1) - 1;
            if (indexToRemove >= 0 && indexToRemove < AutoMineTargetManager.targetBlocks.size()) {
                BlockPos removed = AutoMineTargetManager.targetBlocks.remove(indexToRemove);
                AutoMineTargetManager.saveCoordinates();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.remove.coord.success", removed.getX(), removed.getY(), removed.getZ())));
            } else {
                throw new CommandException(LangUtil.translate("ghost.automine.command.remove.coord.error", AutoMineTargetManager.targetBlocks.size()));
            }
        } else if ("block".equals(removeType)) {
            if (args.length < 3) {
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove.block"));
            }
            AutoMineTargetManager.BlockData blockData = parseBlockData(sender, args[2]);
            if (AutoMineTargetManager.targetBlockTypes.remove(blockData)) {
                AutoMineTargetManager.saveBlockTypes();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.remove.block.success", blockData.toString())));
            } else {
                throw new CommandException(LangUtil.translate("ghost.automine.command.remove.block.not_found", blockData.toString()));
            }
        } else {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));
        }
    }

    private void handleClear(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.clear"));
        }
        String clearType = args[1].toLowerCase();
        if ("coords".equals(clearType)) {
            AutoMineTargetManager.targetBlocks.clear();
            AutoMineTargetManager.saveCoordinates();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.coords.success")));
        } else if ("blocks".equals(clearType)) {
            AutoMineTargetManager.targetBlockTypes.clear();
            AutoMineTargetManager.saveBlockTypes();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.blocks.success")));
        } else if ("weights".equals(clearType)) {
            AutoMineTargetManager.targetBlockWeights.clear();
            AutoMineTargetManager.saveBlockWeights();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.weights.success")));
        } else if ("blacklist".equals(clearType)) {
            AutoMineHandler.clearBlacklist();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.blacklist.success")));
        } else {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.clear"));
        }

        if (AutoMineTargetManager.targetBlocks.isEmpty() && AutoMineTargetManager.targetBlockTypes.isEmpty() && AutoMineHandler.isActive()) {
            AutoMineHandler.toggle();
        }
    }

    private void handleWeight(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight"));
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "set":
                if (args.length < 4) {
                    throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight.set"));
                }
                Block block = getBlockByText(sender, args[2]);
                int weight = parseInt(args[3], 1);
                if (weight <= 0) {
                    throw new CommandException(LangUtil.translate("ghost.automine.command.weight.error.positive"));
                }
                AutoMineTargetManager.targetBlockWeights.put(block, weight);
                AutoMineTargetManager.saveBlockWeights();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.weight.set.success", block.getLocalizedName(), weight)));
                break;
            case "clear":
                AutoMineTargetManager.targetBlockWeights.clear();
                AutoMineTargetManager.saveBlockWeights();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.weights.success")));
                break;
            default:
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight"));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "clear", "toggle", "weight");
        }
        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if ("add".equals(subCmd) || "remove".equals(subCmd)) {
                return getListOfStringsMatchingLastWord(args, "coord", "block");
            }
            if ("clear".equals(subCmd)) {
                return getListOfStringsMatchingLastWord(args, "coords", "blocks", "weights", "blacklist");
            }
            if ("weight".equals(subCmd)) {
                return getListOfStringsMatchingLastWord(args, "set", "clear");
            }
        }
        if (args.length > 2) {
            String subCmd = args[0].toLowerCase();
            String type = args[1].toLowerCase();
            if ("add".equals(subCmd)) {
                if ("coord".equals(type) && args.length >= 3 && args.length <= 5) {
                    return func_175771_a(args, 2, pos);
                }
                if ("block".equals(type) && args.length == 3) {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("skyblock:mithril");
                    suggestions.add("skyblock:titanium");
                    for (ResourceLocation location : Block.blockRegistry.getKeys()) {
                        suggestions.add(location.toString());
                    }
                    return getListOfStringsMatchingLastWord(args, suggestions);
                }
            }
            if ("remove".equals(subCmd)) {
                if ("coord".equals(type) && args.length == 3) {
                    return getListOfStringsMatchingLastWord(args, "1"); 
                }
                if ("block".equals(type) && args.length == 3) {
                    List<String> addedBlockIds = AutoMineTargetManager.targetBlockTypes.stream().map(AutoMineTargetManager.BlockData::toString).collect(Collectors.toList());
                    return getListOfStringsMatchingLastWord(args, addedBlockIds);
                }
            }
            if ("weight".equals(subCmd) && "set".equals(type)) {
                if (args.length == 3) {
                    List<String> blockIdSuggestions = new ArrayList<>();
                    for (ResourceLocation location : Block.blockRegistry.getKeys()) {
                        blockIdSuggestions.add(location.toString());
                    }
                    return getListOfStringsMatchingLastWord(args, blockIdSuggestions);
                }
                if (args.length == 4) {
                    return getListOfStringsMatchingLastWord(args, "10", "50", "100", "200");
                }
            }
        }
        return Collections.emptyList();
    }
    
    // --- Helper Methods for Block Groups & Display ---

    private String getBlockDisplayName(AutoMineTargetManager.BlockData blockData) {
        if (blockData.metadata == -1) {
            return blockData.block.getLocalizedName();
        }
        return new ItemStack(blockData.block, 1, blockData.metadata).getDisplayName();
    }

    private List<AutoMineTargetManager.BlockData> getMithrilComponents(ICommandSender sender) {
        List<String> ids = Arrays.asList("minecraft:wool:7", "minecraft:prismarine", "minecraft:wool:11", "minecraft:stained_hardened_clay:9");
        List<AutoMineTargetManager.BlockData> components = new ArrayList<>();
        for(String id : ids) {
            try {
                components.add(parseBlockData(sender, id));
            } catch (CommandException e) { /* ignore */ }
        }
        return components;
    }

    private List<AutoMineTargetManager.BlockData> getTitaniumComponents(ICommandSender sender) {
        List<String> ids = Arrays.asList("minecraft:stone:4");
        List<AutoMineTargetManager.BlockData> components = new ArrayList<>();
        for(String id : ids) {
            try {
                components.add(parseBlockData(sender, id));
            } catch (CommandException e) { /* ignore */ }
        }
        return components;
    }

    private ChatComponentText createGroupComponent(String groupName, String alias, List<AutoMineTargetManager.BlockData> components) {
        String hoverText = LangUtil.translate("ghost.automine.command.list.group_hover_tooltip", 
            components.stream()
                .map(this::getBlockDisplayName)
                .collect(Collectors.joining(", ")));
        
        ChatComponentText textComponent = new ChatComponentText(EnumChatFormatting.WHITE + "- " + LangUtil.translate("ghost.automine.command.list.block_entry", groupName, alias));
        ChatStyle style = new ChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hoverText)));
        textComponent.setChatStyle(style);
        return textComponent;
    }
}