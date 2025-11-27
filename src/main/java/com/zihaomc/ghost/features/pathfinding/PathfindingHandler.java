package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.LogUtil;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static List<BlockPos> currentPath = null;
    private static boolean isPathfinding = false;
    private static int currentPathIndex = 0;
    
    // 旋转速度
    private static final float ROTATION_SPEED = 20.0f;
    
    // 卡死检测相关变量
    private static int stuckTicks = 0;
    private static BlockPos lastPosition = null;

    public static void setPath(List<BlockPos> path) {
        currentPath = path;
        currentPathIndex = 0;
        stuckTicks = 0;
        lastPosition = null;
        
        // 如果起步点就在脚下，直接跳过
        if (path.size() > 1 && path.get(0).distanceSq(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ) < 1.0) {
            currentPathIndex = 1;
        }
        
        isPathfinding = true;
    }

    public static void stop() {
        isPathfinding = false;
        currentPath = null;
        resetKeys();
    }

    public static boolean isPathfinding() {
        return isPathfinding;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) return;

        if (!isPathfinding || currentPath == null || currentPath.isEmpty()) {
            return;
        }

        // 1. 到达终点检测
        if (currentPathIndex >= currentPath.size()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 目的地已到达。"));
            stop();
            return;
        }

        // 2. 卡死检测 (Stuck Check)
        // 每 20 ticks (1秒) 检查一次位置
        if (mc.thePlayer.ticksExisted % 20 == 0) {
            BlockPos currentPos = mc.thePlayer.getPosition();
            if (lastPosition != null && currentPos.distanceSq(lastPosition) < 1.0) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
                lastPosition = currentPos;
            }
            // 如果卡住超过 3 秒 (3次检查)
            if (stuckTicks > 3) {
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] 检测到卡死，自动停止寻路。"));
                stop();
                return;
            }
        }

        // 3. 目标获取与视角旋转
        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        
        float[] targetRots = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        float[] smoothRots = RotationUtil.getSmoothRotations(
                mc.thePlayer.rotationYaw, 
                mc.thePlayer.rotationPitch, 
                targetRots[0], 
                targetRots[1], 
                ROTATION_SPEED
        );
        mc.thePlayer.rotationYaw = smoothRots[0];
        mc.thePlayer.rotationPitch = smoothRots[1];

        // 4. 到达判定
        double distSqFlat = Math.pow(mc.thePlayer.posX - targetCenter.xCoord, 2) + Math.pow(mc.thePlayer.posZ - targetCenter.zCoord, 2);
        
        // 到达当前节点，切换下一个
        if (distSqFlat < 0.25) { 
            currentPathIndex++;
            return;
        }

        // 5. 移动逻辑
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);

        // [优化] 智能疾跑 (Smart Sprinting)
        // 条件：距离下一个点大于 3 格，且没有水平碰撞 (路况良好)
        boolean shouldSprint = distSqFlat > 9.0 && !mc.thePlayer.isCollidedHorizontally;
        mc.thePlayer.setSprinting(shouldSprint);

        // 6. 跳跃与游泳逻辑
        boolean needJump = false;
        double speed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);

        // [优化] 水下求生：如果在水里，始终尝试上浮
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) {
            needJump = true; 
        }
        // 地面跳跃判定
        else if (mc.thePlayer.onGround) {
            // 情况A: 上楼梯/跳台阶
            if (targetNode.getY() > Math.round(mc.thePlayer.posY)) {
                needJump = true;
            }
            // 情况B: 确实卡住了 (有碰撞且速度归零)
            else if (mc.thePlayer.isCollidedHorizontally && speed < 0.05) {
                needJump = true;
            }
        }
        
        // 执行跳跃
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), needJump);
    }

    private static void resetKeys() {
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
    }
}