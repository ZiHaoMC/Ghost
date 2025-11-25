package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
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

        BlockPos targetPos = GhostBlockHelper.parseBlockPosLegacy(sender, args, 0);
        BlockPos startPos = Minecraft.getMinecraft().thePlayer.getPosition();

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Ghost] 正在计算路径..."));

        // 在独立线程中计算路径，防止卡顿主线程
        new Thread(() -> {
            List<BlockPos> path = Pathfinder.computePath(startPos, targetPos, 5000); // 5000次迭代限制

            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (path != null && !path.isEmpty()) {
                    PathfindingHandler.setPath(path);
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 路径已找到 (" + path.size() + " 步)，开始移动。"));
                } else {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] 无法到达指定坐标 (路径未找到或距离太远)。"));
                }
            });
        }).start();
    }
    
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "stop");
        }
        return null;
    }
}