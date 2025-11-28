package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.LogUtil;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static List<BlockPos> currentPath = null;
    private static boolean isPathfinding = false;
    private static int currentPathIndex = 0;
    
    private static final float ROTATION_SPEED = 20.0f; 
    private static int stuckTicks = 0;
    private static BlockPos lastPosition = null;
    
    // 调试日志
    private static final boolean DEBUG_HANDLER = true;

    public static void setPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] 错误：无法找到通往目标的路径 (可能是目标不可达)。"));
            isPathfinding = false;
            return;
        }

        currentPath = path;
        currentPathIndex = 0;
        stuckTicks = 0;
        lastPosition = null;
        
        // 如果路径点多于1个，且第0个点离我很近，则直接从第1个点开始走
        if (path.size() > 1 && path.get(0).distanceSq(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ) < 2.0) {
            currentPathIndex = 1;
        }
        
        isPathfinding = true;
    }

    public static void stop() {
        if (isPathfinding) {
            isPathfinding = false;
            currentPath = null;
            resetKeys();
            if (DEBUG_HANDLER) LogUtil.info("[Handler] 寻路已停止");
        }
    }

    public static boolean isPathfinding() {
        return isPathfinding;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) return;

        if (!isPathfinding || currentPath == null || currentPath.isEmpty()) return;

        // 到达检查
        if (currentPathIndex >= currentPath.size()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 目的地已到达。"));
            stop();
            return;
        }

        // --- 卡死检测 ---
        if (mc.thePlayer.ticksExisted % 20 == 0) {
            BlockPos currentPos = mc.thePlayer.getPosition();
            if (lastPosition != null && currentPos.distanceSq(lastPosition) < 0.5) { // 稍微放宽一点
                stuckTicks++;
            } else {
                stuckTicks = 0;
                lastPosition = currentPos;
            }
            if (stuckTicks > 4) { // 延长到 4秒
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] 检测到长时间卡死，自动停止。"));
                stop();
                return;
            }
        }

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        
        // 视角
        float[] targetRots = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        float[] smoothRots = RotationUtil.getSmoothRotations(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, targetRots[0], targetRots[1], ROTATION_SPEED);
        mc.thePlayer.rotationYaw = smoothRots[0];
        mc.thePlayer.rotationPitch = smoothRots[1];

        // 距离判定
        double distSqFlat = Math.pow(mc.thePlayer.posX - targetCenter.xCoord, 2) + Math.pow(mc.thePlayer.posZ - targetCenter.zCoord, 2);
        if (distSqFlat < 0.25) { 
            currentPathIndex++;
            return;
        }

        // 移动
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(false); 

        // 跳跃
        boolean needJump = false;
        double speed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);

        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) {
            needJump = true; 
        }
        else if (mc.thePlayer.onGround) {
            if (targetNode.getY() > Math.floor(mc.thePlayer.posY)) {
                needJump = true;
            }
            // 只有极低速度且有碰撞才跳，防止蹭墙跳
            else if (mc.thePlayer.isCollidedHorizontally && speed < 0.01) {
                needJump = true;
            }
        }
        
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), needJump);
    }

    private static void resetKeys() {
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
    }
}