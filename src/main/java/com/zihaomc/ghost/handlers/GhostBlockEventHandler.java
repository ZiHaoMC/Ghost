package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.tasks.ClearTask;
import com.zihaomc.ghost.commands.tasks.FillTask;
import com.zihaomc.ghost.commands.tasks.LoadTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.config.GhostConfig;
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
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 处理所有与 GhostBlockCommand 相关的 Forge 事件。
 * 包括：后台任务队列的 Tick 处理、事件驱动的状态清理与恢复等。
 */
public class GhostBlockEventHandler {

    // --- 事件相关状态 ---
    private static int lastTrackedDimension = 0;
    private static boolean isFirstJoin = true;

    // --- 自动放置相关状态 ---
    private static GhostBlockData.GhostBlockEntry pendingAutoPlaceEntry = null;
    private static BlockPos pendingAutoPlaceTargetPos = null;
    private static File pendingAutoPlaceFileRef = null;
    private static int autoPlaceTickDelayCounter = 0;
    private static final int AUTO_PLACE_DURATION_TICKS = 40; // 持续放置2秒
    private static final int AUTO_PLACE_MAX_ATTEMPT_TICKS = 100; // 最多等待5秒
    private static boolean autoPlaceInProgress = false;

    /**
     * 在每个客户端 Tick 结束时调用。
     * 用于处理后台任务队列、确认超时和自动放置逻辑。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        // 1. 处理所有后台任务队列
        processTaskQueues();
        
        // 2. 处理清除确认请求的超时
        processConfirmationsTimeout();
        
        // 3. 处理登陆时的自动放置逻辑
        handleAutoPlaceTick();
    }

    /**
     * 遍历并处理所有活动的后台任务（填充、加载、清除）。
     */
    private void processTaskQueues() {
        // 处理填充任务
        synchronized (CommandState.activeFillTasks) {
            Iterator<FillTask> taskIter = CommandState.activeFillTasks.iterator();
            while (taskIter.hasNext()) {
                FillTask task = taskIter.next();
                if (task.processBatch()) {
                    taskIter.remove();
                }
            }
        }
        // 处理加载任务
        synchronized (CommandState.activeLoadTasks) {
            Iterator<LoadTask> taskIter = CommandState.activeLoadTasks.iterator();
            while (taskIter.hasNext()) {
                LoadTask task = taskIter.next();
                if (task.processBatch()) {
                    taskIter.remove();
                }
            }
        }
        // 处理清除任务
        synchronized (CommandState.activeClearTasks) {
            Iterator<ClearTask> taskIter = CommandState.activeClearTasks.iterator();
            while (taskIter.hasNext()) {
                ClearTask task = taskIter.next();
                if (task.processBatch()) {
                    taskIter.remove();
                }
            }
        }
    }

    /**
     * 检查并移除超时的清除确认请求。
     */
    private void processConfirmationsTimeout() {
        Iterator<Map.Entry<String, CommandState.ClearConfirmation>> confirmIter = CommandState.pendingConfirmations.entrySet().iterator();
        while (confirmIter.hasNext()) {
            Map.Entry<String, CommandState.ClearConfirmation> entry = confirmIter.next();
            if (System.currentTimeMillis() - entry.getValue().timestamp > CommandState.CONFIRMATION_TIMEOUT) {
                confirmIter.remove();
            }
        }
    }
    
    /**
     * 处理玩家加入世界或切换维度时的逻辑。
     * 主要负责状态重置和自动恢复/放置。
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!event.world.isRemote || !(event.entity instanceof EntityPlayer)) return;
        
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null || !event.entity.equals(player)) return;

        WorldClient world = (WorldClient) event.world;
        int currentDim = player.dimension;
        LogUtil.debug("log.debug.joinWorld.entry", currentDim, lastTrackedDimension);

        if (autoPlaceInProgress) {
            LogUtil.debug("log.debug.joinWorld.autoplaceInProgress");
            return;
        }
        if (pendingAutoPlaceEntry != null) {
            LogUtil.warn("log.warn.joinWorld.pendingEntry");
            cleanupPendingAutoPlace(true);
        }

        // 检查是否需要执行自动放置逻辑
        if (GhostConfig.AutoPlace.enableAutoPlaceOnJoin) {
            String autoPlaceFileName = CommandHelper.getAutoPlaceSaveFileName(world);
            List<GhostBlockData.GhostBlockEntry> autoPlaceEntries = GhostBlockData.loadData(world, Collections.singletonList(autoPlaceFileName));

            if (!autoPlaceEntries.isEmpty()) {
                pendingAutoPlaceEntry = autoPlaceEntries.get(0);
                pendingAutoPlaceTargetPos = new BlockPos(pendingAutoPlaceEntry.x, pendingAutoPlaceEntry.y, pendingAutoPlaceEntry.z);
                pendingAutoPlaceFileRef = GhostBlockData.getDataFile(world, autoPlaceFileName);
                autoPlaceTickDelayCounter = 0;
                autoPlaceInProgress = true;
                LogUtil.debug("log.debug.joinWorld.pendingSet", pendingAutoPlaceTargetPos);
                return; // 让 Tick 事件接管后续处理
            }
        }

        // 标准的加入/切换维度逻辑
        if (isFirstJoin) {
            LogUtil.debug("log.debug.joinWorld.standardFlow.firstJoin", currentDim);
            cleanupAndRestoreOnLoad(world);
            isFirstJoin = false;
        } else if (lastTrackedDimension != currentDim) {
            LogUtil.debug("log.debug.joinWorld.standardFlow.dimensionChange", lastTrackedDimension, currentDim);
            cancelAllTasks(player);
            cleanupAndRestoreOnLoad(world);
        } else {
            LogUtil.debug("log.debug.joinWorld.standardFlow.rejoinSameDim", currentDim);
            cleanupAndRestoreOnLoad(world);
        }
        lastTrackedDimension = currentDim;
    }

    /**
     * 在世界加载时触发，用于恢复原始方块。
     */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote || !(event.world instanceof WorldClient)) return;
        
        if (GhostConfig.AutoPlace.enableAutoPlaceOnJoin) {
            LogUtil.info("log.info.worldLoad.skipCleanup");
            return;
        }
        cleanupAndRestoreOnLoad((WorldClient) event.world);
    }
    
    /**
     * 在世界卸载时触发，用于清理状态和保存数据。
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote || !(event.world instanceof WorldClient)) return;
        
        WorldClient clientWorld = (WorldClient) event.world;
        LogUtil.info("log.info.worldUnload.entry", autoPlaceInProgress);

        // 如果存在待处理的自动放置任务，立即清理
        if (pendingAutoPlaceEntry != null || autoPlaceInProgress) {
            cleanupPendingAutoPlace(true);
        }
        
        // 如果启用了自动放置，则在退出时保存脚下的幽灵方块信息
        if (GhostConfig.AutoPlace.enableAutoPlaceOnJoin) {
            saveAutoPlaceDataOnUnload(clientWorld);
        }

        // 清理与卸载的世界相关的文件和任务
        cleanupOnUnload(clientWorld);
        
        // 重置状态
        isFirstJoin = true;
        autoPlaceInProgress = false;
        LogUtil.info("log.info.worldUnload.standardCleanup.resettingState");
    }
    
    // --- 辅助方法 ---

    /**
     * 在每个 Tick 中处理自动放置的逻辑。
     */
    private void handleAutoPlaceTick() {
        if (!autoPlaceInProgress || pendingAutoPlaceEntry == null) return;

        autoPlaceTickDelayCounter++;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        WorldClient world = Minecraft.getMinecraft().theWorld;

        if (player == null || world == null || pendingAutoPlaceTargetPos == null || pendingAutoPlaceFileRef == null) {
            cleanupPendingAutoPlace(true);
            return;
        }

        int fileDimension = GhostBlockData.getDimensionFromFileName(pendingAutoPlaceFileRef.getName());
        if (fileDimension != Integer.MIN_VALUE && player.dimension != fileDimension) {
            cleanupPendingAutoPlace(true);
            return;
        }

        BlockPos centerActualPlacePos = pendingAutoPlaceTargetPos.down(1);

        // 在持续时间内，如果玩家在范围内，则尝试放置平台
        if (autoPlaceTickDelayCounter <= AUTO_PLACE_DURATION_TICKS) {
            if (isPlayerInAutoPlaceRange(player, centerActualPlacePos)) {
                placeAutoPlatform(world, centerActualPlacePos);
            }
        }
        
        // 检查是否超时
        if (autoPlaceTickDelayCounter > AUTO_PLACE_MAX_ATTEMPT_TICKS) {
            cleanupPendingAutoPlace(true);
        }
    }
    
    /**
     * 检查玩家是否在自动放置的目标范围内。
     */
    private boolean isPlayerInAutoPlaceRange(EntityPlayer player, BlockPos platformCenter) {
        BlockPos playerCurrentBlockPos = player.getPosition();
        boolean isInHorizontalRange = Math.abs(playerCurrentBlockPos.getX() - platformCenter.getX()) <= 2 &&
                                      Math.abs(playerCurrentBlockPos.getZ() - platformCenter.getZ()) <= 2;
        boolean isVerticallyReasonable = playerCurrentBlockPos.getY() >= platformCenter.getY() - 2 &&
                                         playerCurrentBlockPos.getY() <= platformCenter.getY() + 5;
        return isInHorizontalRange && isVerticallyReasonable;
    }

    /**
     * 放置一个3x3的幽灵方块平台。
     */
    private void placeAutoPlatform(WorldClient world, BlockPos center) {
        Block ghostBlockToPlace = Block.getBlockFromName(pendingAutoPlaceEntry.blockId);
        if (ghostBlockToPlace == null || ghostBlockToPlace == Blocks.air) {
            cleanupPendingAutoPlace(true);
            return;
        }
        IBlockState stateToSet = ghostBlockToPlace.getStateFromMeta(pendingAutoPlaceEntry.metadata);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos currentPlatformPos = center.add(dx, 0, dz);
                if (world.isAirBlock(currentPlatformPos)) {
                    world.setBlockState(currentPlatformPos, stateToSet, 2 | 16);
                    world.markBlockForUpdate(currentPlatformPos);
                }
            }
        }
        // 在首次放置或持续时间结束时发送消息
        if (autoPlaceTickDelayCounter == 1 || (autoPlaceTickDelayCounter == AUTO_PLACE_DURATION_TICKS)) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.autoplace.platform_success", center.getX(), center.getY(), center.getZ()));
        }
        if (autoPlaceTickDelayCounter >= AUTO_PLACE_DURATION_TICKS) {
            cleanupPendingAutoPlace(true);
        }
    }

    /**
     * 清理所有与自动放置相关的状态。
     */
    private void cleanupPendingAutoPlace(boolean deleteFile) {
        if (deleteFile && pendingAutoPlaceFileRef != null && pendingAutoPlaceFileRef.exists()) {
            if (!pendingAutoPlaceFileRef.delete()) {
                LogUtil.error("log.error.autoplace.cleanup.file.deleteFailed", pendingAutoPlaceFileRef.getName());
            }
        }
        pendingAutoPlaceEntry = null;
        pendingAutoPlaceTargetPos = null;
        pendingAutoPlaceFileRef = null;
        autoPlaceTickDelayCounter = 0;
        boolean wasInProgress = autoPlaceInProgress;
        autoPlaceInProgress = false;
        
        // 如果自动放置过程结束了，并且这是首次加入，需要手动触发一次清理和恢复
        if (wasInProgress && isFirstJoin) {
            WorldClient world = Minecraft.getMinecraft().theWorld;
            if (world != null) {
                cleanupAndRestoreOnLoad(world);
                isFirstJoin = false;
                lastTrackedDimension = world.provider.getDimensionId();
            }
        }
    }

    /**
     * 在世界加载时清理自动保存文件并恢复原始方块。
     */
    private void cleanupAndRestoreOnLoad(WorldClient world) {
        String autoFileName = CommandHelper.getAutoClearFileName(world);
        File autoFile = GhostBlockData.getDataFile(world, autoFileName);

        if (autoFile.exists()) {
            LogUtil.info("log.worldLoad.foundClearFile", autoFileName);
            List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
            int restored = 0;
            int failed = 0;
            for (GhostBlockData.GhostBlockEntry entry : entries) {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
                if (originalBlock != null) {
                    world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                    restored++;
                } else {
                    failed++;
                }
            }
            LogUtil.info("log.worldLoad.restoreComplete", restored, failed);
            if (!autoFile.delete()) {
                LogUtil.error("log.error.worldLoad.clearFile.deleteFailed", autoFile.getPath());
            }
        }
    }

    /**
     * 在世界卸载时保存自动放置所需的数据。
     */
    private void saveAutoPlaceDataOnUnload(WorldClient clientWorld) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null || player.worldObj != clientWorld) return;

        BlockPos logicalPlayerFeetPos = new BlockPos(Math.floor(player.posX), Math.floor(player.posY - 1.0), Math.floor(player.posZ));
        String tempClearFileName = CommandHelper.getAutoClearFileName(clientWorld);
        List<GhostBlockData.GhostBlockEntry> clearEntries = GhostBlockData.loadData(clientWorld, Collections.singletonList(tempClearFileName));

        Optional<GhostBlockData.GhostBlockEntry> ghostEntryAtLogicalFeet = clearEntries.stream()
                .filter(entry -> entry.x == logicalPlayerFeetPos.getX() && entry.y == logicalPlayerFeetPos.getY() && entry.z == logicalPlayerFeetPos.getZ())
                .findFirst();

        String autoPlaceSaveFileName = CommandHelper.getAutoPlaceSaveFileName(clientWorld);
        File autoPlaceFileToSaveTo = GhostBlockData.getDataFile(clientWorld, autoPlaceSaveFileName);

        if (ghostEntryAtLogicalFeet.isPresent()) {
            GhostBlockData.saveData(clientWorld, Collections.singletonList(ghostEntryAtLogicalFeet.get()), autoPlaceSaveFileName, true);
            LogUtil.info("log.info.worldUnload.autoplace.saved", logicalPlayerFeetPos, ghostEntryAtLogicalFeet.get().blockId, autoPlaceSaveFileName);
        } else {
            if (autoPlaceFileToSaveTo.exists() && !autoPlaceFileToSaveTo.delete()) {
                LogUtil.error("log.error.worldUnload.autoplace.deleteFailed", autoPlaceFileToSaveTo.getName());
            }
        }
    }

    /**
     * 在世界卸载时清理相关文件和任务。
     */
    private void cleanupOnUnload(WorldClient clientWorld) {
        File tempClearFileObject = GhostBlockData.getDataFile(clientWorld, CommandHelper.getAutoClearFileName(clientWorld));
        if (tempClearFileObject.exists()) {
            tempClearFileObject.delete();
        }

        String baseId = GhostBlockData.getWorldBaseIdentifier(clientWorld);
        int unloadedDim = clientWorld.provider.getDimensionId();
        File savesDir = new File(GhostBlockData.SAVES_DIR);
        final String undoPrefix = "undo_" + baseId + "_dim_" + unloadedDim + "_";
        File[] undoFiles = savesDir.listFiles((dir, name) -> name.startsWith(undoPrefix) && name.endsWith(".json"));
        if (undoFiles != null) {
            for (File file : undoFiles) file.delete();
        }
        
        cancelAllTasks(Minecraft.getMinecraft().thePlayer);
    }
    
    /**
     * 取消所有类型的活动任务。
     */
    private void cancelAllTasks(ICommandSender feedbackSender) {
        int cancelledCount = 0;
        cancelledCount += CommandState.activeFillTasks.size();
        CommandState.activeFillTasks.forEach(FillTask::cancel);
        CommandState.activeFillTasks.clear();
        
        cancelledCount += CommandState.activeLoadTasks.size();
        CommandState.activeLoadTasks.forEach(LoadTask::cancel);
        CommandState.activeLoadTasks.clear();

        cancelledCount += CommandState.activeClearTasks.size();
        CommandState.activeClearTasks.forEach(ClearTask::cancel);
        CommandState.activeClearTasks.clear();

        cancelledCount += CommandState.pausedTasks.size();
        CommandState.pausedTasks.clear();

        if (feedbackSender != null && cancelledCount > 0) {
            feedbackSender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.task.cancelled_world_change"));
        }
        LogUtil.info("log.info.tasks.cancelled.count", cancelledCount);
    }
}