package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
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

        // 0. 到达最终目标判断 (判定范围设为 2.0，稍微宽容一点避免在终点转圈)
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

        // 2. 执行走路逻辑
        followPath();
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

                // --- 关键逻辑：弹性目标搜索 ---
                // 从 MAX_SEGMENT_LENGTH 开始尝试，如果那个位置未加载或不可行走，就缩短距离重试
                // 这样能保证在视距边缘找到最远的那个可行点，而不会直接放弃
                double checkDist = Math.min(distToGlobal, MAX_SEGMENT_LENGTH);
                
                while (checkDist > MIN_SEGMENT_LENGTH) {
                    int tx = (int) (startPos.getX() + nx * checkDist);
                    int tz = (int) (startPos.getZ() + nz * checkDist);
                    
                    // 寻找该坐标处安全的 Y (处理上下坡)
                    BlockPos candidate = findSafeY(tx, tz, startPos.getY());
                    
                    if (candidate != null) {
                        segmentTarget = candidate;
                        break; // 找到了最远的有效点，停止搜索
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
                        } else {
                            // 寻路失败（有目标但算不出路），可能是死路，暂时待机
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
     * 解决“往回走”的关键
     */
    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        
        // 剪裁掉离玩家太近的起始节点
        // 只有当新路径的开头部分 实际上就是玩家刚才走过的路时，才需要剪裁
        while (!newPath.isEmpty()) {
            BlockPos node = newPath.get(0);
            Vec3 nodeCenter = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            
            // 计算水平距离平方
            double distSq = (playerPos.xCoord - nodeCenter.xCoord) * (playerPos.xCoord - nodeCenter.xCoord) + 
                            (playerPos.zCoord - nodeCenter.zCoord) * (playerPos.zCoord - nodeCenter.zCoord);

            // 如果节点在玩家 2 格范围内，或者是身后的点，视为已通过
            if (distSq < 2.5) {
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

    private BlockPos findSafeY(int x, int z, int startY) {
        // 优先检查同高度
        if (isSafeToStand(new BlockPos(x, startY, z))) return new BlockPos(x, startY, z);
        
        // 向上搜 (上坡)
        for (int y = startY + 1; y <= startY + 20 && y < 256; y++) {
            if (isSafeToStand(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        // 向下搜 (下坡)
        for (int y = startY - 1; y >= startY - 20 && y > 0; y--) {
            if (isSafeToStand(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        return null;
    }

    private boolean isSafeToStand(BlockPos pos) {
        // 必须检查 chunkExists，否则 getBlockState 会在未加载区域返回 Air (导致误判虚空) 或默认方块
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        
        Block feet = mc.theWorld.getBlockState(pos).getBlock();
        Block head = mc.theWorld.getBlockState(pos.up()).getBlock();
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();
        
        // 简单判定：脚下实心，脚和头是空的
        // 超平坦模式下 ground 通常是草方块或基岩，feet是空气
        return ground.getMaterial().isSolid() && !feet.getMaterial().isSolid() && !head.getMaterial().isSolid();
    }

    private void followPath() {
        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        
        double distToTarget = mc.thePlayer.getDistanceSq(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        
        // 靠近节点时切换到下一个
        if (distToTarget < 1.0) {
            currentPathIndex++;
            return;
        }
        
        // 旋转
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 30.0f); 
        mc.thePlayer.rotationYaw = smoothYaw;
        mc.thePlayer.rotationPitch = 0; // 走路平视即可

        // 移动
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        
        // 疾跑逻辑：距离远且前方没有急转弯时疾跑
        // 这里简化处理：只要在寻路就疾跑，效率最高
        mc.thePlayer.setSprinting(true);

        // 自动跳跃
        boolean jump = false;
        if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) {
            jump = true;
        }
        if (targetNode.getY() > mc.thePlayer.posY + 0.5) {
            jump = true;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
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