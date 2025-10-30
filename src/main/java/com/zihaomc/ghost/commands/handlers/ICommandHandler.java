package com.zihaomc.ghost.commands.handlers;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.List;

/**
 * 所有 GhostBlock 子命令处理器的通用接口。
 */
public interface ICommandHandler {
    /**
     * 处理命令逻辑。
     * @param sender 命令发送者
     * @param world 客户端世界（如果不需要，可以为 null，但通常需要）
     * @param args 命令参数
     * @throws CommandException 如果命令执行出错
     */
    void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException;

    /**
     * 提供 Tab 补全选项。
     * @param sender 命令发送者
     * @param args 命令参数
     * @param pos 目标方块位置 (可从 Minecraft.getMinecraft().objectMouseOver 获取，但此处简化为直接传入)
     * @return 补全建议列表
     */
    List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos);
}