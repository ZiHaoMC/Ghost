package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;
import java.util.Random;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static List<BlockPos> currentPath = null;
    private static boolean isPathfinding = false;
    private static int currentPathIndex = 0;
    private static final Random random = new Random();

    public static void setPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) return;
        currentPath = path;
        currentPathIndex = 0;
        isPathfinding = true;
    }

    public static void stop() {
        isPathfinding = false;
        currentPath = null;
        resetKeys();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        // --- 1. 终点判定 ---
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 目的地已到达。"));
            return;
        }

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        double distToTarget = mc.thePlayer.getDistanceSq(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);

        if (currentPathIndex == currentPath.size() - 1 && distToTarget < 0.1) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 精准到达。"));
            return;
        }

        // --- 2. 前瞻与目标更新 ---
        updatePathIndexWithLookahead();
        targetNode = currentPath.get(currentPathIndex);
        targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);

        // --- 3. 到达判定 ---
        boolean isNarrow = isNarrowPath(new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ));
        double reachThreshold = isNarrow || (currentPathIndex == currentPath.size() - 1) ? 0.4 : 2.0; 
        
        distToTarget = mc.thePlayer.getDistanceSq(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        if (distToTarget < (reachThreshold * reachThreshold)) {
            currentPathIndex++;
            return;
        }

        // --- 4. 平滑旋转逻辑 (核心修改) ---
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        
        // 目标角度
        float targetYaw = rotations[0];
        float targetPitch = 0; // 走路通常平视即可，如果需要看路可以改为 rotations[1]

        // 随机旋转速度 (15 ~ 30 度/tick)，模拟真人手速波动
        float turnSpeed = 15.0f + random.nextFloat() * 15.0f;

        // 平滑插值：当前角度 -> 目标角度
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, targetYaw, turnSpeed);
        float smoothPitch = limitAngleChange(mc.thePlayer.rotationPitch, targetPitch, turnSpeed);

        // 应用旋转
        mc.thePlayer.rotationYaw = smoothYaw;
        mc.thePlayer.rotationPitch = smoothPitch;

        // --- 5. 移动控制 ---
        // 计算当前还要转多少度才能对准
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw));

        boolean moveForward = true;

        // 窄路保护：如果没对准 (>10度)，原地转头，不许走
        if (isNarrow && yawDiff > 10.0f) {
            moveForward = false;
        }
        
        // 普通转弯保护：如果角度差太大 (>45度)，减速/停顿，等待转头
        if (!isNarrow && yawDiff > 45.0f) {
            moveForward = false;
        }

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), moveForward);
        mc.thePlayer.setSprinting(false); 

        // --- 6. 跳跃逻辑 ---
        boolean needJump = false;
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) {
            needJump = true;
        } else if (targetNode.getY() > mc.thePlayer.posY + 0.1) {
             needJump = true;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), needJump);
    }

    /**
     * [新增] 限制角度变化幅度 (平滑核心)
     * 确保从 current 到 target 的变化量不超过 maxChange
     */
    private float limitAngleChange(float current, float target, float maxChange) {
        float change = MathHelper.wrapAngleTo180_float(target - current);
        if (change > maxChange) change = maxChange;
        if (change < -maxChange) change = -maxChange;
        return current + change;
    }

    private void updatePathIndexWithLookahead() {
        int searchLimit = 5;
        int maxIndex = Math.min(currentPathIndex + searchLimit, currentPath.size() - 1);
        
        for (int i = maxIndex; i > currentPathIndex; i--) {
            BlockPos node = currentPath.get(i);
            if (Math.abs(node.getY() - mc.thePlayer.posY) > 0.5) continue;
            if (canSeeAndWalkTo(node)) {
                currentPathIndex = i;
                return;
            }
        }
    }

    private boolean canSeeAndWalkTo(BlockPos target) {
        Vec3 start = mc.thePlayer.getPositionVector();
        Vec3 end = new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);

        Vec3 eyes = start.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        Vec3 targetEyes = new Vec3(target.getX() + 0.5, target.getY() + 1.5, target.getZ() + 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, targetEyes, false, true, false);
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }

        double dist = start.distanceTo(end);
        Vec3 dir = end.subtract(start).normalize();
        
        for (double d = 0; d < dist; d += 0.5) {
            Vec3 checkPos = start.addVector(dir.xCoord * d, dir.yCoord * d, dir.zCoord * d);
            BlockPos blockUnder = new BlockPos(checkPos).down();
            if (!isSafe(blockUnder)) return false;
        }

        return true;
    }

    private boolean isNarrowPath(BlockPos playerPos) {
        BlockPos under = playerPos.down();
        if (!isSafe(under)) under = under.down();

        boolean n = isSafe(under.north());
        boolean s = isSafe(under.south());
        boolean e = isSafe(under.east());
        boolean w = isSafe(under.west());

        return (!e && !w) || (!n && !s) || ((!n ? 1:0) + (!s ? 1:0) + (!e ? 1:0) + (!w ? 1:0) >= 3);
    }

    private boolean isSafe(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block instanceof BlockAir || block instanceof BlockLiquid) return false;
        return block.getCollisionBoundingBox(mc.theWorld, pos, mc.theWorld.getBlockState(pos)) != null;
    }

    private static void resetKeys() {
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
    }
}