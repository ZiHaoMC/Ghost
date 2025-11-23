package com.zihaomc.ghost.features.ghostblock.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.UndoRecord;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.BlockStateProxy;
import com.zihaomc.ghost.features.ghostblock.tasks.ClearTask;
import com.zihaomc.ghost.features.ghostblock.tasks.FillTask;
import com.zihaomc.ghost.features.ghostblock.tasks.LoadTask;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class UndoHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (GhostBlockState.undoHistory.isEmpty()) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.undo.empty"));
        }

        int index = 1; 
        if (args.length > 1) {
            try {
                index = Integer.parseInt(args[1]);
                if (index <= 0 || index > GhostBlockState.undoHistory.size()) {
                    throw new CommandException(LangUtil.translate("ghostblock.commands.undo.invalid_index", GhostBlockState.undoHistory.size()));
                }
            } catch (NumberFormatException e) {
                throw new WrongUsageException(LangUtil.translate("ghostblock.commands.undo.usage.extended"));
            }
        }

        UndoRecord record = GhostBlockState.undoHistory.remove(index - 1);

        if (record.relatedTaskId != null) {
            cancelRelatedTask(record.relatedTaskId);
        }

        restoreUserFileBackups(sender, world, record);
        handleCoreUndo(sender, world, record);
        deleteUndoDataFile(record, world);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>();
            for (int i = 1; i <= GhostBlockState.undoHistory.size(); i++) {
                suggestions.add(String.valueOf(i));
            }
            return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
        }
        return Collections.emptyList();
    }
    
    private void cancelRelatedTask(int taskId) {
        boolean cancelled = false;
        synchronized (GhostBlockState.activeFillTasks) {
            for (FillTask task : GhostBlockState.activeFillTasks) {
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    cancelled = true;
                    break;
                }
            }
        }
        if (!cancelled) {
            synchronized (GhostBlockState.activeLoadTasks) {
                for (LoadTask task : GhostBlockState.activeLoadTasks) {
                    if (task.getTaskId() == taskId) {
                        task.cancel();
                        cancelled = true;
                        break;
                    }
                }
            }
        }
        if (!cancelled) {
            synchronized (GhostBlockState.activeClearTasks) {
                for (ClearTask task : GhostBlockState.activeClearTasks) {
                    if (task.getTaskId() == taskId) {
                        task.cancel();
                        cancelled = true;
                        break;
                    }
                }
            }
        }
        if (cancelled) {
            LogUtil.info("log.info.undo.taskCancelled", taskId);
        }
    }

    private void restoreUserFileBackups(ICommandSender sender, WorldClient world, UndoRecord record) {
        if (record.fileBackups != null && !record.fileBackups.isEmpty()) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_user_files"));
            for (Map.Entry<String, List<GhostBlockEntry>> entry : record.fileBackups.entrySet()) {
                String fileName = entry.getKey();
                List<GhostBlockEntry> backupEntries = entry.getValue();
                GhostBlockData.saveData(world, backupEntries, fileName, true);
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.user_file_restored", fileName));
            }
        }
    }

    private void handleCoreUndo(ICommandSender sender, WorldClient world, UndoRecord record) throws CommandException {
        File undoDataFile = GhostBlockData.getDataFile(world, record.undoFileName);
        List<GhostBlockEntry> entriesFromUndoFile = new ArrayList<>();
        if (undoDataFile.exists()) {
            entriesFromUndoFile = GhostBlockData.loadData(world, Collections.singletonList(record.undoFileName));
        }

        switch (record.operationType) {
            case SET:
                undoSetOperation(sender, world, entriesFromUndoFile);
                break;
            case CLEAR_BLOCK:
                undoClearOperation(sender, world, record, entriesFromUndoFile);
                break;
        }
    }
    
    private void undoSetOperation(ICommandSender sender, WorldClient world, List<GhostBlockEntry> entriesFromUndoFile) throws CommandException {
        if (entriesFromUndoFile.isEmpty()) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.error.data_file_empty"));
            return;
        }
        sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_blocks"));
        AtomicInteger restoredCount = new AtomicInteger();
        List<BlockPos> affectedPositions = new ArrayList<>();

        entriesFromUndoFile.forEach(entry -> {
            BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
            Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
            if (originalBlock != null) {
                try {
                    world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                    world.markBlockForUpdate(pos);
                    restoredCount.incrementAndGet();
                    affectedPositions.add(pos);
                } catch (Exception e) {
                    LogUtil.error("log.error.undo.restore.set.failed", pos, e.getMessage());
                    sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error.restore_failed", pos.getX(), pos.getY(), pos.getZ(), e.getMessage()));
                }
            } else {
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error_block_lookup", entry.originalBlockId, pos.getX(), pos.getY(), pos.getZ()));
            }
        });

        removeEntriesFromAutoClearFile(world, affectedPositions);
        sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.undo.success_set", restoredCount.get()));
    }
    
    private void undoClearOperation(ICommandSender sender, WorldClient world, UndoRecord record, List<GhostBlockEntry> entriesFromUndoFile) throws CommandException {
        if (record.undoFileName.startsWith("undo_clear_file_")) {
            if (record.fileBackups != null && !record.fileBackups.isEmpty()) {
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.undo.success_clear_file"));
            } else {
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.warning.no_files_to_restore"));
            }
        } else {
            if (entriesFromUndoFile.isEmpty()) {
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghost.commands.undo.error.data_file_empty_ghost"));
                return;
            }
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY, "ghost.commands.undo.restoring_ghost_blocks"));
            AtomicInteger restoredCount = new AtomicInteger();
            List<GhostBlockEntry> restoredGhostEntries = new ArrayList<>();

            entriesFromUndoFile.forEach(entry -> {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block ghostBlock = Block.getBlockFromName(entry.blockId);
                if (ghostBlock != null && ghostBlock != Block.getBlockFromName("minecraft:air")) {
                    try {
                        GhostBlockHelper.setGhostBlock(world, pos, new BlockStateProxy(Block.getIdFromBlock(ghostBlock), entry.metadata));
                        restoredCount.incrementAndGet();
                        restoredGhostEntries.add(entry);
                    } catch (Exception e) {
                        LogUtil.error("log.error.undo.restore.clear.failed", pos, e.getMessage());
                        sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED,"ghost.commands.undo.error.restore_ghost_failed", pos.getX(), pos.getY(), pos.getZ(), e.getMessage()));
                    }
                } else {
                    sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED,"ghost.commands.undo.error_block_lookup", entry.blockId, pos.getX(), pos.getY(), pos.getZ()));
                }
            });

            if (!restoredGhostEntries.isEmpty()) {
                String autoFileName = GhostBlockHelper.getAutoClearFileName(world);
                GhostBlockData.saveData(world, restoredGhostEntries, autoFileName, false);
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY,"ghost.commands.undo.auto_file_restored", restoredGhostEntries.size()));
            }
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghost.commands.undo.success_clear", restoredCount.get()));
        }
    }

    private void deleteUndoDataFile(UndoRecord record, WorldClient world) {
        File undoDataFile = GhostBlockData.getDataFile(world, record.undoFileName);
        if (undoDataFile.exists()) {
            if (!undoDataFile.delete()) {
                LogUtil.error("log.error.undo.deleteFile.failed", undoDataFile.getPath());
            } else {
                LogUtil.info("log.info.undo.deleteFile.success", undoDataFile.getPath());
            }
        } else {
            LogUtil.info("log.info.undo.deleteFile.notFound", record.undoFileName);
        }
    }
    
    private void removeEntriesFromAutoClearFile(World world, List<BlockPos> positionsToRemove) {
        if (positionsToRemove.isEmpty()) return;
        String autoFileName = GhostBlockHelper.getAutoClearFileName((WorldClient) world);
        GhostBlockData.removeEntriesFromFile(world, autoFileName, positionsToRemove);
        LogUtil.info("log.info.undo.autoClearFile.updated", autoFileName, positionsToRemove.size());
    }
}
