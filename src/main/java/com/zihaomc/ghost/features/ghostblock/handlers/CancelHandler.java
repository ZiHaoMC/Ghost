package com.zihaomc.ghost.features.ghostblock.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.TaskSnapshot;
import com.zihaomc.ghost.features.ghostblock.tasks.ClearTask;
import com.zihaomc.ghost.features.ghostblock.tasks.FillTask;
import com.zihaomc.ghost.features.ghostblock.tasks.LoadTask;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
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
                    successIds.size(), GhostBlockHelper.formatIdList(successIds));
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, successMsg));
        }

        if (!invalidIds.isEmpty()) {
            String errorMsg = LangUtil.translate("ghostblock.commands.cancel.invalid_ids",
                    GhostBlockHelper.formatIdList(invalidIds));
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED, errorMsg));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        List<String> taskIds = new ArrayList<>();
        synchronized(GhostBlockState.activeFillTasks) { GhostBlockState.activeFillTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
        synchronized(GhostBlockState.activeLoadTasks) { GhostBlockState.activeLoadTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
        synchronized(GhostBlockState.activeClearTasks) { GhostBlockState.activeClearTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
        taskIds.addAll(GhostBlockState.pausedTasks.keySet().stream().map(String::valueOf).collect(Collectors.toList()));
        
        List<String> existingTaskIds = Arrays.asList(args).subList(1, args.length - 1);
        taskIds.removeIf(existingTaskIds::contains);

        return CommandBase.getListOfStringsMatchingLastWord(args, taskIds);
    }
    
    private boolean cancelTask(int taskId) {
        boolean found = false;

        synchronized (GhostBlockState.activeFillTasks) {
            Iterator<FillTask> fillIter = GhostBlockState.activeFillTasks.iterator();
            while (fillIter.hasNext()) {
                FillTask task = fillIter.next();
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    GhostBlockState.pausedTasks.put(taskId, new TaskSnapshot(task));
                    fillIter.remove();
                    found = true;
                    LogUtil.info("log.info.task.cancel.fill", taskId);
                    return true;
                }
            }
        }

        synchronized (GhostBlockState.activeLoadTasks) {
            Iterator<LoadTask> loadIter = GhostBlockState.activeLoadTasks.iterator();
            while (loadIter.hasNext()) {
                LoadTask task = loadIter.next();
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    GhostBlockState.pausedTasks.put(taskId, new TaskSnapshot(task));
                    loadIter.remove();
                    found = true;
                    LogUtil.info("log.info.task.cancel.load", taskId);
                    return true;
                }
            }
        }

        synchronized (GhostBlockState.activeClearTasks) {
            Iterator<ClearTask> clearIter = GhostBlockState.activeClearTasks.iterator();
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

        if (GhostBlockState.pausedTasks.containsKey(taskId)) {
            GhostBlockState.pausedTasks.remove(taskId);
            found = true;
        }

        return found;
    }
}