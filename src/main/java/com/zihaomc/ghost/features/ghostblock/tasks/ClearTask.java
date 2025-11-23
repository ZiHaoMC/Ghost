package com.zihaomc.ghost.features.ghostblock.tasks;

import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.io.File;
import java.util.List;

/**
 * 代表一个后台批量清除幽灵方块（恢复为原始方块）的任务。
 */
public class ClearTask {
    private final WorldClient world;
    private final List<GhostBlockData.GhostBlockEntry> entries;
    private int currentIndex;
    private final int batchSize;
    private final ICommandSender sender;
    private long lastUpdateTime = 0;
    private float lastReportedPercent = -1;
    private volatile boolean cancelled = false;
    private final int taskId;
    private final File autoClearFile;

    public ClearTask(WorldClient world, List<GhostBlockData.GhostBlockEntry> entries,
                     int batchSize, ICommandSender sender, int taskId,
                     File autoClearFile) {
        this.world = world;
        this.entries = entries;
        this.batchSize = batchSize;
        this.sender = sender;
        this.currentIndex = 0;
        this.taskId = taskId;
        this.autoClearFile = autoClearFile;
    }

    public boolean processBatch() {
        if (cancelled) {
            return true;
        }

        int endIndex = Math.min(currentIndex + batchSize, entries.size());

        for (int i = currentIndex; i < endIndex; i++) {
            if (cancelled) break; 
            
            GhostBlockData.GhostBlockEntry entry = entries.get(i);
            BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
            Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
            if (originalBlock != null) { 
                try {
                    world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                    world.markBlockForUpdate(pos);
                } catch (Exception e) {
                    LogUtil.error("log.error.clear.task.restore.failed", pos, e.getMessage());
                }
            } else {
                LogUtil.error("log.error.clear.task.originalBlock.notFound", entry.originalBlockId, pos);
            }
        }
        currentIndex = endIndex;

        if (cancelled) return true;

        boolean finished = currentIndex >= entries.size();

        if (finished) {
            sendFinalProgress();
        } else {
            float currentPercent = (currentIndex * 100.0f) / entries.size();
            sendProgressIfNeeded(currentPercent, false);
        }
        return finished;
    }

    private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
        currentPercent = Math.round(currentPercent * 10) / 10.0f;
        boolean shouldSend = forceSend || Math.abs(currentPercent - lastReportedPercent) >= 0.1f || System.currentTimeMillis() - lastUpdateTime > 1000;

        if (shouldSend) {
            String progressBar = GhostBlockHelper.createProgressBar(currentPercent, 10);
            IChatComponent message = GhostBlockHelper.createProgressMessage("ghostblock.commands.clear.progress", (int) currentPercent, progressBar);
            sender.addChatMessage(message);
            lastReportedPercent = currentPercent;
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    private void sendFinalProgress() {
        sendProgressIfNeeded(100.0f, true); 
        
        if (!cancelled) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(GhostBlockHelper.FINISH_COLOR, "ghostblock.commands.clear.finish", entries.size()));
        }

        if (autoClearFile != null && autoClearFile.exists()) {
            boolean deleted = autoClearFile.delete();
            LogUtil.info("log.info.clear.task.file.deleted", autoClearFile.getName(), deleted);
            if (!deleted && !cancelled) {
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED, "ghostblock.commands.clear.block.delete_failed", autoClearFile.getName()));
            }
        } else if (autoClearFile != null) {
            LogUtil.info("log.info.clear.task.file.notFound", autoClearFile.getName());
        }
    }

    public void cancel() {
        this.cancelled = true;
    }

    public int getTaskId() {
        return taskId;
    }
}