package com.zihaomc.ghost.features.ghostblock;

import com.zihaomc.ghost.features.ghostblock.tasks.ClearTask;
import com.zihaomc.ghost.features.ghostblock.tasks.FillTask;
import com.zihaomc.ghost.features.ghostblock.tasks.LoadTask;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData;
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
 * 处理所有与 GhostBlock 功能相关的 Forge 事件。
 * 包括后台任务处理、自动放置逻辑、世界加载/卸载时的清理。
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
     * 客户端 Tick 事件，用于处理后台任务队列和自动放置。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        processTaskQueues();
        processConfirmationsTimeout();
        handleAutoPlaceTick();
    }

    private void processTaskQueues() {
        synchronized (GhostBlockState.activeFillTasks) {
            Iterator<FillTask> taskIter = GhostBlockState.activeFillTasks.iterator();
            while (taskIter.hasNext()) {
                FillTask task = taskIter.next();
                if (task.processBatch()) {
                    taskIter.remove();
                }
            }
        }
        synchronized (GhostBlockState.activeLoadTasks) {
            Iterator<LoadTask> taskIter = GhostBlockState.activeLoadTasks.iterator();
            while (taskIter.hasNext()) {
                LoadTask task = taskIter.next();
                if (task.processBatch()) {
                    taskIter.remove();
                }
            }
        }
        synchronized (GhostBlockState.activeClearTasks) {
            Iterator<ClearTask> taskIter = GhostBlockState.activeClearTasks.iterator();
            while (taskIter.hasNext()) {
                ClearTask task = taskIter.next();
                if (task.processBatch()) {
                    taskIter.remove();
                }
            }
        }
    }

    private void processConfirmationsTimeout() {
        Iterator<Map.Entry<String, GhostBlockState.ClearConfirmation>> confirmIter = GhostBlockState.pendingConfirmations.entrySet().iterator();
        while (confirmIter.hasNext()) {
            Map.Entry<String, GhostBlockState.ClearConfirmation> entry = confirmIter.next();
            if (System.currentTimeMillis() - entry.getValue().timestamp > GhostBlockState.CONFIRMATION_TIMEOUT) {
                confirmIter.remove();
            }
        }
    }
    
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

        // 检查自动放置
        if (GhostConfig.AutoPlace.enableAutoPlaceOnJoin) {
            String autoPlaceFileName = GhostBlockHelper.getAutoPlaceSaveFileName(world);
            List<GhostBlockData.GhostBlockEntry> autoPlaceEntries = GhostBlockData.loadData(world, Collections.singletonList(autoPlaceFileName));

            if (!autoPlaceEntries.isEmpty()) {
                pendingAutoPlaceEntry = autoPlaceEntries.get(0);
                pendingAutoPlaceTargetPos = new BlockPos(pendingAutoPlaceEntry.x, pendingAutoPlaceEntry.y, pendingAutoPlaceEntry.z);
                pendingAutoPlaceFileRef = GhostBlockData.getDataFile(world, autoPlaceFileName);
                autoPlaceTickDelayCounter = 0;
                autoPlaceInProgress = true;
                LogUtil.debug("log.debug.joinWorld.pendingSet", pendingAutoPlaceTargetPos);
                return; 
            }
        }

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

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote || !(event.world instanceof WorldClient)) return;
        
        if (GhostConfig.AutoPlace.enableAutoPlaceOnJoin) {
            LogUtil.info("log.info.worldLoad.skipCleanup");
            return;
        }
        cleanupAndRestoreOnLoad((WorldClient) event.world);
    }
    
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.world.isRemote || !(event.world instanceof WorldClient)) return;
        
        WorldClient clientWorld = (WorldClient) event.world;
        LogUtil.info("log.info.worldUnload.entry", autoPlaceInProgress);

        if (pendingAutoPlaceEntry != null || autoPlaceInProgress) {
            cleanupPendingAutoPlace(true);
        }
        
        if (GhostConfig.AutoPlace.enableAutoPlaceOnJoin) {
            saveAutoPlaceDataOnUnload(clientWorld);
        }

        cleanupOnUnload(clientWorld);
        
        isFirstJoin = true;
        autoPlaceInProgress = false;
        LogUtil.info("log.info.worldUnload.standardCleanup.resettingState");
    }
    
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

        if (autoPlaceTickDelayCounter <= AUTO_PLACE_DURATION_TICKS) {
            if (isPlayerInAutoPlaceRange(player, centerActualPlacePos)) {
                placeAutoPlatform(world, centerActualPlacePos);
            }
        }
        
        if (autoPlaceTickDelayCounter > AUTO_PLACE_MAX_ATTEMPT_TICKS) {
            cleanupPendingAutoPlace(true);
        }
    }
    
    private boolean isPlayerInAutoPlaceRange(EntityPlayer player, BlockPos platformCenter) {
        BlockPos playerCurrentBlockPos = player.getPosition();
        boolean isInHorizontalRange = Math.abs(playerCurrentBlockPos.getX() - platformCenter.getX()) <= 2 &&
                                      Math.abs(playerCurrentBlockPos.getZ() - platformCenter.getZ()) <= 2;
        boolean isVerticallyReasonable = playerCurrentBlockPos.getY() >= platformCenter.getY() - 2 &&
                                         playerCurrentBlockPos.getY() <= platformCenter.getY() + 5;
        return isInHorizontalRange && isVerticallyReasonable;
    }

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
        if (autoPlaceTickDelayCounter == 1 || (autoPlaceTickDelayCounter == AUTO_PLACE_DURATION_TICKS)) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.autoplace.platform_success", center.getX(), center.getY(), center.getZ()));
        }
        if (autoPlaceTickDelayCounter >= AUTO_PLACE_DURATION_TICKS) {
            cleanupPendingAutoPlace(true);
        }
    }

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
        
        if (wasInProgress && isFirstJoin) {
            WorldClient world = Minecraft.getMinecraft().theWorld;
            if (world != null) {
                cleanupAndRestoreOnLoad(world);
                isFirstJoin = false;
                lastTrackedDimension = world.provider.getDimensionId();
            }
        }
    }

    private void cleanupAndRestoreOnLoad(WorldClient world) {
        String autoFileName = GhostBlockHelper.getAutoClearFileName(world);
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

    private void saveAutoPlaceDataOnUnload(WorldClient clientWorld) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null || player.worldObj != clientWorld) return;

        BlockPos logicalPlayerFeetPos = new BlockPos(Math.floor(player.posX), Math.floor(player.posY - 1.0), Math.floor(player.posZ));
        String tempClearFileName = GhostBlockHelper.getAutoClearFileName(clientWorld);
        List<GhostBlockData.GhostBlockEntry> clearEntries = GhostBlockData.loadData(clientWorld, Collections.singletonList(tempClearFileName));

        Optional<GhostBlockData.GhostBlockEntry> ghostEntryAtLogicalFeet = clearEntries.stream()
                .filter(entry -> entry.x == logicalPlayerFeetPos.getX() && entry.y == logicalPlayerFeetPos.getY() && entry.z == logicalPlayerFeetPos.getZ())
                .findFirst();

        String autoPlaceSaveFileName = GhostBlockHelper.getAutoPlaceSaveFileName(clientWorld);
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

    private void cleanupOnUnload(WorldClient clientWorld) {
        File tempClearFileObject = GhostBlockData.getDataFile(clientWorld, GhostBlockHelper.getAutoClearFileName(clientWorld));
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
    
    private void cancelAllTasks(ICommandSender feedbackSender) {
        int cancelledCount = 0;
        cancelledCount += GhostBlockState.activeFillTasks.size();
        GhostBlockState.activeFillTasks.forEach(FillTask::cancel);
        GhostBlockState.activeFillTasks.clear();
        
        cancelledCount += GhostBlockState.activeLoadTasks.size();
        GhostBlockState.activeLoadTasks.forEach(LoadTask::cancel);
        GhostBlockState.activeLoadTasks.clear();

        cancelledCount += GhostBlockState.activeClearTasks.size();
        GhostBlockState.activeClearTasks.forEach(ClearTask::cancel);
        GhostBlockState.activeClearTasks.clear();

        cancelledCount += GhostBlockState.pausedTasks.size();
        GhostBlockState.pausedTasks.clear();

        if (feedbackSender != null && cancelledCount > 0) {
            feedbackSender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.task.cancelled_world_change"));
        }
        LogUtil.info("log.info.tasks.cancelled.count", cancelledCount);
    }
}
