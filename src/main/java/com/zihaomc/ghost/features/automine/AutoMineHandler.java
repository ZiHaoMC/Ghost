package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * "Auto Mine" 功能的核心处理器。
 * 采用了持续旋转的逻辑和更智能的状态管理。
 */
public class AutoMineHandler {

    private enum State {
        IDLE,
        SWITCHING_TARGET,
        MINING,
        WAITING
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean isActive = false;
    private static int currentTargetIndex = -1;
    private static State currentState = State.IDLE;
    private int waitTicks = 0;

    public static void toggle() {
        if (AutoMineTargetManager.targetBlocks.isEmpty() && !isActive) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.no_targets")));
            return;
        }

        isActive = !isActive;
        String status = isActive ? EnumChatFormatting.GREEN + LangUtil.translate("ghost.generic.enabled") : EnumChatFormatting.RED + LangUtil.translate("ghost.generic.disabled");
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.keybind.toggle.automine") + " " + status));

        if (isActive) {
            currentState = State.SWITCHING_TARGET;
        } else {
            reset();
        }
    }

    private static void reset() {
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }
        currentState = State.IDLE;
        currentTargetIndex = -1;
        isActive = false;
    }

    public static boolean isActive() {
        return isActive;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) {
            if (!isActive && mc.gameSettings.keyBindAttack.isKeyDown() && currentState != State.IDLE) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            }
            return;
        }

        if (!isActive) return;

        if (AutoMineTargetManager.targetBlocks.isEmpty()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.command.list.empty")));
            reset();
            return;
        }

        if (currentState == State.SWITCHING_TARGET) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            int nextTargetIndex = findNextValidTarget();
            if (nextTargetIndex != -1) {
                currentTargetIndex = nextTargetIndex;
                currentState = State.MINING;
            } else {
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.status.waiting")));
                currentState = State.WAITING;
                waitTicks = 0;
            }
        }

        if (currentState == State.WAITING) {
            waitTicks++;
            if (waitTicks >= 20) {
                currentState = State.SWITCHING_TARGET;
            }
            return;
        }

        if (currentState == State.MINING) {
            BlockPos currentTarget = AutoMineTargetManager.targetBlocks.get(currentTargetIndex);

            if (mc.theWorld.isAirBlock(currentTarget) || !isTargetValid(currentTarget)) {
                currentState = State.SWITCHING_TARGET;
                return;
            }

            Vec3 bestPointToLookAt = RotationUtil.getClosestVisiblePoint(currentTarget);
            if (bestPointToLookAt == null) {
                currentState = State.SWITCHING_TARGET;
                return;
            }

            // --- 核心修复：重新引入 instantRotation 判断 ---
            float[] targetRots = RotationUtil.getRotations(bestPointToLookAt);
            if (GhostConfig.AutoMine.instantRotation) {
                // 如果启用瞬间旋转，直接设置玩家视角
                mc.thePlayer.rotationYaw = targetRots[0];
                mc.thePlayer.rotationPitch = targetRots[1];
            } else {
                // 否则，执行平滑旋转
                float[] smoothRots = RotationUtil.getSmoothRotations(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, targetRots[0], targetRots[1], (float) GhostConfig.AutoMine.rotationSpeed);
                mc.thePlayer.rotationYaw = smoothRots[0];
                mc.thePlayer.rotationPitch = smoothRots[1];
            }

            MovingObjectPosition mouseOver = mc.objectMouseOver;
            boolean isCrosshairOnTarget = mouseOver != null &&
                                          mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK &&
                                          mouseOver.getBlockPos().equals(currentTarget);

            if (isCrosshairOnTarget) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), true);
            } else {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                // 如果准星没对上，但目标仍然有效，下一tick会继续旋转，无需改变状态
                // 只有当目标本身失效时，才需要重新寻找
                if (!isTargetValid(currentTarget)) {
                    currentState = State.SWITCHING_TARGET;
                }
            }
        }
    }

    private int findNextValidTarget() {
        int listSize = AutoMineTargetManager.targetBlocks.size();
        if (listSize == 0) return -1;
        
        int startIndex = (currentTargetIndex + 1) % listSize;
        for (int i = 0; i < listSize; i++) {
            int indexToCheck = (startIndex + i) % listSize;
            BlockPos pos = AutoMineTargetManager.targetBlocks.get(indexToCheck);
            if (isTargetValid(pos)) {
                return indexToCheck;
            }
        }
        return -1;
    }

    private boolean isTargetValid(BlockPos pos) {
        if (mc.theWorld.isAirBlock(pos)) {
            return false;
        }

        double reach = GhostConfig.AutoMine.maxReachDistance;
        Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (mc.thePlayer.getPositionEyes(1.0f).squareDistanceTo(blockCenter) > reach * reach) {
            return false;
        }

        return RotationUtil.getClosestVisiblePoint(pos) != null;
    }
}