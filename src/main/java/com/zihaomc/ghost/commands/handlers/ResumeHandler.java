package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.TaskSnapshot;
import com.zihaomc.ghost.commands.tasks.FillTask;
import com.zihaomc.ghost.commands.tasks.LoadTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理 /cgb resume 子命令的逻辑。
 */
public class ResumeHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.resume.usage"));
        }

        int taskId;
        try {
            taskId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_id", args[1]));
        }

        TaskSnapshot snapshot = CommandState.pausedTasks.get(taskId);
        if (snapshot == null) {
            // 检查任务是否仍在运行
            boolean isRunning = CommandState.activeFillTasks.stream().anyMatch(t -> t.getTaskId() == taskId);
            if (!isRunning) isRunning = CommandState.activeLoadTasks.stream().anyMatch(t -> t.getTaskId() == taskId);
            
            if (isRunning) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.resume.error.already_running", taskId));
            } else {
                throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_id", taskId));
            }
        }

        // 恢复任务
        if ("fill".equals(snapshot.type)) {
            FillTask newTask = new FillTask(world, snapshot.state, snapshot.remainingBlocks, snapshot.batchSize,
                    snapshot.saveToFile, snapshot.saveFileName, snapshot.sender, snapshot.taskId, snapshot.entriesToSaveForUserFile);
            CommandState.activeFillTasks.add(newTask);
            CommandState.pausedTasks.remove(taskId);
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.resume.success", taskId));
        } else if ("load".equals(snapshot.type)) {
            LoadTask newTask = new LoadTask(world, snapshot.remainingEntries, snapshot.batchSize, snapshot.sender, snapshot.taskId);
            CommandState.activeLoadTasks.add(newTask);
            CommandState.pausedTasks.remove(taskId);
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.resume.success", taskId));
        } else {
            CommandState.pausedTasks.remove(taskId);
            throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_type"));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        // 只补全已暂停的任务ID
        List<String> pausedIds = CommandState.pausedTasks.keySet().stream().map(String::valueOf).collect(Collectors.toList());
        return CommandBase.getListOfStringsMatchingLastWord(args, pausedIds);
    }
}