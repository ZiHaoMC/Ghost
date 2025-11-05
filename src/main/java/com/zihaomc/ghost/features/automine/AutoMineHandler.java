package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

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

    // --- 智能黑名单和超时相关成员变量 ---
    // 黑名单现在存储导致黑名单的方块类型，而不是过期时间
    private static final ConcurrentHashMap<BlockPos, Block> unmineableBlacklist = new ConcurrentHashMap<>();
    private Long miningStartTime = null; // 记录开始挖掘当前方块的时间

    public static void toggle() {
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

        boolean hasTargets = !AutoMineTargetManager.targetBlocks.isEmpty() || !AutoMineTargetManager.targetBlockTypes.isEmpty();
        if (!hasTargets) {
            reset();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.no_targets_left")));
            return;
        }

        switch (currentState) {
            case SWITCHING_TARGET:
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                miningStartTime = null; 
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
                if (currentTarget == null) {
                    currentState = State.SWITCHING_TARGET; 
                    return;
                }
                
                IBlockState targetBlockState = mc.theWorld.getBlockState(currentTarget);
                Block blockAtTarget = targetBlockState.getBlock();

                boolean shouldSwitchTarget = false;
                if (blockAtTarget == Blocks.air) {
                    shouldSwitchTarget = true;
                } else if (isTargetValid(currentTarget)) { // isTargetValid 现在会处理黑名单逻辑
                    // 挖掘超时检查
                    long mineTimeoutMs = GhostConfig.AutoMine.mineTimeoutSeconds * 1000L;
                    if (miningStartTime == null) {
                        miningStartTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - miningStartTime > mineTimeoutMs) {
                        unmineableBlacklist.put(currentTarget, blockAtTarget);
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.mining_timeout_blacklisted", blockAtTarget.getLocalizedName(), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
                        shouldSwitchTarget = true;
                    }
                } else {
                    // 如果 isTargetValid 返回 false，说明方块要么不可见，要么在黑名单中，要么不可破坏等
                    shouldSwitchTarget = true;
                }
                
                // 如果需要切换目标
                if (shouldSwitchTarget) {
                    currentState = State.SWITCHING_TARGET;
                    return;
                }

                // 如果目标依然有效，继续挖掘
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
    
    private BlockPos findBestTarget() {
        BlockPos coordinateTarget = findNextCoordinate();
        if (coordinateTarget != null) {
            return coordinateTarget;
        }
        return findClosestBlock();
    }

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

    private BlockPos findClosestBlock() {
        if (AutoMineTargetManager.targetBlockTypes.isEmpty()) return null;

        int radius = GhostConfig.AutoMine.searchRadius;
        BlockPos playerPos = mc.thePlayer.getPosition();
        BlockPos closestBlock = null;
        double closestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.getAllInBox(playerPos.add(-radius, -radius, -radius), playerPos.add(radius, radius, radius))) {
            if (AutoMineTargetManager.targetBlockTypes.contains(mc.theWorld.getBlockState(pos).getBlock())) {
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
        // --- 智能黑名单检查 ---
        if (unmineableBlacklist.containsKey(pos)) {
            Block blacklistedBlock = unmineableBlacklist.get(pos);
            Block currentBlock = mc.theWorld.getBlockState(pos).getBlock();
            // 如果当前方块不再是当初被拉黑的那个方块，就把它从黑名单中移除
            if (currentBlock != blacklistedBlock) {
                unmineableBlacklist.remove(pos);
            } else {
                // 否则，它仍然是那个不可挖掘的方块，所以这个目标无效
                return false;
            }
        }

        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();

        // 检查方块是否是空气
        if (block == Blocks.air) {
            return false;
        }
        
        // 检查方块是否不可破坏 (硬度 < 0)
        if (block.getBlockHardness(mc.theWorld, pos) < 0) {
            unmineableBlacklist.put(pos, block); // 如果发现不可破坏，立即加入黑名单
            return false;
        }
        
        // (方块模式下) 检查方块是否还是我们想要挖掘的类型
        if (!AutoMineTargetManager.targetBlocks.contains(pos) && !AutoMineTargetManager.targetBlockTypes.contains(block)) {
            return false;
        }

        // 检查距离
        double reach = GhostConfig.AutoMine.maxReachDistance;
        Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (mc.thePlayer.getPositionEyes(1.0f).squareDistanceTo(blockCenter) > reach * reach) {
            return false;
        }

        // 检查是否可见
        return RotationUtil.getClosestVisiblePoint(pos) != null;
    }
}