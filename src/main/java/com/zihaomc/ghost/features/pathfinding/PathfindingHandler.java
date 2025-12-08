package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // --- 状态变量 ---
    private static boolean isPathfinding = false;
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    private static BlockPos globalTarget = null;
    private static boolean isCalculating = false;

    // 寻路参数
    private static final int MAX_SEGMENT_LENGTH = 80;
    private static final int MIN_SEGMENT_LENGTH = 5;

    // --- 调试开关 ---
    private static final boolean DEBUG = true;

    public static void setGlobalTarget(BlockPos target) {
        globalTarget = target;
        currentPath = null;
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
        debug("设置新目标: " + target);
    }

    public static void stop() {
        if (isPathfinding) debug("寻路停止");
        isPathfinding = false;
        currentPath = null;
        globalTarget = null;
        isCalculating = false;
        resetKeys();
    }

    private static void debug(String msg) {
        if (DEBUG && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Debug] " + msg));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        // 0. 到达判定
        double distToTarget = mc.thePlayer.getDistanceSq(globalTarget);
        // 如果垂直高度差小于2，且水平距离很近，才算到达
        boolean verticalMatch = Math.abs(mc.thePlayer.posY - globalTarget.getY()) < 2.0;

        if (globalTarget != null && distToTarget < 4.0 && verticalMatch) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        // 1. 路径生成触发
        if (!isCalculating) {
            boolean needsNewPath = (currentPath == null) || (currentPathIndex >= currentPath.size() - 8);
            if (needsNewPath) {
                generateNextSegment();
            }
        }
        
        // 2. 如果真的没有路径可走，重置按键并返回
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            resetKeys();
            return;
        }

        // 3. 执行移动
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

                // --- 垂直下落逻辑 ---
                if (distToGlobalSq < 2.5) { // 稍微放宽水平判定
                    if (globalTarget.getY() < startPos.getY()) {
                        // debug("检测到目标在正下方，寻找落点...");
                        
                        BlockPos dropSpot = findNearestDropSpot(startPos);
                        
                        if (dropSpot != null) {
                            debug(">>> 找到最佳落点: " + dropSpot + " 准备跳跃");
                            segmentTarget = dropSpot;
                            forceManualDrop = true;
                        } else {
                            // debug("警告: 四周未找到安全的下落点，尝试直连");
                            segmentTarget = globalTarget; 
                        }
                    } else {
                        segmentTarget = globalTarget;
                    }
                } else {
                    // --- 水平寻路逻辑 ---
                    double distToGlobal = Math.sqrt(distToGlobalSq);
                    double nx = dx / distToGlobal;
                    double nz = dz / distToGlobal;
                    double checkDist = Math.min(distToGlobal, MAX_SEGMENT_LENGTH);
                    
                    while (checkDist > MIN_SEGMENT_LENGTH) {
                        int tx = (int) (startPos.getX() + nx * checkDist);
                        int tz = (int) (startPos.getZ() + nz * checkDist);
                        BlockPos candidate = findSafeY(tx, tz, startPos.getY());
                        if (candidate != null) {
                            segmentTarget = candidate;
                            break; 
                        }
                        checkDist -= 10.0;
                    }
                    if (segmentTarget == null && distToGlobal < MAX_SEGMENT_LENGTH) {
                        if (isSafeToStand(globalTarget)) segmentTarget = globalTarget;
                    }
                }

                if (segmentTarget != null) {
                    final BlockPos finalTarget = segmentTarget;
                    final boolean manualDrop = forceManualDrop;
                    
                    // 只有非强制下落时才跑A*，如果是就在旁边的落点，直接构造路径
                    List<BlockPos> newSegment = null;
                    if (!manualDrop) {
                        newSegment = Pathfinder.computePath(startPos, segmentTarget, 4000);
                    }
                    
                    final List<BlockPos> calculatedPath = newSegment;

                    mc.addScheduledTask(() -> {
                        if (!isPathfinding) return;
                        
                        if (calculatedPath != null && !calculatedPath.isEmpty()) {
                            applyNewPath(calculatedPath);
                        } else if (manualDrop) {
                            // [关键修复] 手动构造下落路径
                            List<BlockPos> manualPath = new ArrayList<>();
                            manualPath.add(finalTarget);
                            applyNewPath(manualPath);
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

    /**
     * [关键修复] 应用新路径
     * 修复了距离太近导致唯一节点被删除，进而导致死循环的问题
     */
    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        
        // 剪裁逻辑：只有当路径节点多于1个时，才允许剪裁离得近的起始点
        while (newPath.size() > 1) {
            BlockPos node = newPath.get(0);
            Vec3 nodeCenter = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            double distSq = (playerPos.xCoord - nodeCenter.xCoord) * (playerPos.xCoord - nodeCenter.xCoord) + 
                            (playerPos.zCoord - nodeCenter.zCoord) * (playerPos.zCoord - nodeCenter.zCoord);
            
            // 如果离第一个点很近，且后面还有路，就把第一个点删了直接去第二个
            if (distSq < 1.0) { 
                newPath.remove(0);
            } else {
                break;
            }
        }
        
        // 如果剪裁后为空（理论上上面加了size>1判断，不会空，但为了保险）
        if (newPath.isEmpty()) {
            // debug("警告：新路径为空，跳过更新");
            return;
        }

        // 更新路径
        currentPath = new CopyOnWriteArrayList<>(newPath);
        currentPathIndex = 0;
        // debug("路径已更新，长度: " + newPath.size());
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
        
        // 强制平视逻辑：如果目标在下方，不低头，平视前方移动
        if (aimTarget.getY() < mc.thePlayer.posY - 0.5) {
            targetCenter = new Vec3(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        } else {
            targetCenter = targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        }
        
        float[] rotations = RotationUtil.getRotations(targetCenter);
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 40.0f); 
        float smoothPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], 40.0f);

        mc.thePlayer.rotationYaw = smoothYaw;
        // 如果目标在很下面，强制 pitch 为 0 (平视)
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
                // 忽略脚下的方块边缘
                if (hitPos.getY() > startPos.yCoord) {
                    Block hitBlock = mc.theWorld.getBlockState(hitPos).getBlock();
                    if (hitBlock.getMaterial().isSolid()) return false; 
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
                if (!isGroundSolid(candidate)) {
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

    private boolean isSafeToStep(BlockPos pos) {
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();
        Material mat = ground.getMaterial();
        if (!mat.isSolid() || mat.isLiquid() || ground == Blocks.cactus || ground == Blocks.web) return false;
        Block head = mc.theWorld.getBlockState(pos.up()).getBlock();
        if (head.getMaterial().isSolid()) return false;
        return true;
    }
    
    private boolean canWalkThrough(BlockPos pos) {
        Block body = mc.theWorld.getBlockState(pos).getBlock();
        Block head = mc.theWorld.getBlockState(pos.up()).getBlock();
        return !body.getMaterial().isSolid() && !head.getMaterial().isSolid();
    }
    
    private boolean isGroundSolid(BlockPos pos) {
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();
        return ground.getMaterial().isSolid();
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
        if (isSafeToStand(new BlockPos(x, startY, z))) return new BlockPos(x, startY, z);
        for (int y = startY + 1; y <= startY + 20 && y < 256; y++) {
            if (isSafeToStand(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        for (int y = startY - 1; y >= startY - 20 && y > 0; y--) {
            if (isSafeToStand(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        return null;
    }

    private boolean isSafeToStand(BlockPos pos) {
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        Block feet = mc.theWorld.getBlockState(pos).getBlock();
        Block head = mc.theWorld.getBlockState(pos.up()).getBlock();
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();
        return ground.getMaterial().isSolid() && !feet.getMaterial().isSolid() && !head.getMaterial().isSolid();
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
}