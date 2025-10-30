package com.zihaomc.ghost.commands.tasks;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState.BlockStateProxy;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.*;

/**
 * 代表一个后台批量填充幽灵方块的任务。
 * 这个任务是区块感知和玩家距离感知的，只会在条件满足时放置方块。
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
    // 跟踪哪些位置之前是因为 (isBlockLoaded=false 或 距离远) 而未处理的
    private final Set<BlockPos> previouslyWaitingForLoadOrProximity = new HashSet<>();
    private static final double TASK_PLACEMENT_PROXIMITY_SQ = 32.0 * 32.0; // 任务执行时，玩家需要在此距离平方内才放置 (32格)

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

    /**
     * 由 Tick 事件循环调用，处理一个批次的方块放置。
     * @return 如果任务完成或被取消，则返回 true。
     */
    public boolean processBatch() {
        if (cancelled) {
            return true;
        }

        Block block = Block.getBlockById(state.blockId);
        if (block == null || block == Blocks.air) {
            if (processedCount == 0 && !cancelled) {
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED, "ghostblock.commands.error.invalid_block"));
                LogUtil.error("log.error.task.fill.invalidBlock", taskId, state.blockId);
                this.cancel();
            }
            return true;
        }

        int attemptsThisTick = 0;
        int successfullyProcessedThisTick = 0;
        Iterator<BlockPos> iterator = remainingBlocks.iterator();

        // 获取当前玩家实例 (如果 sender 是玩家)
        EntityPlayer currentPlayer = (sender instanceof EntityPlayer) ? (EntityPlayer) sender : null;
        if (currentPlayer != null && (!currentPlayer.isEntityAlive() || currentPlayer.worldObj != this.world)) {
            LogUtil.info("log.info.task.fill.playerInvalid", taskId);
            this.cancel();
            return true;
        }

        while (iterator.hasNext() && attemptsThisTick < batchSize) {
            if (cancelled) break; // 在循环内部也检查取消状态
            
            BlockPos pos = iterator.next();
            attemptsThisTick++;

            // 检查方块是否可以立即放置
            boolean canPlaceNow = checkPlacementConditions(pos, currentPlayer);

            if (canPlaceNow) {
                // 如果之前在等待，现在条件满足了，可以记录一下日志
                if (previouslyWaitingForLoadOrProximity.remove(pos)) {
                    LogUtil.debug("log.info.task.fill.posReady", taskId, pos);
                }

                try {
                    // 执行放置
                    IBlockState blockStateToSet = block.getStateFromMeta(state.metadata);
                    world.setBlockState(pos, blockStateToSet, 3);
                    iterator.remove();
                    successfullyProcessedThisTick++;
                    processedCount++;
                } catch (Exception e) {
                    LogUtil.printStackTrace("log.warn.task.fill.placeError", e, taskId, pos, e.getMessage());
                    iterator.remove(); // 即使失败也移除，避免任务卡住
                }
            } else {
                // 如果Y轴无效，直接从任务中移除
                if (pos.getY() < 0 || pos.getY() >= 256) {
                    LogUtil.debug("log.info.task.fill.posInvalidY", taskId, pos.getY(), pos);
                    iterator.remove();
                } else {
                    // 如果是因为区块未加载或距离远，则保留并在下次tick重试
                    break; // 中断当前批次，让其他任务有机会执行或等待玩家移动
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

    /**
     * 检查给定位置的方块是否满足放置条件（Y轴有效、区块加载、玩家距离近）。
     * @param pos 要检查的位置
     * @param player 玩家实体
     * @return 如果可以放置则返回 true
     */
    private boolean checkPlacementConditions(BlockPos pos, EntityPlayer player) {
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return false; // Y轴无效
        }
        if (CommandHelper.isBlockSectionReady(world, pos)) {
            // 区块已加载，检查距离
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
            return true; // 没有玩家上下文，但区块已加载，允许放置
        }
        if (previouslyWaitingForLoadOrProximity.add(pos)) {
            LogUtil.debug("log.info.task.fill.posWaiting", taskId, pos, "isBlockLoaded=false");
        }
        return false;
    }

    /**
     * 根据需要发送进度消息，避免刷屏。
     * @param currentPercent 当前进度百分比
     * @param forceSend 是否强制发送
     */
    private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
        if (totalBlocks == 0) currentPercent = 100.0f;
        currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent));
        currentPercent = Math.round(currentPercent * 10) / 10.0f;

        boolean shouldSend = forceSend || Math.abs(currentPercent - lastReportedPercent) >= 0.1f || System.currentTimeMillis() - lastUpdateTime > 1000;

        if (shouldSend && currentPercent <= 100.0f) {
            String progressBar = CommandHelper.createProgressBar(currentPercent, 10);
            IChatComponent message = CommandHelper.createProgressMessage("ghostblock.commands.fill.progress", (int) Math.floor(currentPercent), progressBar);
            
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

    /**
     * 发送最终的100%进度，执行保存逻辑，并发送完成消息。
     */
    private void sendFinalProgress() {
        if (lastReportedPercent < 100.0f && !cancelled) {
            sendProgressIfNeeded(100.0f, true);
        }

        // 保存文件逻辑 (仅当任务未取消时)
        if (saveToFile && !cancelled) {
            String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
            if (this.entriesToSaveForUserFile != null && !this.entriesToSaveForUserFile.isEmpty()) {
                LogUtil.debug("log.info.task.fill.save.userFile", taskId, this.entriesToSaveForUserFile.size(), actualSaveFileName);
                GhostBlockData.saveData(world, this.entriesToSaveForUserFile, actualSaveFileName, false);
                String displayName = (saveFileName == null) ? LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world)) : saveFileName;
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.save.success", displayName));
            } else {
                LogUtil.warn("log.warn.task.fill.save.noEntries", taskId);
            }
        }

        // 发送完成消息 (仅当任务未取消时)
        if (!cancelled) {
            String finishKey = (totalBlocks == 1 && processedCount <= 1) ? "ghostblock.commands.fill.finish_single" : "ghostblock.commands.fill.finish";
            sender.addChatMessage(CommandHelper.formatMessage(CommandHelper.FINISH_COLOR, finishKey, processedCount));
        } else {
            LogUtil.info("log.info.task.fill.cancelled", taskId);
        }
    }

    /**
     * 标记任务为取消。
     */
    public void cancel() {
        if (!this.cancelled) {
            LogUtil.info("log.info.task.fill.markedCancelled", taskId);
            this.cancelled = true;
            previouslyWaitingForLoadOrProximity.clear();
        }
    }

    // --- Getters for TaskSnapshot ---
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