package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
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
 * "Auto Mine" 功能的核心处理器，支持坐标和方块类型两种模式。
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
    private static BlockPos currentTarget = null;
    private static State currentState = State.IDLE;
    private int waitTicks = 0;

    public static void toggle() {
        // 启动前检查：必须至少有一个坐标或一个方块类型被设置
        if (!isActive && AutoMineTargetManager.targetBlocks.isEmpty() && AutoMineTargetManager.targetBlockType == null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.no_targets_set")));
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
        currentTarget = null;
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

        // 检查是否还有任何可挖掘的目标
        boolean hasTargets = !AutoMineTargetManager.targetBlocks.isEmpty() || AutoMineTargetManager.targetBlockType != null;
        if (!hasTargets) {
            reset();
            return;
        }

        switch (currentState) {
            case SWITCHING_TARGET:
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                currentTarget = findBestTarget();
                if (currentTarget != null) {
                    currentState = State.MINING;
                } else {
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.status.waiting")));
                    currentState = State.WAITING;
                    waitTicks = 0;
                }
                break;

            case WAITING:
                waitTicks++;
                if (waitTicks >= 20) {
                    currentState = State.SWITCHING_TARGET;
                }
                break;

            case MINING:
                if (currentTarget == null || mc.theWorld.isAirBlock(currentTarget) || !isTargetValid(currentTarget)) {
                    currentState = State.SWITCHING_TARGET;
                    return;
                }

                Vec3 bestPointToLookAt = RotationUtil.getClosestVisiblePoint(currentTarget);
                if (bestPointToLookAt == null) {
                    currentState = State.SWITCHING_TARGET;
                    return;
                }

                float[] targetRots = RotationUtil.getRotations(bestPointToLookAt);
                if (GhostConfig.AutoMine.instantRotation) {
                    mc.thePlayer.rotationYaw = targetRots[0];
                    mc.thePlayer.rotationPitch = targetRots[1];
                } else {
                    float[] smoothRots = RotationUtil.getSmoothRotations(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, targetRots[0], targetRots[1], (float) GhostConfig.AutoMine.rotationSpeed);
                    mc.thePlayer.rotationYaw = smoothRots[0];
                    mc.thePlayer.rotationPitch = smoothRots[1];
                }

                MovingObjectPosition mouseOver = mc.objectMouseOver;
                boolean isCrosshairOnTarget = mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mouseOver.getBlockPos().equals(currentTarget);

                if (isCrosshairOnTarget) {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), true);
                } else {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                }
                break;
        }
    }
    
    /**
     * 按优先级寻找最佳目标：先找坐标列表，再找方块类型。
     */
    private BlockPos findBestTarget() {
        // 1. 优先在坐标列表中寻找下一个有效目标
        BlockPos coordinateTarget = findNextCoordinate();
        if (coordinateTarget != null) {
            return coordinateTarget;
        }

        // 2. 如果坐标列表没有有效目标，则寻找最近的方块类型目标
        BlockPos blockTypeTarget = findClosestBlock();
        if (blockTypeTarget != null) {
            return blockTypeTarget;
        }
        
        // 两种都没找到
        return null;
    }

    /**
     * 在坐标列表中寻找下一个有效的方块。
     */
    private BlockPos findNextCoordinate() {
        if (AutoMineTargetManager.targetBlocks.isEmpty()) {
            return null;
        }

        int listSize = AutoMineTargetManager.targetBlocks.size();
        int currentIndex = (currentTarget != null) ? AutoMineTargetManager.targetBlocks.indexOf(currentTarget) : -1;
        int startIndex = (currentIndex + 1) % listSize;

        for (int i = 0; i < listSize; i++) {
            int indexToCheck = (startIndex + i) % listSize;
            BlockPos pos = AutoMineTargetManager.targetBlocks.get(indexToCheck);
            if (isTargetValid(pos)) {
                return pos;
            }
        }
        return null;
    }

    /**
     * 在玩家周围寻找最近的、指定类型的方块。
     */
    private BlockPos findClosestBlock() {
        Block targetType = AutoMineTargetManager.targetBlockType;
        if (targetType == null) return null;

        int radius = GhostConfig.AutoMine.searchRadius;
        BlockPos playerPos = mc.thePlayer.getPosition();
        BlockPos closestBlock = null;
        double closestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.getAllInBox(playerPos.add(-radius, -radius, -radius), playerPos.add(radius, radius, radius))) {
            if (mc.theWorld.getBlockState(pos).getBlock() == targetType) {
                if (isTargetValid(pos)) {
                    double distSq = mc.thePlayer.getDistanceSq(pos);
                    if (distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closestBlock = pos;
                    }
                }
            }
        }
        return closestBlock;
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