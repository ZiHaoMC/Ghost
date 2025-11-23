package com.zihaomc.ghost.features.ghostblock.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.TaskSnapshot;
import com.zihaomc.ghost.features.ghostblock.tasks.FillTask;
import com.zihaomc.ghost.features.ghostblock.tasks.LoadTask;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
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

        TaskSnapshot snapshot = GhostBlockState.pausedTasks.get(taskId);
        if (snapshot == null) {
            boolean isRunning = GhostBlockState.activeFillTasks.stream().anyMatch(t -> t.getTaskId() == taskId);
            if (!isRunning) isRunning = GhostBlockState.activeLoadTasks.stream().anyMatch(t -> t.getTaskId() == taskId);
            
            if (isRunning) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.resume.error.already_running", taskId));
            } else {
                throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_id", taskId));
            }
        }

        if ("fill".equals(snapshot.type)) {
            FillTask newTask = new FillTask(world, snapshot.state, snapshot.remainingBlocks, snapshot.batchSize,
                    snapshot.saveToFile, snapshot.saveFileName, snapshot.sender, snapshot.taskId, snapshot.entriesToSaveForUserFile);
            GhostBlockState.activeFillTasks.add(newTask);
            GhostBlockState.pausedTasks.remove(taskId);
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.resume.success", taskId));
        } else if ("load".equals(snapshot.type)) {
            LoadTask newTask = new LoadTask(world, snapshot.remainingEntries, snapshot.batchSize, snapshot.sender, snapshot.taskId);
            GhostBlockState.activeLoadTasks.add(newTask);
            GhostBlockState.pausedTasks.remove(taskId);
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.resume.success", taskId));
        } else {
            GhostBlockState.pausedTasks.remove(taskId);
            throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_type"));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        List<String> pausedIds = GhostBlockState.pausedTasks.keySet().stream().map(String::valueOf).collect(Collectors.toList());
        return CommandBase.getListOfStringsMatchingLastWord(args, pausedIds);
    }
}