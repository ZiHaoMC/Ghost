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
    
    // 当前正在走的“小段”路径
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    
    // 最终的“大目标”
    private static BlockPos globalTarget = null;
    
    // 寻路参数
    private static final int MAX_SEGMENT_LENGTH = 80; // 尝试寻找的最远单段距离
    private static final int MIN_SEGMENT_LENGTH = 5;  // 最小搜寻距离
    private static boolean isCalculating = false;     // 防止重复计算

    /**
     * 设置长途目标并启动
     */
    public static void setGlobalTarget(BlockPos target) {
        globalTarget = target;
        currentPath = null;
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
    }

    /**
     * 停止寻路
     */
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

        // 0. 到达最终目标判断 (判定范围设为 4.0，宽容一点)
        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 4.0) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        // 1. 动态规划：如果没有路径，或者当前路径快走完了（剩余节点少于 8 个），尝试生成下一段
        if (!isCalculating) {
            boolean needsNewPath = (currentPath == null) || (currentPathIndex >= currentPath.size() - 8);
            if (needsNewPath) {
                generateNextSegment();
            }
        }
        
        // 如果当前没有路径可走（正在计算中，或者计算失败），停止按键，防止乱跑
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            resetKeys();
            return;
        }

        // 2. 执行平滑移动逻辑
        followPathSmoothed();
    }

    /**
     * 核心：生成下一段路径
     * 采用“回退搜索”策略，解决区块未加载导致不走路的问题
     */
    private void generateNextSegment() {
        isCalculating = true;
        
        // 获取计算时的起点
        final BlockPos startPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);

        new Thread(() -> {
            try {
                if (globalTarget == null) return;

                // 计算方向向量
                double dx = globalTarget.getX() - startPos.getX();
                double dz = globalTarget.getZ() - startPos.getZ();
                double distToGlobal = Math.sqrt(dx * dx + dz * dz);
                
                // 归一化方向
                double nx = dx / distToGlobal;
                double nz = dz / distToGlobal;

                BlockPos segmentTarget = null;

                // --- 弹性目标搜索 ---
                // 从最远距离开始尝试，如果那个位置不可用，就缩短距离重试
                double checkDist = Math.min(distToGlobal, MAX_SEGMENT_LENGTH);
                
                while (checkDist > MIN_SEGMENT_LENGTH) {
                    int tx = (int) (startPos.getX() + nx * checkDist);
                    int tz = (int) (startPos.getZ() + nz * checkDist);
                    
                    // 寻找该坐标处安全的 Y (处理上下坡)
                    BlockPos candidate = findSafeY(tx, tz, startPos.getY());
                    
                    if (candidate != null) {
                        segmentTarget = candidate;
                        break; // 找到了最远的有效点
                    }
                    
                    checkDist -= 10.0; // 缩短 10 格再次尝试
                }

                // 如果实在找不到（比如脸贴着虚空），最后尝试一下终点
                if (segmentTarget == null && distToGlobal < MAX_SEGMENT_LENGTH) {
                    if (isSafeToStand(globalTarget)) {
                        segmentTarget = globalTarget;
                    }
                }

                if (segmentTarget != null) {
                    // A* 计算
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
                    // 找不到任何落脚点（前方未加载），稍后重试
                    mc.addScheduledTask(() -> isCalculating = false);
                }

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false);
            }
        }).start();
    }

    /**
     * 应用新路径，并执行【防回弹剪裁】
     */
    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        
        // 剪裁掉离玩家太近的起始节点
        while (!newPath.isEmpty()) {
            BlockPos node = newPath.get(0);
            Vec3 nodeCenter = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            
            // 计算水平距离平方
            double distSq = (playerPos.xCoord - nodeCenter.xCoord) * (playerPos.xCoord - nodeCenter.xCoord) + 
                            (playerPos.zCoord - nodeCenter.zCoord) * (playerPos.zCoord - nodeCenter.zCoord);

            // 如果节点在玩家 2.5 格范围内，或者是身后的点，视为已通过
            if (distSq < 2.5 * 2.5) {
                newPath.remove(0);
            } else {
                break;
            }
        }

        if (newPath.isEmpty()) return;

        // 替换当前路径
        currentPath = new CopyOnWriteArrayList<>(newPath);
        currentPathIndex = 0;
    }

    /**
     * [核心优化] 平滑移动逻辑
     * 不再盯着 index 看，而是往前“展望”
     */
    private void followPathSmoothed() {
        if (currentPath == null || currentPath.isEmpty()) return;

        // 1. 自动吸附进度：如果玩家抄近道走到了路径后面，更新 currentPathIndex
        updatePathIndexAutomatically();

        if (currentPathIndex >= currentPath.size()) return;

        // 2. 前瞻目标选择 (Look Ahead)
        // 尝试往后看最多 6 个节点，如果能直线看到，就直接对准那个点
        BlockPos aimTarget = currentPath.get(currentPathIndex);
        
        int lookAheadDist = 6; // 前瞻距离
        for (int i = 1; i <= lookAheadDist; i++) {
            int testIndex = currentPathIndex + i;
            if (testIndex >= currentPath.size()) break;
            
            BlockPos nextNode = currentPath.get(testIndex);
            if (canWalkDirectly(mc.thePlayer.getPositionVector(), nextNode)) {
                aimTarget = nextNode;
            } else {
                break; // 视线被挡或地面不安全
            }
        }

        Vec3 targetCenter = new Vec3(aimTarget.getX() + 0.5, aimTarget.getY(), aimTarget.getZ() + 0.5);
        
        // 3. 旋转与移动
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        
        // 平滑旋转
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 40.0f); 
        mc.thePlayer.rotationYaw = smoothYaw;
        mc.thePlayer.rotationPitch = 0;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(true);

        // 4. 自动跳跃逻辑
        boolean jump = false;
        if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) {
            jump = true;
        }
        // 如果目标点比当前脚下高 (处理台阶)
        if (aimTarget.getY() > mc.thePlayer.getEntityBoundingBox().minY + 0.5) {
            jump = true;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    /**
     * [修复版] 判断当前位置是否可以直线走到目标方块
     * 1. 检查中间有没有墙 (视线检查)
     * 2. 检查脚下有没有路 (地面扫描)
     */
    private boolean canWalkDirectly(Vec3 startPos, BlockPos end) {
        Vec3 endCenter = new Vec3(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        
        // --- 1. 墙壁阻挡检查 ---
        Vec3 startEye = startPos.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        Vec3 endEye = endCenter.addVector(0, 1.5, 0); // 视线看方块上方一点

        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startEye, endEye, false, true, false);

        // 如果射线碰到了非目标方块的东西，说明有墙
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            if (!result.getBlockPos().equals(end)) {
                return false; // 被墙挡住了
            }
        }

        // --- 2. 地面塌陷/悬崖检查 ---
        if (!isPathSafe(startPos, endCenter)) {
            return false; // 地面不安全（有坑）
        }

        return true;
    }

    /**
     * 沿直线扫描地面，确保不会掉坑里
     */
    private boolean isPathSafe(Vec3 start, Vec3 end) {
        double dist = start.distanceTo(end);
        
        // 计算单位方向向量
        double dx = (end.xCoord - start.xCoord) / dist;
        double dy = (end.yCoord - start.yCoord) / dist;
        double dz = (end.zCoord - start.zCoord) / dist;

        double stepSize = 0.5; // 步长
        double currentX = start.xCoord;
        double currentY = start.yCoord; 
        double currentZ = start.zCoord;

        // 循环直到接近终点
        for (double d = 0; d < dist; d += stepSize) {
            currentX += dx * stepSize;
            currentY += dy * stepSize;
            currentZ += dz * stepSize;

            BlockPos posToCheck = new BlockPos(currentX, currentY, currentZ);
            
            // 检查逻辑：下一格应该是实心 (落脚点)
            if (!isSafeToStep(posToCheck)) {
                // 如果脚下这格不安全，检查一下下面一格 (可能是下坡)
                if (!isSafeToStep(posToCheck.down())) {
                    return false; // 确实是悬崖或虚空
                }
            }
        }
        return true;
    }

    /**
     * 检查某个坐标是否可以作为落脚点
     */
    private boolean isSafeToStep(BlockPos pos) {
        // 检查区块是否加载
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) return false;

        // 检查脚下 (pos.down) 是否是实心方块
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();
        Material mat = ground.getMaterial();
        
        // 如果脚下是空气、液体或者仙人掌等危险物，视为不安全
        // 修复：使用 mat.isLiquid() 替代 ground.isLiquid()
        if (!mat.isSolid() || mat.isLiquid() || ground == Blocks.cactus || ground == Blocks.web) {
            return false;
        }
        
        // 检查头顶空间 (防止走进只有1格高的洞里卡住)
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