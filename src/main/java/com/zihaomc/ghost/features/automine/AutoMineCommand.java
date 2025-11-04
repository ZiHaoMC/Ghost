package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.features.automine.AutoMineTargetManager;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /automine 命令的实现类，支持坐标和方块类型两种模式。
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
                // 列表命令现在会显示所有类型的目标
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "--- " + LangUtil.translate("ghost.automine.command.list.header_main") + " ---"));

                boolean hasCoords = !AutoMineTargetManager.targetBlocks.isEmpty();
                boolean hasBlock = AutoMineTargetManager.targetBlockType != null;

                if (!hasCoords && !hasBlock) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.none")));
                    break;
                }

                // 显示坐标目标
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.automine.command.list.header_coords")));
                if (hasCoords) {
                    for (int i = 0; i < AutoMineTargetManager.targetBlocks.size(); i++) {
                        BlockPos p = AutoMineTargetManager.targetBlocks.get(i);
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "" + (i + 1) + ". " + EnumChatFormatting.WHITE + String.format("(%d, %d, %d)", p.getX(), p.getY(), p.getZ())));
                    }
                } else {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.empty_coords")));
                }

                sender.addChatMessage(new ChatComponentText(" ")); // 添加一个空行作为分隔

                // 显示方块目标
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.automine.command.list.header_block")));
                if (hasBlock) {
                    Block block = AutoMineTargetManager.targetBlockType;
                    String name = block.getLocalizedName();
                    String id = block.getRegistryName().toString();
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.WHITE + LangUtil.translate("ghost.automine.command.list.block_entry", name, id)));
                } else {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.empty_block")));
                }
                break;

            case "clear":
                AutoMineTargetManager.targetBlocks.clear();
                AutoMineTargetManager.saveCoordinates(); // 保存更改
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.success")));
                if (AutoMineHandler.isActive()) {
                    AutoMineHandler.toggle(); // 如果正在运行，则关闭
                }
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
    
    /**
     * 处理 /automine add 子命令
     * @param sender 命令发送者
     * @param args 命令参数
     * @throws CommandException 命令异常
     */
    private void handleAdd(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
        }
        String addType = args[1].toLowerCase();
        if ("coord".equals(addType)) {
            if (args.length < 5) { // automine add coord x y z
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.coord"));
            }
            BlockPos pos = parseBlockPos(sender, args, 2, false);
            AutoMineTargetManager.targetBlocks.add(pos);
            AutoMineTargetManager.saveCoordinates();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.coord.success", pos.getX(), pos.getY(), pos.getZ())));
        } else if ("block".equals(addType)) {
            if (args.length < 3) { // automine add block <block_id>
                throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add.block"));
            }
            Block block = getBlockByText(sender, args[2]);
            AutoMineTargetManager.targetBlockType = block;
            AutoMineTargetManager.saveBlockType();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.block.success", block.getLocalizedName())));
        } else {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
        }
    }

    /**
     * 处理 /automine remove 子命令
     * @param sender 命令发送者
     * @param args 命令参数
     * @throws CommandException 命令异常
     */
    private void handleRemove(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));
        }
        String removeType = args[1].toLowerCase();
        if ("coord".equals(removeType)) {
            if (args.length < 3) { // automine remove coord <index>
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
            AutoMineTargetManager.targetBlockType = null;
            AutoMineTargetManager.saveBlockType();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.remove.block.success")));
        } else {
            throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "clear", "toggle");
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("add")) {
                return getListOfStringsMatchingLastWord(args, "coord", "block");
            }
            if (args[0].equalsIgnoreCase("remove")) {
                return getListOfStringsMatchingLastWord(args, "coord", "block");
            }
        }
        if (args.length > 2) {
            if (args[0].equalsIgnoreCase("add")) {
                if (args[1].equalsIgnoreCase("coord") && args.length >= 3 && args.length <= 5) {
                    return func_175771_a(args, 2, pos); // 坐标补全
                }
                if (args[1].equalsIgnoreCase("block") && args.length == 3) {
                    return getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys()); // 方块ID补全
                }
            }
        }
        return Collections.emptyList();
    }
}