package com.zihaomc.ghost.commands.tasks;

import com.zihaomc.ghost.LangUtil;
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
 * 代表一个后台批量从文件加载幽灵方块的任务。
 * 这个任务是区块感知和玩家距离感知的。
 */
public class LoadTask {
    private volatile boolean cancelled = false;
    private final WorldClient world;
    private final List<GhostBlockData.GhostBlockEntry> entries;
    private int currentIndex;
    private final int batchSize;
    private final ICommandSender sender;
    private long lastUpdateTime = 0;
    private float lastReportedPercent = -1;
    private final int taskId;
    // 跟踪哪些索引之前是因为 (isBlockLoaded=false 或 距离远) 而未处理的
    private final Set<Integer> previouslyWaitingForLoadOrProximityIndices = new HashSet<>();
    private static final double TASK_PLACEMENT_PROXIMITY_SQ = 32.0 * 32.0; // 32格

    public LoadTask(WorldClient world, List<GhostBlockData.GhostBlockEntry> entriesToLoad, int batchSize,
                    ICommandSender sender, int taskId) {
        this.world = world;
        this.entries = entriesToLoad != null ? new ArrayList<>(entriesToLoad) : new ArrayList<>();
        this.batchSize = Math.max(1, batchSize);
        this.sender = sender;
        this.currentIndex = 0;
        this.taskId = taskId;
        LogUtil.info("log.info.task.load.init", taskId, this.entries.size(), this.batchSize);
    }

    /**
     * 由 Tick 事件循环调用，处理一个批次的加载。
     * @return 如果任务完成或被取消，则返回 true。
     */
    public boolean processBatch() {
        if (cancelled || entries.isEmpty() || currentIndex >= entries.size()) {
            if (!cancelled && !entries.isEmpty() && lastReportedPercent < 100.0f) {
                sendFinalProgress(); // 确保在任务结束时发送最终消息
            }
            return true;
        }

        int successfullyProcessedThisTick = 0;
        EntityPlayer currentPlayer = (sender instanceof EntityPlayer) ? (EntityPlayer) sender : null;
        if (currentPlayer != null && (!currentPlayer.isEntityAlive() || currentPlayer.worldObj != this.world)) {
            LogUtil.info("log.info.task.load.playerInvalid", taskId);
            this.cancel();
            return true;
        }

        // 批次内迭代
        for (int i = 0; i < batchSize && currentIndex < entries.size(); ) {
            if (cancelled) break;

            GhostBlockData.GhostBlockEntry entry = entries.get(currentIndex);
            BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);

            // 检查放置条件
            boolean canPlaceNow = checkPlacementConditions(pos, currentPlayer, currentIndex);
            
            if (canPlaceNow) {
                Block block = Block.getBlockFromName(entry.blockId);
                if (block != null && block != Blocks.air) {
                    try {
                        IBlockState blockStateToSet = block.getStateFromMeta(entry.metadata);
                        world.setBlockState(pos, blockStateToSet, 3);
                        successfullyProcessedThisTick++;
                    } catch (Exception e) {
                        LogUtil.printStackTrace("log.warn.task.load.placeError", e, taskId, pos, currentIndex, e.getMessage());
                    }
                } else {
                    LogUtil.warn("log.warn.task.load.invalidBlock", taskId, entry.blockId, pos, currentIndex);
                }
                currentIndex++;
                i++; // 增加批次内处理计数
            } else {
                // Y轴无效，跳过
                if (pos.getY() < 0 || pos.getY() >= 256) {
                    LogUtil.debug("log.info.task.load.posInvalidY", taskId, pos.getY(), pos, currentIndex);
                    currentIndex++;
                    i++;
                } else {
                    // 等待加载或距离，中断批次
                    break;
                }
            }
        }

        if (cancelled) return true;

        boolean finished = currentIndex >= entries.size();

        if (finished) {
            sendFinalProgress();
        } else {
            float currentPercent = entries.isEmpty() ? 100.0f : (currentIndex * 100.0f) / entries.size();
            boolean forceUpdate = successfullyProcessedThisTick > 0;
            sendProgressIfNeeded(currentPercent, forceUpdate);
        }
        return finished;
    }
    
    /**
     * 检查给定位置的方块是否满足放置条件。
     * @param pos 要检查的位置
     * @param player 玩家实体
     * @param index 当前条目的索引
     * @return 如果可以放置则返回 true
     */
    private boolean checkPlacementConditions(BlockPos pos, EntityPlayer player, int index) {
        if (pos.getY() < 0 || pos.getY() >= 256) {
            return false;
        }
        if (CommandHelper.isBlockSectionReady(world, pos)) {
            if (player != null && player.getDistanceSqToCenter(pos) > TASK_PLACEMENT_PROXIMITY_SQ) {
                if (previouslyWaitingForLoadOrProximityIndices.add(index)) {
                    LogUtil.debug("log.info.task.load.posWaiting", taskId, pos, index, "玩家距离远");
                }
                return false;
            }
            previouslyWaitingForLoadOrProximityIndices.remove(index);
            return true;
        }
        if (previouslyWaitingForLoadOrProximityIndices.add(index)) {
            LogUtil.debug("log.info.task.load.posWaiting", taskId, pos, index, "isBlockLoaded=false");
        }
        return false;
    }

    /**
     * 根据需要发送进度消息。
     * @param currentPercent 当前进度
     * @param forceSend 是否强制发送
     */
    private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
        if (entries.isEmpty()) currentPercent = 100.0f;
        currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent));
        currentPercent = Math.round(currentPercent * 10) / 10.0f;

        boolean shouldSend = forceSend || Math.abs(currentPercent - lastReportedPercent) >= 0.1f || System.currentTimeMillis() - lastUpdateTime > 1000;

        if (shouldSend && currentPercent <= 100.0f) {
            String progressBar = CommandHelper.createProgressBar(currentPercent, 10);
            IChatComponent message = CommandHelper.createProgressMessage("ghostblock.commands.load.progress", (int) Math.floor(currentPercent), progressBar);
            
            if (sender instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) sender;
                if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                    if(!cancelled) LogUtil.info("log.info.task.load.playerLeft.progress", taskId);
                    this.cancel();
                    return;
                }
            }
            try {
                sender.addChatMessage(message);
            } catch (Exception e) {
                LogUtil.error("log.error.task.load.progress.sendFailed", taskId, e.getMessage());
            }
            lastReportedPercent = currentPercent;
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    /**
     * 发送最终进度和完成消息。
     */
    private void sendFinalProgress() {
        if (lastReportedPercent < 100.0f && !cancelled) {
            sendProgressIfNeeded(100.0f, true);
        }
        if (!cancelled) {
            sender.addChatMessage(CommandHelper.formatMessage(CommandHelper.FINISH_COLOR, "ghostblock.commands.load.finish", entries.size()));
        } else {
            LogUtil.info("log.info.task.load.cancelled", taskId);
        }
    }
    
    /**
     * 标记任务为取消。
     */
    public void cancel() {
        if (!this.cancelled) {
            LogUtil.info("log.info.task.load.markedCancelled", taskId);
            this.cancelled = true;
            previouslyWaitingForLoadOrProximityIndices.clear();
        }
    }
    
    // --- Getters for TaskSnapshot ---
    public int getTaskId() { return taskId; }
    public List<GhostBlockData.GhostBlockEntry> getRemainingEntries() {
        return (entries != null && currentIndex < entries.size())
                ? new ArrayList<>(entries.subList(currentIndex, entries.size()))
                : new ArrayList<>();
    }
    public int getBatchSize() { return batchSize; }
    public int getTotalEntries() { return entries.size(); }
    public ICommandSender getSender() { return sender; }
}