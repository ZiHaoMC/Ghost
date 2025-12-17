package com.zihaomc.ghost.features.pathfinding;

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
    public String getCommandName() { return "gpath"; }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/gpath <x> <y> <z> 或 /gpath stop"; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));

        if (args[0].equalsIgnoreCase("stop")) {
            PathfindingHandler.stop();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[Ghost] 寻路已停止。"));
            return;
        }

        if (args.length < 3) throw new WrongUsageException(getCommandUsage(sender));

        try {
            int x = Integer.parseInt(args[0]);
            int y = Integer.parseInt(args[1]);
            int z = Integer.parseInt(args[2]);
            
            BlockPos target = new BlockPos(x, y, z);
            PathfindingHandler.setGlobalTarget(target);
            
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 寻路目标已设定: " + x + ", " + y + ", " + z));
        } catch (NumberFormatException e) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "坐标必须是整数。"));
        }
    }
    
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "stop");
        return null;
    }
}