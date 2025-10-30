package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.TaskSnapshot;
import com.zihaomc.ghost.commands.tasks.ClearTask;
import com.zihaomc.ghost.commands.tasks.FillTask;
import com.zihaomc.ghost.commands.tasks.LoadTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 处理 /cgb cancel 子命令的逻辑。
 */
public class CancelHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cancel.usage"));
        }

        List<Integer> successIds = new ArrayList<>();
        List<String> invalidIds = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String taskIdStr = args[i];
            try {
                int taskId = Integer.parseInt(taskIdStr);
                boolean found = cancelTask(taskId);
                if (found) {
                    successIds.add(taskId);
                } else {
                    invalidIds.add(taskIdStr);
                }
            } catch (NumberFormatException e) {
                invalidIds.add(taskIdStr);
            }
        }

        if (!successIds.isEmpty()) {
            String successMsg = LangUtil.translate("ghostblock.commands.cancel.success.multi",
                    successIds.size(), CommandHelper.formatIdList(successIds));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, successMsg));
        }

        if (!invalidIds.isEmpty()) {
            String errorMsg = LangUtil.translate("ghostblock.commands.cancel.invalid_ids",
                    CommandHelper.formatIdList(invalidIds));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED, errorMsg));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        // 补全正在运行或已暂停的任务ID
        List<String> taskIds = new ArrayList<>();
        synchronized(CommandState.activeFillTasks) { CommandState.activeFillTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
        synchronized(CommandState.activeLoadTasks) { CommandState.activeLoadTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
        synchronized(CommandState.activeClearTasks) { CommandState.activeClearTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
        taskIds.addAll(CommandState.pausedTasks.keySet().stream().map(String::valueOf).collect(Collectors.toList()));
        
        List<String> existingTaskIds = Arrays.asList(args).subList(1, args.length - 1);
        taskIds.removeIf(existingTaskIds::contains);

        return CommandBase.getListOfStringsMatchingLastWord(args, taskIds);
    }
    
    /**
     * 尝试取消指定 ID 的任务，并将其移动到暂停列表（如果可恢复）。
     * @param taskId 任务ID
     * @return 如果找到并处理了任务则为 true
     */
    private boolean cancelTask(int taskId) {
        boolean found = false;

        // 取消 FillTask
        synchronized (CommandState.activeFillTasks) {
            Iterator<FillTask> fillIter = CommandState.activeFillTasks.iterator();
            while (fillIter.hasNext()) {
                FillTask task = fillIter.next();
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    CommandState.pausedTasks.put(taskId, new TaskSnapshot(task));
                    fillIter.remove();
                    found = true;
                    LogUtil.info("log.info.task.cancel.fill", taskId);
                    return true;
                }
            }
        }

        // 取消 LoadTask
        synchronized (CommandState.activeLoadTasks) {
            Iterator<LoadTask> loadIter = CommandState.activeLoadTasks.iterator();
            while (loadIter.hasNext()) {
                LoadTask task = loadIter.next();
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    CommandState.pausedTasks.put(taskId, new TaskSnapshot(task));
                    loadIter.remove();
                    found = true;
                    LogUtil.info("log.info.task.cancel.load", taskId);
                    return true;
                }
            }
        }

        // 取消 ClearTask (不可恢复)
        synchronized (CommandState.activeClearTasks) {
            Iterator<ClearTask> clearIter = CommandState.activeClearTasks.iterator();
            while (clearIter.hasNext()) {
                ClearTask task = clearIter.next();
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    clearIter.remove();
                    found = true;
                    LogUtil.info("log.info.task.cancel.clear", taskId);
                    return true;
                }
            }
        }

        // 检查是否在暂停列表中，如果是，也认为取消成功
        if (CommandState.pausedTasks.containsKey(taskId)) {
            CommandState.pausedTasks.remove(taskId);
            found = true;
        }

        return found;
    }
}