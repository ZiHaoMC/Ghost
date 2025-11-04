package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // --- 黑名单和超时相关成员变量 ---
    private static final long BLACKLIST_DURATION_MS = 60000; // 黑名单持续60秒
    // 使用 ConcurrentHashMap 确保线程安全，并存储方块位置和加入黑名单的时间戳
    private static final ConcurrentHashMap<BlockPos, Long> unmineableBlacklist = new ConcurrentHashMap<>();
    private Long miningStartTime = null; // 记录开始挖掘当前方块的时间

    public static void toggle() {
        // 启动前检查：必须至少有一个坐标或一个方块类型被设置
        if (!isActive && AutoMineTargetManager.targetBlocks.isEmpty() && AutoMineTargetManager.targetBlockTypes.isEmpty()) {
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
        // 关闭时清空黑名单
        unmineableBlacklist.clear();
    }

    /**
     * 公开的静态方法，用于从外部（如命令）清空黑名单。
     */
    public static void clearBlacklist() {
        unmineableBlacklist.clear();
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

        // 刷新黑名单（移除过期的条目）
        refreshBlacklist();

        // 检查是否还有任何可挖掘的目标
        boolean hasTargets = !AutoMineTargetManager.targetBlocks.isEmpty() || !AutoMineTargetManager.targetBlockTypes.isEmpty();
        if (!hasTargets) {
            reset();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.no_targets_left")));
            return;
        }

        switch (currentState) {
            case SWITCHING_TARGET:
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                miningStartTime = null; // 重置挖掘计时
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
                if (waitTicks >= 20) { // 大约1秒后重新寻找目标
                    currentState = State.SWITCHING_TARGET;
                }
                break;

            case MINING:
                if (currentTarget == null) {
                    currentState = State.SWITCHING_TARGET; // 目标变为 null，重新寻找
                    return;
                }
                
                IBlockState targetBlockState = mc.theWorld.getBlockState(currentTarget);
                Block blockAtTarget = targetBlockState.getBlock();

                boolean targetConditionMet = false;
                if (blockAtTarget == Blocks.air) {
                    // 方块已被挖掉，是正常情况
                    targetConditionMet = true;
                } else if (unmineableBlacklist.containsKey(currentTarget)) {
                    // 方块在黑名单中
                    targetConditionMet = true; 
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.status.target_blacklisted", currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
                } else if (blockAtTarget.getBlockHardness(mc.theWorld, currentTarget) < 0) {
                    // 方块是不可破坏的 (例如基岩)，直接加入黑名单
                    unmineableBlacklist.put(currentTarget, System.currentTimeMillis() + BLACKLIST_DURATION_MS);
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.unmineable_block_blacklisted", blockAtTarget.getLocalizedName(), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
                    targetConditionMet = true; 
                } else if (!AutoMineTargetManager.targetBlocks.contains(currentTarget) && !AutoMineTargetManager.targetBlockTypes.contains(blockAtTarget)) {
                    // 如果当前目标既不在坐标列表中，也不在方块类型列表中（意味着它被替换了），则跳过
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.status.target_changed", blockAtTarget.getLocalizedName(), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
                    targetConditionMet = true; 
                }

                if (targetConditionMet) {
                    currentState = State.SWITCHING_TARGET;
                    miningStartTime = null; 
                    return;
                }

                // 检查是否挖掘超时
                long mineTimeoutMs = GhostConfig.AutoMine.mineTimeoutSeconds * 1000L;
                if (miningStartTime == null) {
                    miningStartTime = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - miningStartTime > mineTimeoutMs) {
                    // 挖掘超时，将方块加入黑名单并寻找下一个目标
                    unmineableBlacklist.put(currentTarget, System.currentTimeMillis() + BLACKLIST_DURATION_MS);
                    mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.mining_timeout_blacklisted", blockAtTarget.getLocalizedName(), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
                    currentState = State.SWITCHING_TARGET;
                    miningStartTime = null; 
                    return;
                }

                Vec3 bestPointToLookAt = RotationUtil.getClosestVisiblePoint(currentTarget);
                if (bestPointToLookAt == null) {
                    // 目标不可见，可能被其他方块阻挡，视为不可达，寻找下一个
                    currentState = State.SWITCHING_TARGET;
                    miningStartTime = null; 
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
     * 清理黑名单中已过期的条目。
     */
    private void refreshBlacklist() {
        long currentTime = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Long>> iterator = unmineableBlacklist.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            if (currentTime > entry.getValue()) {
                iterator.remove();
            }
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
        if (AutoMineTargetManager.targetBlockTypes.isEmpty()) return null;

        int radius = GhostConfig.AutoMine.searchRadius;
        BlockPos playerPos = mc.thePlayer.getPosition();
        BlockPos closestBlock = null;
        double closestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.getAllInBox(playerPos.add(-radius, -radius, -radius), playerPos.add(radius, radius, radius))) {
            Block blockAtPos = mc.theWorld.getBlockState(pos).getBlock();
            // 检查方块是否在我们的目标类型集合中
            if (AutoMineTargetManager.targetBlockTypes.contains(blockAtPos)) {
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
        // 1. 检查是否在黑名单中 (且未过期)
        if (unmineableBlacklist.containsKey(pos)) {
            return false;
        }

        // 2. 检查方块是否是空气
        if (mc.theWorld.isAirBlock(pos)) {
            return false;
        }
        
        // 3. 检查方块是否不可破坏 (硬度 < 0)
        if (mc.theWorld.getBlockState(pos).getBlock().getBlockHardness(mc.theWorld, pos) < 0) {
            return false;
        }

        // 4. 检查距离
        double reach = GhostConfig.AutoMine.maxReachDistance;
        Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (mc.thePlayer.getPositionEyes(1.0f).squareDistanceTo(blockCenter) > reach * reach) {
            return false;
        }

        // 5. 检查是否可见
        return RotationUtil.getClosestVisiblePoint(pos) != null;
    }
}