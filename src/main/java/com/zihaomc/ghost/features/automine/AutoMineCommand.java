package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.automine.AutoMineHandler;
import com.zihaomc.ghost.features.automine.AutoMineTargetManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;

/**
 * /automine 命令的实现类。
 * 现在使用 AutoMineTargetManager 来管理坐标列表的持久化。
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
                if (args.length < 4) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.add"));
                BlockPos pos = parseBlockPos(sender, args, 1, false);
                AutoMineTargetManager.targetBlocks.add(pos);
                AutoMineTargetManager.saveTargets(); // 保存更改
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.command.add.success", pos.getX(), pos.getY(), pos.getZ())));
                break;

            case "remove":
                if (args.length < 2) throw new WrongUsageException(LangUtil.translate("ghost.automine.command.usage.remove"));
                int indexToRemove = parseInt(args[1], 1) - 1;
                if (indexToRemove >= 0 && indexToRemove < AutoMineTargetManager.targetBlocks.size()) {
                    BlockPos removed = AutoMineTargetManager.targetBlocks.remove(indexToRemove);
                    AutoMineTargetManager.saveTargets(); // 保存更改
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.remove.success", removed.getX(), removed.getY(), removed.getZ())));
                } else {
                    throw new CommandException(LangUtil.translate("ghost.automine.command.remove.error", AutoMineTargetManager.targetBlocks.size()));
                }
                break;

            case "list":
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "--- " + LangUtil.translate("ghost.automine.command.list.header") + " ---"));
                if (AutoMineTargetManager.targetBlocks.isEmpty()) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.command.list.empty")));
                } else {
                    for (int i = 0; i < AutoMineTargetManager.targetBlocks.size(); i++) {
                        BlockPos p = AutoMineTargetManager.targetBlocks.get(i);
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "" + (i + 1) + ". " + EnumChatFormatting.WHITE + String.format("(%d, %d, %d)", p.getX(), p.getY(), p.getZ())));
                    }
                }
                break;

            case "clear":
                AutoMineTargetManager.targetBlocks.clear();
                AutoMineTargetManager.saveTargets(); // 保存更改
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.clear.success")));
                if (AutoMineHandler.isActive()) {
                    AutoMineHandler.toggle();
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

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "clear", "toggle");
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("add")) {
            return func_175771_a(args, 1, pos);
        }
        return Collections.emptyList();
    }
}