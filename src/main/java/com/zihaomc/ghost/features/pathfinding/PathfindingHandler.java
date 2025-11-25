package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.config.GhostConfig;
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

    public static void setPath(List<BlockPos> path) {
        currentPath = path;
        currentPathIndex = 0;
        isPathfinding = true;
        LogUtil.info("Pathfinding started. Nodes: " + path.size());
    }

    public static void stop() {
        isPathfinding = false;
        currentPath = null;
        resetKeys();
        LogUtil.info("Pathfinding stopped.");
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

        if (currentPathIndex >= currentPath.size()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 目的地已到达。"));
            stop();
            return;
        }

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        
        // 距离检测
        double dist = mc.thePlayer.getDistanceSq(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        
        // 简单的到达判断
        if (dist < 0.5) {
            currentPathIndex++;
            if (currentPathIndex >= currentPath.size()) return;
            targetNode = currentPath.get(currentPathIndex);
            targetCenter = new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
        }

        // 旋转视角
        float[] rotations = RotationUtil.getRotations(targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0));
        mc.thePlayer.rotationYaw = rotations[0];
        mc.thePlayer.rotationPitch = rotations[1]; // 也可以设为0保持平视

        // 移动控制
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(false); // 防止因疾跑导致从路径上冲出去

        // 跳跃逻辑
        boolean needJump = false;
        if (targetNode.getY() > mc.thePlayer.posY + 0.5) { // 目标比脚下高
             needJump = true;
        } else if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) {
             needJump = true; // 遇到障碍物
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