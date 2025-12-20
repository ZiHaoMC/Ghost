
/*
 * This module is a derivative work of Baritone (https://github.com/cabaletta/baritone).
 * This module is licensed under the GNU LGPL v3.0.
 */

package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;

import java.util.List;

public class PathfindingCommand extends CommandBase {

    @Override
    public String getCommandName() { return "gpath"; }

    @Override
    public String getCommandUsage(ICommandSender sender) { return LangUtil.translate("ghost.pathfinding.command.usage"); }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));

        // 停止寻路逻辑
        if (args[0].equalsIgnoreCase("stop")) {
            PathfindingHandler.stop();
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghost.pathfinding.stop.success"));
            return;
        }

        if (args.length < 3) throw new WrongUsageException(getCommandUsage(sender));

        // 获取发送者的位置向量，用于解析相对坐标 (~)
        Vec3 senderVec = sender.getPositionVector();
        
        try {
            // parseDouble 是 CommandBase 的方法，支持 ~ 相对坐标计算
            // 参数1: 基准坐标, 参数2: 输入字符串, 参数3: 是否对整数坐标进行 .5 偏移
            double x = parseDouble(senderVec.xCoord, args[0], true);
            double y = parseDouble(senderVec.yCoord, args[1], 0, 256, false); // 限制高度在 0-256 之间
            double z = parseDouble(senderVec.zCoord, args[2], true);
            
            // 将解析后的双精度坐标转换为 BlockPos
            BlockPos target = new BlockPos(x, y, z);
            PathfindingHandler.setGlobalTarget(target);
            
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghost.pathfinding.target.set", target.getX(), target.getY(), target.getZ()));
        } catch (NumberFormatException e) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED, "ghost.pathfinding.error.invalid_coord"));
        }
    }
    
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            List<String> options = getListOfStringsMatchingLastWord(args, "stop");
            // 如果输入不是 stop，尝试提供 X 坐标补全
            if (options.isEmpty() && pos != null) {
                return func_175771_a(args, 0, pos);
            }
            return options;
        }
        // 提供 Y 和 Z 轴的坐标补全
        if (args.length > 1 && args.length <= 3 && pos != null) {
            return func_175771_a(args, 0, pos);
        }
        return null;
    }
}