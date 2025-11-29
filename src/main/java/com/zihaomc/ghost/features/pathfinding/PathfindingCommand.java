package com.zihaomc.ghost.features.pathfinding;

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

        final int x, y, z;
        try {
            x = Integer.parseInt(args[0]);
            y = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "坐标格式错误，请输入整数。"));
            return;
        }

        BlockPos targetPos = new BlockPos(x, y, z);
        BlockPos startPos = Minecraft.getMinecraft().thePlayer.getPosition();

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Ghost] 正在计算路径... (目标: " + x + ", " + y + ", " + z + ")"));

        Minecraft.getMinecraft().addScheduledTask(() -> {
            try {
                long t1 = System.currentTimeMillis();
                
                // 5000 迭代限制
                List<BlockPos> path = Pathfinder.computePath(startPos, targetPos, 5000);

                long t2 = System.currentTimeMillis();

                if (path != null && !path.isEmpty()) {
                    PathfindingHandler.setPath(path);
                    String msg = String.format("[Ghost] 路径找到! 长度: %d, 耗时: %dms", path.size(), (t2 - t1));
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + msg));
                    
                    // --- 调试输出 ---
                    // 打印前 5 个节点，看看它到底想去哪
                    StringBuilder sb = new StringBuilder(EnumChatFormatting.GOLD + "前5步: ");
                    for (int i = 0; i < Math.min(5, path.size()); i++) {
                        BlockPos p = path.get(i);
                        sb.append(String.format("[%d,%d,%d] ", p.getX(), p.getY(), p.getZ()));
                    }
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(sb.toString()));
                    
                } else {
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] 寻路失败：未找到路径。"));
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (Minecraft.getMinecraft().thePlayer != null) {
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.DARK_RED + "[Ghost] 错误: " + e.getMessage()));
                }
            }
        });
    }
    
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "stop");
        }
        return null;
    }
}