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

    public static void setPath(List<BlockPos> path) {
        currentPath = path;
        currentPathIndex = 0;
        
        // 简单的初始点优化
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

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) return;

        if (!isPathfinding || currentPath == null || currentPath.isEmpty()) {
            return;
        }

        if (currentPathIndex >= currentPath.size()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 目的地已到达。"));
            stop();
            return;
        }

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        
        // --- 视角控制 ---
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

        // --- 到达判定 ---
        double distSqFlat = Math.pow(mc.thePlayer.posX - targetCenter.xCoord, 2) + Math.pow(mc.thePlayer.posZ - targetCenter.zCoord, 2);
        if (distSqFlat < 0.25) { 
            currentPathIndex++;
            return;
        }

        // --- 移动控制 ---
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(false); 

        // --- 跳跃控制 ---
        boolean needJump = false;
        double speed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);

        // 如果在水中，始终尝试上浮
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) {
            needJump = true; 
        }
        else if (mc.thePlayer.onGround) {
            // 上台阶判定：目标Y > 玩家脚下Y (取整)
            // 注意：因为 Pathfinder 现在正确处理了楼梯，路径点会位于楼梯上方
            if (targetNode.getY() > Math.floor(mc.thePlayer.posY)) {
                needJump = true;
            }
            // 防卡死判定
            else if (mc.thePlayer.isCollidedHorizontally && speed < 0.05) {
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