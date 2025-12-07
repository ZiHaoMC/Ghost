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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    // --- 状态变量 ---
    private static boolean isPathfinding = false;
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    
    // 最终的“大目标”
    private static BlockPos globalTarget = null;
    
    // 寻路参数
    private static final int MAX_SEGMENT_LENGTH = 80; // 尝试寻找的最远距离
    private static final int MIN_SEGMENT_LENGTH = 5;  // 最小搜寻距离
    private static boolean isCalculating = false; 

    public static void setGlobalTarget(BlockPos target) {
        globalTarget = target;
        currentPath = null;
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
    }

    public static void stop() {
        isPathfinding = false;
        currentPath = null;
        globalTarget = null;
        isCalculating = false;
        resetKeys();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        // 0. 到达最终目标判断
        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 4.0) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        // 1. 动态规划：生成下一段路径
        if (!isCalculating) {
            boolean needsNewPath = (currentPath == null) || (currentPathIndex >= currentPath.size() - 8);
            if (needsNewPath) {
                generateNextSegment();
            }
        }
        
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            resetKeys();
            return;
        }

        // 2. 执行平滑移动逻辑
        followPathSmoothed();
    }

    private void generateNextSegment() {
        isCalculating = true;
        final BlockPos startPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);

        new Thread(() -> {
            try {
                if (globalTarget == null) return;

                double dx = globalTarget.getX() - startPos.getX();
                double dz = globalTarget.getZ() - startPos.getZ();
                double distToGlobal = Math.sqrt(dx * dx + dz * dz);
                
                double nx = dx / distToGlobal;
                double nz = dz / distToGlobal;

                BlockPos segmentTarget = null;
                
                // 弹性目标搜索：从远到近寻找可行的落脚点
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
                    if (isSafeToStand(globalTarget)) {
                        segmentTarget = globalTarget;
                    }
                }

                if (segmentTarget != null) {
                    List<BlockPos> newSegment = Pathfinder.computePath(startPos, segmentTarget, 4000);
                    
                    final List<BlockPos> finalSegment = newSegment;
                    mc.addScheduledTask(() -> {
                        if (!isPathfinding) return;
                        
                        if (finalSegment != null && !finalSegment.isEmpty()) {
                            applyNewPath(finalSegment);
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

    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        
        while (!newPath.isEmpty()) {
            BlockPos node = newPath.get(0);
            Vec3 nodeCenter = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            
            // 计算水平距离平方
            double distSq = (playerPos.xCoord - nodeCenter.xCoord) * (playerPos.xCoord - nodeCenter.xCoord) + 
                            (playerPos.zCoord - nodeCenter.zCoord) * (playerPos.zCoord - nodeCenter.zCoord);

            if (distSq < 2.5) {
                newPath.remove(0);
            } else {
                break;
            }
        }

        if (newPath.isEmpty()) return;

        currentPath = new CopyOnWriteArrayList<>(newPath);
        currentPathIndex = 0;
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
        
        // 修正：Block 类没有 isLiquid()，必须从 Material 获取
        Material groundMat = ground.getMaterial();
        Material feetMat = feet.getMaterial();
        Material headMat = head.getMaterial();

        // 简单判定：脚下实心，且不是液体；脚和头是空的
        return groundMat.isSolid() && !groundMat.isLiquid() && !feetMat.isSolid() && !headMat.isSolid();
    }

    /**
     * 平滑移动逻辑：包含前瞻 (Look Ahead) 和防摔 (Ground Scan)
     */
    private void followPathSmoothed() {
        if (currentPath == null || currentPath.isEmpty()) return;

        updatePathIndexAutomatically();

        if (currentPathIndex >= currentPath.size()) return;

        // 尝试往后看最多 6 个节点
        BlockPos aimTarget = currentPath.get(currentPathIndex);
        
        int lookAheadDist = 6; 
        for (int i = 1; i <= lookAheadDist; i++) {
            int testIndex = currentPathIndex + i;
            if (testIndex >= currentPath.size()) break;
            
            BlockPos nextNode = currentPath.get(testIndex);
            // 只有当可以直接走到（无墙且地面安全）时才切换目标
            if (canWalkDirectly(mc.thePlayer.getPositionVector(), nextNode)) {
                aimTarget = nextNode;
            } else {
                break; 
            }
        }

        Vec3 targetCenter = new Vec3(aimTarget.getX() + 0.5, aimTarget.getY(), aimTarget.getZ() + 0.5);
        
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 40.0f); 
        mc.thePlayer.rotationYaw = smoothYaw;
        mc.thePlayer.rotationPitch = 0;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(true);

        boolean jump = false;
        if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) {
            jump = true;
        }
        if (aimTarget.getY() > mc.thePlayer.getEntityBoundingBox().minY + 0.5) {
            jump = true;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    /**
     * 判断当前位置是否可以直线走到目标方块
     * 1. 检查中间有没有墙 (视线检查)
     * 2. 检查脚下有没有路 (防摔落检查)
     */
    private boolean canWalkDirectly(Vec3 startPos, BlockPos end) {
        Vec3 endCenter = new Vec3(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        
        // 1. 墙壁阻挡检查
        Vec3 startEye = startPos.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        Vec3 endEye = endCenter.addVector(0, 1.5, 0);

        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startEye, endEye, false, true, false);

        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            if (!result.getBlockPos().equals(end)) {
                return false; // 被墙挡住了
            }
        }

        // 2. 地面塌陷/悬崖检查
        if (!isPathSafe(startPos, endCenter)) {
            return false; // 地面不安全
        }

        return true;
    }

    /**
     * 沿直线扫描地面，确保不会掉坑里
     */
    private boolean isPathSafe(Vec3 start, Vec3 end) {
        double dist = start.distanceTo(end);
        
        double dx = (end.xCoord - start.xCoord) / dist;
        double dy = (end.yCoord - start.yCoord) / dist;
        double dz = (end.zCoord - start.zCoord) / dist;

        double stepSize = 0.5;
        
        double currentX = start.xCoord;
        double currentY = start.yCoord; 
        double currentZ = start.zCoord;

        for (double d = 0; d < dist; d += stepSize) {
            currentX += dx * stepSize;
            currentY += dy * stepSize;
            currentZ += dz * stepSize;

            BlockPos posToCheck = new BlockPos(currentX, currentY, currentZ);
            
            if (!isSafeToStep(posToCheck)) {
                // 如果脚下这格不安全，检查一下下面一格 (可能是下坡)
                if (!isSafeToStep(posToCheck.down())) {
                    return false; 
                }
            }
        }
        return true;
    }

    /**
     * 修正后的 isSafeToStep：包含正确的 Import 和逻辑
     */
    private boolean isSafeToStep(BlockPos pos) {
        // 检查脚下 (pos.down) 是否是实心方块
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();
        Material mat = ground.getMaterial();
        
        // 修复：使用 mat.isLiquid()
        if (!mat.isSolid() || mat.isLiquid() || ground == Blocks.cactus || ground == Blocks.web) {
            return false;
        }
        
        // 检查头顶空间
        Block head = mc.theWorld.getBlockState(pos.up()).getBlock();
        if (head.getMaterial().isSolid()) {
            return false;
        }

        return true;
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