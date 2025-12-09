package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static boolean isPathfinding = false;
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    private static BlockPos globalTarget = null;
    private static boolean isCalculating = false;

    // 参数
    private static final int MAX_SEGMENT_LENGTH = 60; 
    private static final int MIN_SEGMENT_LENGTH = 2; // 允许搜寻更近的点

    private static final boolean DEBUG = true; // 开启调试看日志

    public static void setGlobalTarget(BlockPos target) {
        globalTarget = target;
        currentPath = null;
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
        
        debug(">>> 启动新寻路，目标: " + target);
        runTerrainAnalysis(target);
    }

    public static void stop() {
        if (isPathfinding && DEBUG) debug("<<< 寻路停止");
        isPathfinding = false;
        currentPath = null;
        globalTarget = null;
        isCalculating = false;
        resetKeys();
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static BlockPos getGlobalTarget() { return globalTarget; }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        double distToTarget = mc.thePlayer.getDistanceSq(globalTarget);
        boolean verticalMatch = Math.abs(mc.thePlayer.posY - globalTarget.getY()) < 2.0;

        if (globalTarget != null && distToTarget < 4.0 && verticalMatch) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        if (!isCalculating) {
            boolean needsNewPath = (currentPath == null) || (currentPathIndex >= currentPath.size() - 5);
            if (needsNewPath) {
                generateNextSegment();
            }
        }
        
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            resetKeys();
            return;
        }

        followPathSmoothed();
    }

    private void generateNextSegment() {
        isCalculating = true;
        final BlockPos startPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);

        new Thread(() -> {
            try {
                if (globalTarget == null) return;

                BlockPos segmentTarget = null;
                boolean forceManualDrop = false;

                double dx = globalTarget.getX() - startPos.getX();
                double dz = globalTarget.getZ() - startPos.getZ();
                double distToGlobalSq = dx * dx + dz * dz;
                double distToGlobal = Math.sqrt(distToGlobalSq);

                // A. 垂直下落
                if (distToGlobalSq < 2.5) { 
                    if (globalTarget.getY() < startPos.getY()) {
                        BlockPos dropSpot = findNearestDropSpot(startPos);
                        if (dropSpot != null) {
                            segmentTarget = dropSpot;
                            forceManualDrop = true;
                        } else {
                            segmentTarget = globalTarget; 
                        }
                    } else {
                        segmentTarget = globalTarget;
                    }
                } else {
                    // B. 分级扫描
                    double baseAngle = Math.atan2(dz, dx);
                    
                    // [修复] 传入实际距离，防止“远视眼”跳过近处目标
                    segmentTarget = scanGreedy(startPos, baseAngle, distToGlobal);
                    
                    if (segmentTarget == null) {
                        debugErr("前方受阻，启动逃逸扫描...");
                        segmentTarget = scanEscape(startPos, baseAngle);
                    }
                    
                    if (segmentTarget == null && distToGlobalSq < MAX_SEGMENT_LENGTH * MAX_SEGMENT_LENGTH) {
                        segmentTarget = globalTarget;
                    }
                }

                if (segmentTarget != null) {
                    final BlockPos finalTarget = segmentTarget;
                    final boolean manualDrop = forceManualDrop;
                    
                    List<BlockPos> newSegment = null;
                    if (!manualDrop) {
                        newSegment = Pathfinder.computePath(startPos, segmentTarget, 8000);
                    }
                    
                    final List<BlockPos> calculatedPath = newSegment;

                    mc.addScheduledTask(() -> {
                        if (!isPathfinding) return;
                        
                        if (calculatedPath != null && !calculatedPath.isEmpty()) {
                            applyNewPath(calculatedPath);
                        } else if (manualDrop) {
                            List<BlockPos> manualPath = new ArrayList<>();
                            manualPath.add(finalTarget);
                            applyNewPath(manualPath);
                        } else {
                            // A* 失败
                        }
                        isCalculating = false;
                    });
                } else {
                    mc.addScheduledTask(() -> isCalculating = false);
                }

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false);
            }
        }).start();
    }

    // --- 扫描逻辑 ---

    private BlockPos scanGreedy(BlockPos startPos, double baseAngle, double distToGlobal) {
        BlockPos bestCandidate = null;
        // 如果离终点很近，阈值放宽，只要能到就行，不一定要更近
        double threshold = (distToGlobal < 5.0) ? 0.0 : 2.0;
        double minDistToGlobal = startPos.distanceSq(globalTarget) - threshold;
        
        int[] angles = {0, 10, -10, 20, -20, 30, -30, 45, -45, 60, -60, 90, -90};
        
        for (int angleOffset : angles) {
            // [修复] 搜索范围限制为实际距离和最大距离之间的较小值
            // 这样能确保我们优先检查终点附近的点
            double searchDist = Math.min(distToGlobal + 2.0, MAX_SEGMENT_LENGTH);
            
            BlockPos candidate = raycastFindValidSpot(startPos, baseAngle + Math.toRadians(angleOffset), searchDist);
            
            if (candidate != null) {
                double dist = candidate.distanceSq(globalTarget);
                if (dist < minDistToGlobal) {
                    minDistToGlobal = dist;
                    bestCandidate = candidate;
                    break; 
                }
            }
        }
        return bestCandidate;
    }

    private BlockPos scanEscape(BlockPos startPos, double baseAngle) {
        BlockPos bestCandidate = null;
        double maxDistFromStart = 0.0;
        
        for (int i = 0; i < 24; i++) {
            double angle = baseAngle + Math.toRadians(i * 15);
            // 逃逸模式还是看远一点
            BlockPos candidate = raycastFindValidSpot(startPos, angle, MAX_SEGMENT_LENGTH);
            
            if (candidate != null) {
                double distFromStart = candidate.distanceSq(startPos);
                if (distFromStart > 25.0 && distFromStart > maxDistFromStart) {
                    maxDistFromStart = distFromStart;
                    bestCandidate = candidate;
                }
            }
        }
        return bestCandidate;
    }

    /**
     * [核心修复] 射线检测
     * 1. startDist: 根据情况动态调整，不再固定 50
     * 2. step: 降低到 1.0，防止漏掉近处的点
     */
    private BlockPos raycastFindValidSpot(BlockPos start, double angleRad, double startDist) {
        double nx = Math.cos(angleRad);
        double nz = Math.sin(angleRad);
        
        double checkDist = startDist;
        
        while (checkDist > MIN_SEGMENT_LENGTH) {
            int tx = (int) (start.getX() + nx * checkDist);
            int tz = (int) (start.getZ() + nz * checkDist);
            
            BlockPos candidate = findSafeY(tx, tz, start.getY());
            if (candidate != null) {
                return candidate;
            }
            checkDist -= 1.0; // [修复] 步长改为 1.0，高精度扫描
        }
        return null;
    }

    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        while (newPath.size() > 1) {
            BlockPos node = newPath.get(0);
            Vec3 nodeCenter = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            double distSq = (playerPos.xCoord - nodeCenter.xCoord) * (playerPos.xCoord - nodeCenter.xCoord) + 
                            (playerPos.zCoord - nodeCenter.zCoord) * (playerPos.zCoord - nodeCenter.zCoord);
            if (distSq < 1.0) { 
                newPath.remove(0);
            } else {
                break;
            }
        }
        if (newPath.isEmpty()) return;
        currentPath = new CopyOnWriteArrayList<>(newPath);
        currentPathIndex = 0;
    }

    private void followPathSmoothed() {
        if (currentPath == null || currentPath.isEmpty()) return;
        updatePathIndexAutomatically();
        if (currentPathIndex >= currentPath.size()) return;

        BlockPos aimTarget = currentPath.get(currentPathIndex);
        int lookAheadDist = 6; 
        for (int i = 1; i <= lookAheadDist; i++) {
            int testIndex = currentPathIndex + i;
            if (testIndex >= currentPath.size()) break;
            BlockPos nextNode = currentPath.get(testIndex);
            if (canWalkDirectly(mc.thePlayer.getPositionVector(), nextNode)) {
                aimTarget = nextNode;
            } else {
                break; 
            }
        }

        Vec3 targetCenter = new Vec3(aimTarget.getX() + 0.5, aimTarget.getY(), aimTarget.getZ() + 0.5);
        if (aimTarget.getY() < mc.thePlayer.posY - 0.5) {
            targetCenter = new Vec3(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        } else {
            targetCenter = targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        }
        
        float[] rotations = RotationUtil.getRotations(targetCenter);
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 40.0f); 
        float smoothPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], 40.0f);

        mc.thePlayer.rotationYaw = smoothYaw;
        if (aimTarget.getY() < mc.thePlayer.posY - 1.0) {
            mc.thePlayer.rotationPitch = 0.0f;
        } else {
            mc.thePlayer.rotationPitch = smoothPitch;
        }

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(true);

        boolean jump = false;
        if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) jump = true;
        if (aimTarget.getY() > mc.thePlayer.getEntityBoundingBox().minY + 0.5) jump = true;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    private boolean canWalkDirectly(Vec3 startPos, BlockPos end) {
        Vec3 endCenter = new Vec3(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        Vec3 startEye = startPos.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        double endYOffset = (end.getY() < startPos.yCoord) ? 1.0 : 0.5;
        Vec3 endEye = new Vec3(end.getX() + 0.5, end.getY() + endYOffset, end.getZ() + 0.5);

        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startEye, endEye, false, true, false);
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos hitPos = result.getBlockPos();
            if (!hitPos.equals(end)) {
                if (hitPos.getY() > startPos.yCoord) {
                    Block hitBlock = mc.theWorld.getBlockState(hitPos).getBlock();
                    if (!canWalkThrough(hitPos)) return false; 
                }
            }
        }
        if (!isPathSafe(startPos, endCenter)) return false; 
        return true;
    }

    private boolean isPathSafe(Vec3 start, Vec3 end) {
        double dist = start.distanceTo(end);
        double dx = (end.xCoord - start.xCoord) / dist;
        double dy = (end.yCoord - start.yCoord) / dist;
        double dz = (end.zCoord - start.zCoord) / dist;
        double stepSize = 0.4;
        
        double currentX = start.xCoord;
        double currentY = start.yCoord; 
        double currentZ = start.zCoord;

        for (double d = 0; d < dist; d += stepSize) {
            currentX += dx * stepSize;
            currentY += dy * stepSize;
            currentZ += dz * stepSize;

            BlockPos posToCheck = new BlockPos(currentX, currentY, currentZ);
            
            if (!isSafeToStep(posToCheck)) {
                if (!isSafeToStep(posToCheck.down())) {
                    boolean isTargetBelow = end.yCoord < start.yCoord;
                    if (isTargetBelow && canSurviveFall(posToCheck)) continue; 
                    return false;
                }
            }
        }
        return true;
    }

    private BlockPos findNearestDropSpot(BlockPos start) {
        BlockPos bestSpot = null;
        double minDistSq = 999.0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue; 
                BlockPos candidate = start.add(x, 0, z);
                if (!canWalkThrough(candidate)) continue;
                BlockPos ground = candidate.down();
                double groundH = getBlockHeight(ground);
                Material mat = mc.theWorld.getBlockState(ground).getBlock().getMaterial();
                if (groundH <= 0.0 && !mat.isLiquid()) {
                     if (canSurviveFall(candidate)) {
                        double dSq = start.distanceSq(candidate);
                        if (dSq < minDistSq) {
                            minDistSq = dSq;
                            bestSpot = candidate;
                        }
                    }
                }
            }
        }
        return bestSpot;
    }

    private boolean canSurviveFall(BlockPos startPos) {
        float currentHealth = mc.thePlayer.getHealth();
        float minHealthToKeep = 6.0f; 
        if (currentHealth <= minHealthToKeep) return false;

        for (int i = 0; i <= 20; i++) {
            BlockPos checkPos = startPos.down(i);
            if (!mc.theWorld.getChunkProvider().chunkExists(checkPos.getX() >> 4, checkPos.getZ() >> 4)) return false;
            Block block = mc.theWorld.getBlockState(checkPos).getBlock();
            Material mat = block.getMaterial();

            if (mat.isSolid() || mat.isLiquid()) {
                if (mat == Material.water) return true;
                if (mat == Material.lava || block == Blocks.cactus) return false;
                float damage = Math.max(0, (i - 1) - 3.0f); 
                return (currentHealth - damage) > minHealthToKeep;
            }
        }
        return false;
    }

    private static void runTerrainAnalysis(BlockPos target) {
        final BlockPos start = mc.thePlayer.getPosition();
        new Thread(() -> {
            try {
                int minX = Math.min(start.getX(), target.getX()) - 2;
                int maxX = Math.max(start.getX(), target.getX()) + 2;
                int minY = Math.min(start.getY(), target.getY()) - 2;
                int maxY = Math.max(start.getY(), target.getY()) + 5; 
                int minZ = Math.min(start.getZ(), target.getZ()) - 2;
                int maxZ = Math.max(start.getZ(), target.getZ()) + 2;

                File dumpFile = new File(mc.mcDataDir, "ghost_terrain_scan.txt");
                try (PrintWriter writer = new PrintWriter(dumpFile, "UTF-8")) {
                    writer.println("=== Ghost 地形自动扫描报告 ===");
                    writer.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    writer.println("起点: " + start);
                    writer.println("终点: " + target);
                    writer.println("扫描范围: X[" + minX + "~" + maxX + "] Y[" + minY + "~" + maxY + "] Z[" + minZ + "~" + maxZ + "]");
                    writer.println("--------------------------------------------------");
                    writer.println("格式: [X, Y, Z] | 方块名称 | 碰撞高度 | 材质 | 阻挡判定");
                    writer.println("--------------------------------------------------");

                    int count = 0;
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                if (!mc.theWorld.getChunkProvider().chunkExists(x >> 4, z >> 4)) continue;
                                BlockPos pos = new BlockPos(x, y, z);
                                Block block = mc.theWorld.getBlockState(pos).getBlock();
                                if (block.getMaterial() == Material.air && !pos.equals(target)) continue;

                                double h = getBlockHeight(pos);
                                String name = block.getLocalizedName();
                                String status = "通行";
                                if (isCenterBlocked(pos)) {
                                    if (h > 0.6) status = "!!!阻挡(脚)!!!";
                                    if (h > 0.0 && y == start.getY() + 1) status = "!!!阻挡(头)!!!";
                                }
                                if (block.getMaterial().isLiquid()) status = "液体";
                                writer.printf("[%d, %d, %d] | %-15s | H:%.2f | %s\n", x, y, z, name, h, status);
                                count++;
                            }
                        }
                    }
                    writer.println("--------------------------------------------------");
                    writer.println("共记录 " + count + " 个非空气方块。");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static boolean isCenterBlocked(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block instanceof BlockCarpet || block instanceof BlockSnow || block instanceof BlockLilyPad || 
            block == Blocks.tallgrass || block == Blocks.double_plant || block == Blocks.deadbush) return false;
        
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, mc.theWorld.getBlockState(pos));
        if (box == null) return false; 

        double centerXMin = pos.getX() + 0.3;
        double centerXMax = pos.getX() + 0.7;
        double centerZMin = pos.getZ() + 0.3;
        double centerZMax = pos.getZ() + 0.7;

        boolean xIntersect = (box.maxX > centerXMin && box.minX < centerXMax);
        boolean zIntersect = (box.maxZ > centerZMin && box.minZ < centerZMax);
        return xIntersect && zIntersect;
    }

    private static double getBlockHeight(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, mc.theWorld.getBlockState(pos));
        if (box == null) return 0.0;
        return box.maxY - pos.getY();
    }

    private static boolean canWalkThrough(BlockPos pos) {
        if (isCenterBlocked(pos)) {
            if (getBlockHeight(pos) > 0.6) return false;
        }
        if (isCenterBlocked(pos.up())) {
            if (getBlockHeight(pos.up()) > 0.8) return false; 
        }
        return true;
    }

    private boolean isSafeToStep(BlockPos pos) {
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) return false;

        BlockPos groundPos = pos.down();
        Block groundBlock = mc.theWorld.getBlockState(groundPos).getBlock();
        double groundHeight = getBlockHeight(groundPos);
        Material mat = groundBlock.getMaterial();

        if (groundHeight <= 0.0 && !mat.isLiquid()) return false;
        if (groundBlock == Blocks.cactus || groundBlock == Blocks.web || groundBlock == Blocks.lava) return false;

        return canWalkThrough(pos);
    }

    private void updatePathIndexAutomatically() {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        int searchRange = 10;
        int maxIndex = Math.min(currentPathIndex + searchRange, currentPath.size() - 1);
        for (int i = currentPathIndex; i <= maxIndex; i++) {
            BlockPos node = currentPath.get(i);
            double distSq = playerPos.squareDistanceTo(new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5));
            if (distSq < 0.8 * 0.8) {
                currentPathIndex = i + 1; 
            }
        }
    }

    private BlockPos findSafeY(int x, int z, int startY) {
        if (isSafeToStep(new BlockPos(x, startY, z))) return new BlockPos(x, startY, z);
        for (int y = startY + 1; y <= startY + 5; y++) {
            if (isSafeToStep(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        for (int y = startY - 1; y >= startY - 5; y--) {
            if (isSafeToStep(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        return null;
    }

    private float limitAngleChange(float current, float target, float maxChange) {
        float change = MathHelper.wrapAngleTo180_float(target - current);
        if (change > maxChange) change = maxChange;
        if (change < -maxChange) change = -maxChange;
        return current + change;
    }

    private static void resetKeys() {
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
            if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
        }
    }
    
    private static void debug(String msg) {
        if (DEBUG && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Debug] " + msg));
        }
    }
    
    private static void debugErr(String msg) {
        if (DEBUG && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Error] " + msg));
        }
    }
}