package com.zihaomc.ghost.features.pathfinding;

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
    
    // 必须走到距离中心 0.4 格以内才算到达，确保不切角
    private static final double REACH_DISTANCE = 0.4; 

    public static void setPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) return;
        currentPath = path;
        currentPathIndex = 0;
        isPathfinding = true;
        
        // 简单的起点跳过：只跳过脚下那个点，防止原地转圈
        if (path.size() > 0) {
            BlockPos first = path.get(0);
            if (first.distanceSq(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ) < 1.5) {
                currentPathIndex = 1;
            }
        }
    }

    public static void stop() {
        isPathfinding = false;
        currentPath = null;
        resetKeys();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 目的地已到达。"));
            return;
        }

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);

        double distSqFlat = Math.pow(mc.thePlayer.posX - targetCenter.xCoord, 2) + Math.pow(mc.thePlayer.posZ - targetCenter.zCoord, 2);

        // 如果到达，切换下一个点
        if (distSqFlat < (REACH_DISTANCE * REACH_DISTANCE)) {
            currentPathIndex++;
            return;
        }

        // 旋转
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        mc.thePlayer.rotationYaw = rotations[0];
        mc.thePlayer.rotationPitch = 0; 

        // 移动
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(false); 

        // 跳跃：只在需要爬坡或游泳时跳
        boolean needJump = false;
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) {
            needJump = true;
        } else if (targetNode.getY() > mc.thePlayer.posY + 0.1) {
             needJump = true;
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