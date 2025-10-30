package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.UndoRecord;
import com.zihaomc.ghost.commands.data.CommandState.BlockStateProxy;
import com.zihaomc.ghost.commands.tasks.ClearTask;
import com.zihaomc.ghost.commands.tasks.FillTask;
import com.zihaomc.ghost.commands.tasks.LoadTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 处理 /cgb undo 子命令的逻辑。
 */
public class UndoHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (CommandState.undoHistory.isEmpty()) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.undo.empty"));
        }
        UndoRecord record = CommandState.undoHistory.pop();

        // [新增] 核心逻辑：如果此撤销操作关联了一个仍在运行的任务，强制终止它。
        if (record.relatedTaskId != null) {
            cancelRelatedTask(record.relatedTaskId);
        }

        // 1. 恢复用户文件备份
        restoreUserFileBackups(sender, world, record);

        // 2. 处理核心撤销逻辑
        handleCoreUndo(sender, world, record);

        // 3. 删除撤销数据文件
        deleteUndoDataFile(record, world);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return Collections.emptyList();
    }
    
    // --- 辅助方法 ---

    /**
     * 强制终止与撤销操作关联的任务。
     * 注意：这里只调用 .cancel()，任务会在下一次Tick时自动从活动列表中移除，不会进入暂停列表。
     */
    private void cancelRelatedTask(int taskId) {
        boolean cancelled = false;
        // 检查 FillTask
        synchronized (CommandState.activeFillTasks) {
            for (FillTask task : CommandState.activeFillTasks) {
                if (task.getTaskId() == taskId) {
                    task.cancel();
                    cancelled = true;
                    break;
                }
            }
        }
        // 检查 LoadTask
        if (!cancelled) {
            synchronized (CommandState.activeLoadTasks) {
                for (LoadTask task : CommandState.activeLoadTasks) {
                    if (task.getTaskId() == taskId) {
                        task.cancel();
                        cancelled = true;
                        break;
                    }
                }
            }
        }
        // 检查 ClearTask
        if (!cancelled) {
            synchronized (CommandState.activeClearTasks) {
                for (ClearTask task : CommandState.activeClearTasks) {
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
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_user_files"));
            for (Map.Entry<String, List<GhostBlockEntry>> entry : record.fileBackups.entrySet()) {
                String fileName = entry.getKey();
                List<GhostBlockEntry> backupEntries = entry.getValue();
                GhostBlockData.saveData(world, backupEntries, fileName, true);
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.user_file_restored", fileName));
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
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.error.data_file_empty"));
            return;
        }
        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_blocks"));
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
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error.restore_failed", pos.getX(), pos.getY(), pos.getZ(), e.getMessage()));
                }
            } else {
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error_block_lookup", entry.originalBlockId, pos.getX(), pos.getY(), pos.getZ()));
            }
        });

        removeEntriesFromAutoClearFile(world, affectedPositions);
        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.undo.success_set", restoredCount.get()));
    }
    
    private void undoClearOperation(ICommandSender sender, WorldClient world, UndoRecord record, List<GhostBlockEntry> entriesFromUndoFile) throws CommandException {
        if (record.undoFileName.startsWith("undo_clear_file_")) {
            if (record.fileBackups != null && !record.fileBackups.isEmpty()) {
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.undo.success_clear_file"));
            } else {
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.warning.no_files_to_restore"));
            }
        } else {
            if (entriesFromUndoFile.isEmpty()) {
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.error.data_file_empty_ghost"));
                return;
            }
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_ghost_blocks"));
            AtomicInteger restoredCount = new AtomicInteger();
            List<GhostBlockEntry> restoredGhostEntries = new ArrayList<>();

            entriesFromUndoFile.forEach(entry -> {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block ghostBlock = Block.getBlockFromName(entry.blockId);
                if (ghostBlock != null && ghostBlock != Block.getBlockFromName("minecraft:air")) {
                    try {
                        CommandHelper.setGhostBlock(world, pos, new BlockStateProxy(Block.getIdFromBlock(ghostBlock), entry.metadata));
                        restoredCount.incrementAndGet();
                        restoredGhostEntries.add(entry);
                    } catch (Exception e) {
                        LogUtil.error("log.error.undo.restore.clear.failed", pos, e.getMessage());
                        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error.restore_ghost_failed", pos.getX(), pos.getY(), pos.getZ(), e.getMessage()));
                    }
                } else {
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error_block_lookup", entry.blockId, pos.getX(), pos.getY(), pos.getZ()));
                }
            });

            if (!restoredGhostEntries.isEmpty()) {
                String autoFileName = CommandHelper.getAutoClearFileName(world);
                GhostBlockData.saveData(world, restoredGhostEntries, autoFileName, false);
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.auto_file_restored", restoredGhostEntries.size()));
            }
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.undo.success_clear", restoredCount.get()));
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
        String autoFileName = CommandHelper.getAutoClearFileName((WorldClient) world);
        GhostBlockData.removeEntriesFromFile(world, autoFileName, positionsToRemove);
        LogUtil.info("log.info.undo.autoClearFile.updated", autoFileName, positionsToRemove.size());
    }
}