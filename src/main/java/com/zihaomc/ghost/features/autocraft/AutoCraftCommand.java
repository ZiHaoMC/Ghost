package com.zihaomc.ghost.features.autocraft;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.autocraft.AutoCraftHandler;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;

import java.util.Arrays;
import java.util.List;

/**
 * 处理 /autocraft 命令，用于启动或停止自动合成功能。
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
        if (args.length != 1 || (!args[0].equalsIgnoreCase("start") && !args[0].equalsIgnoreCase("stop"))) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        if (args[0].equalsIgnoreCase("start")) {
            AutoCraftHandler.toggle(); // toggle 会处理已经是 start 的情况
        } else { // "stop"
            AutoCraftHandler.toggle(); // toggle 也会处理 stop
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, net.minecraft.util.BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "start", "stop");
        }
        return null;
    }
}