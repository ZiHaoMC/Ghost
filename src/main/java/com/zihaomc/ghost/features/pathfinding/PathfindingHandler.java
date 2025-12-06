package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random random = new Random();

    // --- 状态变量 ---
    private static boolean isPathfinding = false;
    
    // 当前正在走的“小段”路径
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    
    // 最终的“大目标”
    private static BlockPos globalTarget = null;
    
    // 寻路参数
    private static final int SEGMENT_LENGTH = 45; // 每次只算前方 45 格
    private static boolean isCalculating = false; // 防止重复计算

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
        resetKeys();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        // 0. 到达最终目标判断
        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 2.0) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达最终目的地！"));
            return;
        }

        // 1. 如果没有路径，或者当前路径快走完了，尝试生成下一段
        if (!isCalculating && (currentPath == null || currentPathIndex >= currentPath.size() - 2)) {
            generateNextSegment();
            // 如果生成后还是空的（比如在计算中），先暂停移动
            if (currentPath == null) {
                resetKeys();
                return;
            }
        }

        // 2. 执行走路逻辑 (和之前类似，但增加了对空路径的保护)
        if (currentPath != null && currentPathIndex < currentPath.size()) {
            followPath();
        }
    }

    /**
     * 核心：生成下一段路径
     */
    private void generateNextSegment() {
        isCalculating = true;
        
        // 在新线程计算，防止卡顿
        new Thread(() -> {
            try {
                BlockPos startPos;
                if (currentPath != null && !currentPath.isEmpty()) {
                    // 如果有旧路径，从旧路径的终点开始接续
                    startPos = currentPath.get(currentPath.size() - 1);
                } else {
                    // 否则从玩家脚下开始
                    startPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);
                }

                // 计算方向向量
                double dx = globalTarget.getX() - startPos.getX();
                double dz = globalTarget.getZ() - startPos.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);

                BlockPos segmentTarget;

                if (dist <= SEGMENT_LENGTH) {
                    // 如果距离很近，直接设为终点
                    segmentTarget = globalTarget;
                } else {
                    // 否则，向目标方向延伸 SEGMENT_LENGTH 的距离
                    double scale = SEGMENT_LENGTH / dist;
                    int targetX = (int) (startPos.getX() + dx * scale);
                    int targetZ = (int) (startPos.getZ() + dz * scale);
                    
                    // 寻找该 X,Z 处安全的 Y 坐标 (处理上下坡)
                    segmentTarget = findSafeY(targetX, targetZ, startPos.getY());
                }

                if (segmentTarget == null) {
                    // 找不到落脚点（可能是虚空或未加载区块），尝试缩短距离
                    segmentTarget = findSafeY((int)(startPos.getX() + dx * 0.5), (int)(startPos.getZ() + dz * 0.5), startPos.getY());
                }

                if (segmentTarget != null) {
                    // 调用原来的 A* 算法计算这小段路
                    List<BlockPos> newSegment = Pathfinder.computePath(startPos, segmentTarget, 2000);
                    
                    // 回到主线程更新路径
                    final List<BlockPos> finalSegment = newSegment;
                    mc.addScheduledTask(() -> {
                        if (!isPathfinding) return; // 任务可能已被取消
                        
                        if (finalSegment != null && !finalSegment.isEmpty()) {
                            // 如果是第一次，直接赋值
                            if (currentPath == null) {
                                currentPath = finalSegment;
                                currentPathIndex = 0;
                            } else {
                                // 如果是接续，因为是异步的，为了安全，我们直接替换为新路径
                                // 此时玩家可能已经走过了 startPos，所以需要重新定位 index
                                currentPath = finalSegment;
                                currentPathIndex = 0; 
                            }
                        } else {
                            // 寻路失败（可能是死路），这里可以做一些重试逻辑，或者直接停止
                            // 暂时简单处理：向玩家报错
                            // mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] 局部寻路受阻，尝试重新规划..."));
                        }
                        isCalculating = false;
                    });
                } else {
                    // 目标区域未加载，稍后重试
                    mc.addScheduledTask(() -> isCalculating = false);
                }

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false);
            }
        }).start();
    }

    /**
     * 在目标 X, Z 柱子上寻找一个能站立的 Y 坐标
     * 优先搜索当前高度附近
     */
    private BlockPos findSafeY(int x, int z, int startY) {
        // 先检查 startY
        if (isSafeToStand(new BlockPos(x, startY, z))) return new BlockPos(x, startY, z);

        // 向上搜 20 格
        for (int y = startY + 1; y <= startY + 20 && y < 256; y++) {
            if (isSafeToStand(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        
        // 向下搜 40 格 (下坡容易些)
        for (int y = startY - 1; y >= startY - 40 && y > 0; y--) {
            if (isSafeToStand(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        
        return null;
    }

    private boolean isSafeToStand(BlockPos pos) {
        // 必须要是：脚下是实心，脚本体和头是空气
        // 注意：这里需要要在主线程或者确保线程安全的方式获取 BlockState
        // 由于我们在异步线程，直接调用 mc.theWorld 可能有风险，但在 1.8.9 读取操作通常不会崩，
        // 只是可能读到空气。为了简单起见，这里假设区块已加载。
        
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false; // 区块未加载
        }

        Block feet = mc.theWorld.getBlockState(pos).getBlock();
        Block head = mc.theWorld.getBlockState(pos.up()).getBlock();
        Block ground = mc.theWorld.getBlockState(pos.down()).getBlock();

        // 简单的判定：脚下是固体，身子是空的
        boolean groundSolid = ground.getMaterial().isSolid();
        boolean feetClear = !feet.getMaterial().isSolid(); // 允许草/花，但不允许石头
        boolean headClear = !head.getMaterial().isSolid();

        return groundSolid && feetClear && headClear;
    }

    private void followPath() {
        // ... (保留你原有的移动逻辑) ...
        // 只是需要注意 currentPath 可能会在异步线程被重置，所以做好判空
        if (currentPath == null || currentPathIndex >= currentPath.size()) return;

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        
        // ... 原有的旋转、跳跃、按键逻辑 ...
        // (这里直接复制你原来 PathfindingHandler 的 onClientTick 里从 "--- 3. 到达判定 ---" 开始往下所有的逻辑即可)
        // 稍微修改一点：
        
        double distToTarget = mc.thePlayer.getDistanceSq(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        
        if (distToTarget < 0.5) { // 阈值
            currentPathIndex++;
            return;
        }
        
        // 旋转
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 20.0f); // 稍微快一点的转身
        mc.thePlayer.rotationYaw = smoothYaw;
        mc.thePlayer.rotationPitch = 0;

        // 移动
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(true); // 长途赶路可以疾跑

        // 自动跳跃 (简单版)
        if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) {
             KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
        } else {
             KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
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
        }
    }
}