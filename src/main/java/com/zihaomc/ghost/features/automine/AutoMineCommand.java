package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.features.automine.AutoMineTargetManager;
import com.zihaomc.ghost.features.automine.PredefinedGroupManager;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /automine 命令的实现类 (重构优化版)。
 * 采用统一的动词优先结构，提升用户体验和可维护性。
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
        if (args.length < 1 || "help".equalsIgnoreCase(args[0])) {
            handleHelp(sender);
            return;
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
            case "group":
                handleGroup(sender, args);
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

    private void handleAdd(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
        
        String addType = args[1].toLowerCase();
        switch (addType) {
            case "coord":
                if (args.length < 5) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.coord"));
                BlockPos pos = parseBlockPos(sender, args, 2, false);
                AutoMineTargetManager.targetBlocks.add(pos);
                AutoMineTargetManager.saveCoordinates();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.add.coord.success", pos.getX(), pos.getY(), pos.getZ())));
                break;
            case "block":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.block"));
                AutoMineTargetManager.BlockData blockData = parseBlockData(sender, args[2]);
                if (AutoMineTargetManager.targetBlockTypes.add(blockData)) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.add.block.success", getBlockDisplayName(blockData))));
                } else {
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.add.block.already_exists", blockData.toString())));
                }
                break;
            case "group":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.group"));
                String groupName = args[2].toLowerCase();
                List<AutoMineTargetManager.BlockData> components = getComponentsForGroup(sender, groupName);
                if (components.isEmpty()) throw new CommandException(LangUtil.translate("ghost.automine.command.group.not_found", groupName));

                int addedCount = 0;
                for (AutoMineTargetManager.BlockData component : components) {
                    if (AutoMineTargetManager.targetBlockTypes.add(component)) addedCount++;
                }
                
                if (addedCount > 0) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.add.group.success", groupName, addedCount)));
                } else {
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.add.group.already_exists", groupName)));
                }
                break;
            default:
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
        }
    }

    private void handleRemove(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));

        String removeType = args[1].toLowerCase();
        switch (removeType) {
            case "coord":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove.coord"));
                int indexToRemove = parseInt(args[2], 1) - 1;
                if (indexToRemove >= 0 && indexToRemove < AutoMineTargetManager.targetBlocks.size()) {
                    BlockPos removed = AutoMineTargetManager.targetBlocks.remove(indexToRemove);
                    AutoMineTargetManager.saveCoordinates();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.remove.coord.success", removed.getX(), removed.getY(), removed.getZ())));
                } else {
                    throw new CommandException(LangUtil.translate("ghost.automine.command.remove.coord.error", AutoMineTargetManager.targetBlocks.size()));
                }
                break;
            case "block":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove.block"));
                AutoMineTargetManager.BlockData blockData = parseBlockData(sender, args[2]);
                if (AutoMineTargetManager.targetBlockTypes.remove(blockData)) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.remove.block.success", blockData.toString())));
                } else {
                    throw new CommandException(LangUtil.translate("ghost.automine.command.remove.block.not_found", blockData.toString()));
                }
                break;
            case "group":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove.group"));
                String groupName = args[2].toLowerCase();
                List<AutoMineTargetManager.BlockData> components = getComponentsForGroup(sender, groupName);
                if (components.isEmpty()) throw new CommandException(LangUtil.translate("ghost.automine.command.group.not_found", groupName));

                int removedCount = 0;
                for (AutoMineTargetManager.BlockData component : components) {
                    if (AutoMineTargetManager.targetBlockTypes.remove(component)) removedCount++;
                }

                if (removedCount > 0) {
                    AutoMineTargetManager.saveBlockTypes();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.remove.group.success", groupName, removedCount)));
                } else {
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.remove.group.none_found", groupName)));
                }
                break;
            default:
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));
        }
    }

    private void handleClear(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.clear"));
        
        String clearType = args[1].toLowerCase();
        switch (clearType) {
            case "coords":
                AutoMineTargetManager.targetBlocks.clear();
                AutoMineTargetManager.saveCoordinates();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.clear.coords.success")));
                break;
            case "blocks":
                AutoMineTargetManager.targetBlockTypes.clear();
                AutoMineTargetManager.saveBlockTypes();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.clear.blocks.success")));
                break;
            case "weights":
                AutoMineTargetManager.targetBlockWeights.clear();
                AutoMineTargetManager.saveBlockWeights();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.clear.weights.success")));
                break;
            case "blacklist":
                AutoMineHandler.clearBlacklist();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.clear.blacklist.success")));
                break;
            default:
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.clear"));
        }

        if (AutoMineTargetManager.targetBlocks.isEmpty() && AutoMineTargetManager.targetBlockTypes.isEmpty() && AutoMineHandler.isActive()) {
            AutoMineHandler.toggle();
        }
    }

    private void handleWeight(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight"));
        
        String action = args[1].toLowerCase();
        switch (action) {
            case "set":
                if (args.length < 4) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight.set"));
                Block block = getBlockByText(sender, args[2]);
                int weight = parseInt(args[3], 1);
                if (weight <= 0) throw new CommandException(LangUtil.translate("ghost.automine.command.weight.error.positive"));
                AutoMineTargetManager.targetBlockWeights.put(block, weight);
                AutoMineTargetManager.saveBlockWeights();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.weight.set.success", block.getLocalizedName(), weight)));
                break;
            case "clear":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight.clear_one"));
                Block blockToClear = getBlockByText(sender, args[2]);
                if (AutoMineTargetManager.targetBlockWeights.remove(blockToClear) != null) {
                    AutoMineTargetManager.saveBlockWeights();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.weight.clear.success", blockToClear.getLocalizedName())));
                } else {
                    throw new CommandException(LangUtil.translate("ghost.automine.command.weight.clear.not_found", blockToClear.getLocalizedName()));
                }
                break;
            default:
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.weight"));
        }
    }
    
    private void handleGroup(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.group"));
        
        String action = args[1].toLowerCase();
        switch (action) {
            case "create":
                if (args.length < 4) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.group.create"));
                String groupName = args[2].toLowerCase();
                List<String> components = new ArrayList<>(Arrays.asList(args).subList(3, args.length));
                for (String comp : components) parseBlockData(sender, comp); // 验证所有组件
                AutoMineTargetManager.customBlockGroups.put(groupName, components);
                AutoMineTargetManager.saveBlockGroups();
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.group.create.success", groupName, components.size())));
                break;
            case "delete":
                if (args.length < 3) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.group.delete"));
                String groupToRemove = args[2].toLowerCase();
                if (AutoMineTargetManager.customBlockGroups.remove(groupToRemove) != null) {
                    AutoMineTargetManager.saveBlockGroups();
                    sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.group.delete.success", groupToRemove)));
                } else {
                    throw new CommandException(LangUtil.translate("ghost.automine.command.group.not_found", groupToRemove));
                }
                break;
            default:
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.group"));
        }
    }

    private void handleList(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.header_main")));

        boolean hasCoords = !AutoMineTargetManager.targetBlocks.isEmpty();
        boolean hasBlocks = !AutoMineTargetManager.targetBlockTypes.isEmpty();
        boolean hasWeights = !AutoMineTargetManager.targetBlockWeights.isEmpty();
        boolean hasGroups = !AutoMineTargetManager.customBlockGroups.isEmpty();

        if (!hasCoords && !hasBlocks && !hasGroups) {
            sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.none")));
            return;
        }

        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.header_coords")));
        if (hasCoords) {
            for (int i = 0; i < AutoMineTargetManager.targetBlocks.size(); i++) {
                BlockPos p = AutoMineTargetManager.targetBlocks.get(i);
                sender.addChatMessage(new ChatComponentText(String.format("§e%d. §f(%d, %d, %d)", i + 1, p.getX(), p.getY(), p.getZ())));
            }
        } else {
            sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.empty_coords")));
        }
        sender.addChatMessage(new ChatComponentText(" "));

        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.header_blocks")));
        if (hasBlocks) {
            for (AutoMineTargetManager.BlockData blockData : AutoMineTargetManager.targetBlockTypes) {
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.block_entry", getBlockDisplayName(blockData), blockData.toString())));
            }
        } else {
            sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.empty_blocks")));
        }
        sender.addChatMessage(new ChatComponentText(" "));

        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.header_groups")));
        if (hasGroups) {
            for (String groupName : AutoMineTargetManager.customBlockGroups.keySet()) {
                List<AutoMineTargetManager.BlockData> components = getComponentsForGroup(sender, groupName);
                boolean isEnabled = !components.isEmpty() && AutoMineTargetManager.targetBlockTypes.containsAll(components);
                sender.addChatMessage(createGroupComponent(groupName, components, isEnabled));
            }
        } else {
            sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.empty_groups")));
        }
        sender.addChatMessage(new ChatComponentText(" "));

        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.header_weights")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.weights_default_hint")));
        if(hasWeights) {
            for (Map.Entry<Block, Integer> entry : AutoMineTargetManager.targetBlockWeights.entrySet()) {
                sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.weight_entry", entry.getKey().getLocalizedName(), entry.getKey().getRegistryName(), entry.getValue())));
            }
        } else {
            sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.list.empty_weights")));
        }
    }

    private void handleHelp(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.header")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.description")));
        sender.addChatMessage(new ChatComponentText(" "));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommands.header")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.toggle")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.list")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.add")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.remove")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.clear")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.weight")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.subcommand.group")));
        sender.addChatMessage(new ChatComponentText(" "));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.header")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.add_coord")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.add_block")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.add_group")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.remove_coord")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.create_group")));
        sender.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.help.examples.set_weight")));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "clear", "toggle", "weight", "group", "help");
        }
        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            switch (subCmd) {
                case "add":
                case "remove":
                    return getListOfStringsMatchingLastWord(args, "coord", "block", "group");
                case "clear":
                    return getListOfStringsMatchingLastWord(args, "coords", "blocks", "weights", "blacklist");
                case "weight":
                    return getListOfStringsMatchingLastWord(args, "set", "clear");
                case "group":
                    return getListOfStringsMatchingLastWord(args, "create", "delete");
            }
        }
        if (args.length > 2) {
            String subCmd = args[0].toLowerCase();
            String type = args[1].toLowerCase();
            switch (subCmd) {
                case "add":
                    if ("coord".equals(type) && args.length <= 5) return func_175771_a(args, 2, pos);
                    if ("block".equals(type) && args.length == 3) return getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
                    if ("group".equals(type) && args.length == 3) return getListOfStringsMatchingLastWord(args, AutoMineTargetManager.customBlockGroups.keySet());
                    break;
                case "remove":
                    if ("coord".equals(type) && args.length == 3) {
                        List<String> indices = new ArrayList<>();
                        for (int i = 1; i <= AutoMineTargetManager.targetBlocks.size(); i++) indices.add(String.valueOf(i));
                        return getListOfStringsMatchingLastWord(args, indices);
                    }
                    if ("block".equals(type) && args.length == 3) {
                        List<String> addedBlockIds = AutoMineTargetManager.targetBlockTypes.stream().map(AutoMineTargetManager.BlockData::toString).collect(Collectors.toList());
                        return getListOfStringsMatchingLastWord(args, addedBlockIds);
                    }
                    if ("group".equals(type) && args.length == 3) return getListOfStringsMatchingLastWord(args, AutoMineTargetManager.customBlockGroups.keySet());
                    break;
                case "weight":
                    if (("set".equals(type) || "clear".equals(type)) && args.length == 3) return getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
                    if ("set".equals(type) && args.length == 4) return getListOfStringsMatchingLastWord(args, "10", "50", "100");
                    break;
                case "group":
                    if ("delete".equals(type) && args.length == 3) return getListOfStringsMatchingLastWord(args, AutoMineTargetManager.customBlockGroups.keySet());
                    if ("create".equals(type) && args.length >= 4) return getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
                    break;
            }
        }
        return Collections.emptyList();
    }
    
    // --- Helper Methods ---

    /**
     * [新增] 解析方块ID字符串 (例如 "minecraft:stone:1") 的辅助方法。
     */
    private AutoMineTargetManager.BlockData parseBlockData(ICommandSender sender, String input) throws CommandException {
        String blockIdString = input;
        int meta = -1; // -1 for wildcard
        int lastColonIndex = input.lastIndexOf(':');
        if (lastColonIndex > 0 && lastColonIndex > input.indexOf(':')) {
            String potentialMeta = input.substring(lastColonIndex + 1);
            try {
                meta = Integer.parseInt(potentialMeta);
                blockIdString = input.substring(0, lastColonIndex);
            } catch (NumberFormatException e) {
                // Not a meta
            }
        }
        
        try {
            Block block = getBlockByText(sender, blockIdString);
            return new AutoMineTargetManager.BlockData(block, meta);
        } catch (CommandException e) {
            throw new CommandException("commands.generic.block.notFound", input);
        }
    }

    private String getBlockDisplayName(AutoMineTargetManager.BlockData blockData) {
        try {
            if (blockData.metadata == -1) return blockData.block.getLocalizedName();
            return new ItemStack(blockData.block, 1, blockData.metadata).getDisplayName();
        } catch (Exception e) {
            return blockData.toString();
        }
    }

    private List<AutoMineTargetManager.BlockData> getComponentsForGroup(ICommandSender sender, String groupName) {
        List<String> componentIds = AutoMineTargetManager.customBlockGroups.get(groupName.toLowerCase());
        if (componentIds == null) return new ArrayList<>();
        return componentIds.stream().map(id -> {
            try { return parseBlockData(sender, id); } catch (CommandException e) { return null; }
        }).filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }

    private ChatComponentText createGroupComponent(String groupName, List<AutoMineTargetManager.BlockData> components, boolean isEnabled) {
        String hoverText = LangUtil.translate("ghost.automine.command.list.group_hover_tooltip", 
            components.stream().map(this::getBlockDisplayName).collect(Collectors.joining(", ")));
        
        String status = isEnabled ? LangUtil.translate("ghost.generic.enabled") : LangUtil.translate("ghost.generic.disabled");
        
        ChatComponentText textComponent = new ChatComponentText("§f- " + groupName + " [" + status + "§f]");
        textComponent.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hoverText)));
        return textComponent;
    }
}