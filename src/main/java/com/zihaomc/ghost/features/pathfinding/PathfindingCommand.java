package com.zihaomc.ghost.features.pathfinding;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

public class PathfindingCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "gpath";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gpath <x> <y> <z> 或 /gpath stop";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
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

        if (args[0].equalsIgnoreCase("stop")) {
            PathfindingHandler.stop();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[Ghost] 寻路已停止。"));
            return;
        }

        if (args.length < 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        final int x, y, z;
        try {
            x = Integer.parseInt(args[0]);
            y = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "坐标格式错误，请输入整数。"));
            return;
        }

        BlockPos globalTarget = new BlockPos(x, y, z);
        
        // 这里不再计算路径，而是直接设置全局目标，启动动态引擎
        PathfindingHandler.setGlobalTarget(globalTarget);
        
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 长途寻路已启动！目标: " + x + ", " + y + ", " + z));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Ghost] 系统将分段计算路径..."));
    }
    
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "stop");
        }
        return null;
    }
}