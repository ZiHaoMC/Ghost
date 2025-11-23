package com.zihaomc.ghost.features.ghostblock.tasks;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.BlockStateProxy;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.*;

/**
 * 代表一个后台批量填充幽灵方块的任务。
 */
public class FillTask {
    final WorldClient world;
    final BlockStateProxy state;
    final List<BlockPos> remainingBlocks;
    final int batchSize;
    final boolean saveToFile;
    final String saveFileName;
    final ICommandSender sender;
    private final int totalBlocks;
    private int processedCount = 0;
    private long lastUpdateTime = 0;
    private float lastReportedPercent = -1;
    private volatile boolean cancelled = false;
    private final int taskId;
    private final List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile;
    private final Set<BlockPos> previouslyWaitingForLoadOrProximity = new HashSet<>();
    private static final double TASK_PLACEMENT_PROXIMITY_SQ = 32.0 * 32.0;

    public FillTask(WorldClient world, BlockStateProxy state, List<BlockPos> allBlocks,
                    int batchSize, boolean saveToFile, String saveFileName, ICommandSender sender, int taskId,
                    List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile) {
        this.world = world;
        this.state = state;
        this.remainingBlocks = new ArrayList<>(allBlocks);
        this.totalBlocks = this.remainingBlocks.size();
        this.batchSize = Math.max(1, batchSize);
        this.saveToFile = saveToFile;
        this.saveFileName = saveFileName;
        this.sender = sender;
        this.taskId = taskId;
        this.entriesToSaveForUserFile = entriesToSaveForUserFile != null ? new ArrayList<>(entriesToSaveForUserFile) : new ArrayList<>();
        this.processedCount = 0;
        LogUtil.info("log.info.task.fill.init", taskId, this.totalBlocks, this.batchSize);
    }

    public boolean processBatch() {
        if (cancelled) {
            return true;
        }

        Block block = Block.getBlockById(state.blockId);
        if (block == null) {
            if (processedCount == 0 && !cancelled) {
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED, "ghostblock.commands.error.invalid_block"));
                LogUtil.error("log.error.task.fill.invalidBlock", taskId, state.blockId);
                this.cancel();
            }
            return true;
        }

        int attemptsThisTick = 0;
        int successfullyProcessedThisTick = 0;
        Iterator<BlockPos> iterator = remainingBlocks.iterator();

        EntityPlayer currentPlayer = (sender instanceof EntityPlayer) ? (EntityPlayer) sender : null;
        if (currentPlayer != null && (!currentPlayer.isEntityAlive() || currentPlayer.worldObj != this.world)) {
            LogUtil.info("log.info.task.fill.playerInvalid", taskId);
            this.cancel();
            return true;
        }

        while (iterator.hasNext() && attemptsThisTick < batchSize) {
            if (cancelled) break;
            
            BlockPos pos = iterator.next();
            attemptsThisTick++;

            boolean canPlaceNow = checkPlacementConditions(pos, currentPlayer);

            if (canPlaceNow) {
                if (previouslyWaitingForLoadOrProximity.remove(pos)) {
                    LogUtil.debug("log.info.task.fill.posReady", taskId, pos);
                }
                try {
                    IBlockState blockStateToSet = block.getStateFromMeta(state.metadata);
                    world.setBlockState(pos, blockStateToSet, 3);
                    iterator.remove();
                    successfullyProcessedThisTick++;
                    processedCount++;
                } catch (Exception e) {
                    LogUtil.printStackTrace("log.warn.task.fill.placeError", e, taskId, pos, e.getMessage());
                    iterator.remove();
                }
            } else {
                if (pos.getY() < 0 || pos.getY() >= 256) {
                    LogUtil.debug("log.info.task.fill.posInvalidY", taskId, pos.getY(), pos);
                    iterator.remove();
                } else {
                    break;
                }
            }
        }

        if (cancelled) {
            return true;
        }

        boolean finished = remainingBlocks.isEmpty();

        if (finished) {
            LogUtil.info("log.info.task.fill.complete.log", taskId, processedCount, totalBlocks);
            sendFinalProgress();
        } else {
            float currentPercent = (totalBlocks == 0) ? 100.0f : (processedCount * 100.0f) / totalBlocks;
            boolean forceUpdate = successfullyProcessedThisTick > 0;
            sendProgressIfNeeded(currentPercent, forceUpdate);
        }
        return finished;
    }

    private boolean checkPlacementConditions(BlockPos pos, EntityPlayer player) {
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return false;
        }
        if (GhostBlockHelper.isBlockSectionReady(world, pos)) {
            if (player != null) {
                if (player.getDistanceSqToCenter(pos) <= TASK_PLACEMENT_PROXIMITY_SQ) {
                    return true;
                } else {
                    if (previouslyWaitingForLoadOrProximity.add(pos)) {
                        LogUtil.debug("log.info.task.fill.posWaiting", taskId, pos, "玩家距离远");
                    }
                    return false;
                }
            }
            return true;
        }
        if (previouslyWaitingForLoadOrProximity.add(pos)) {
            LogUtil.debug("log.info.task.fill.posWaiting", taskId, pos, "isBlockLoaded=false");
        }
        return false;
    }

    private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
        if (totalBlocks == 0) currentPercent = 100.0f;
        currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent));
        currentPercent = Math.round(currentPercent * 10) / 10.0f;

        boolean shouldSend = forceSend || Math.abs(currentPercent - lastReportedPercent) >= 0.1f || System.currentTimeMillis() - lastUpdateTime > 1000;

        if (shouldSend && currentPercent <= 100.0f) {
            String progressBar = GhostBlockHelper.createProgressBar(currentPercent, 10);
            IChatComponent message = GhostBlockHelper.createProgressMessage("ghostblock.commands.fill.progress", (int) Math.floor(currentPercent), progressBar);
            
            if (sender instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) sender;
                if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                    if(!cancelled) LogUtil.info("log.info.task.fill.playerLeft.progress", taskId);
                    this.cancel();
                    return;
                }
            }
            try {
                sender.addChatMessage(message);
            } catch (Exception e) {
                LogUtil.error("log.error.task.fill.progress.sendFailed", taskId, e.getMessage());
            }
            lastReportedPercent = currentPercent;
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    private void sendFinalProgress() {
        if (lastReportedPercent < 100.0f && !cancelled) {
            sendProgressIfNeeded(100.0f, true);
        }

        if (saveToFile && !cancelled) {
            String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
            if (this.entriesToSaveForUserFile != null && !this.entriesToSaveForUserFile.isEmpty()) {
                LogUtil.debug("log.info.task.fill.save.userFile", taskId, this.entriesToSaveForUserFile.size(), actualSaveFileName);
                GhostBlockData.saveData(world, this.entriesToSaveForUserFile, actualSaveFileName, false);
                String displayName = (saveFileName == null) ? LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world)) : saveFileName;
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.save.success", displayName));
            } else {
                LogUtil.warn("log.warn.task.fill.save.noEntries", taskId);
            }
        }

        if (!cancelled) {
            String finishKey = (totalBlocks == 1 && processedCount <= 1) ? "ghostblock.commands.fill.finish_single" : "ghostblock.commands.fill.finish";
            sender.addChatMessage(GhostBlockHelper.formatMessage(GhostBlockHelper.FINISH_COLOR, finishKey, processedCount));
        } else {
            LogUtil.info("log.info.task.fill.cancelled", taskId);
        }
    }

    public void cancel() {
        if (!this.cancelled) {
            LogUtil.info("log.info.task.fill.markedCancelled", taskId);
            this.cancelled = true;
            previouslyWaitingForLoadOrProximity.clear();
        }
    }

    public int getTaskId() { return taskId; }
    public List<BlockPos> getRemainingBlocks() { return remainingBlocks; }
    public int getBatchSize() { return batchSize; }
    public int getTotalBlocks() { return totalBlocks; }
    public BlockStateProxy getState() { return state; }
    public boolean isSaveToFile() { return saveToFile; }
    public String getSaveFileName() { return saveFileName; }
    public ICommandSender getSender() { return sender; }
    public List<GhostBlockData.GhostBlockEntry> getEntriesToSaveForUserFile() { return entriesToSaveForUserFile; }
}
