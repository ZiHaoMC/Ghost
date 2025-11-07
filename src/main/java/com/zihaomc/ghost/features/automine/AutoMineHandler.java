package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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

    private static final ConcurrentHashMap<BlockPos, Block> unmineableBlacklist = new ConcurrentHashMap<>();
    private Long miningStartTime = null; 
    
    // 用于跟踪潜行键是否由本模块控制
    private static boolean modIsControllingSneak = false;

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
        // 如果是本模块在控制潜行，则在关闭时松开按键
        if (modIsControllingSneak) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            modIsControllingSneak = false;
        }
        currentState = State.IDLE;
        currentTarget = null;
        isActive = false;
        unmineableBlacklist.clear();
    }
    
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

        if (!isActive) {
            // 安全检查：如果模块已关闭但潜行键仍被我们控制，则释放它
            if (modIsControllingSneak) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
                modIsControllingSneak = false;
            }
            return;
        }

        // --- 自动潜行逻辑 ---
        if (GhostConfig.AutoMine.sneakOnMine) {
            // 如果配置要求潜行，且潜行键当前没有按下，则按下它
            if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
            }
            modIsControllingSneak = true; // 标记我们正在控制
        } else {
            // 如果配置不要求潜行，但之前是我们在控制，则松开它
            if (modIsControllingSneak) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
                modIsControllingSneak = false;
            }
        }

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
                } else if (isTargetValid(currentTarget)) { 
                    long mineTimeoutMs = GhostConfig.AutoMine.mineTimeoutSeconds * 1000L;
                    if (miningStartTime == null) {
                        miningStartTime = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - miningStartTime > mineTimeoutMs) {
                        unmineableBlacklist.put(currentTarget, blockAtTarget);
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.mining_timeout_blacklisted", blockAtTarget.getLocalizedName(), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
                        shouldSwitchTarget = true;
                    }
                } else {
                    shouldSwitchTarget = true;
                }
                
                if (shouldSwitchTarget) {
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
    
    private BlockPos findBestTarget() {
        Set<BlockPos> candidates = new HashSet<>();

        for (BlockPos pos : AutoMineTargetManager.targetBlocks) {
            if (isTargetValid(pos)) {
                candidates.add(pos);
            }
        }

        if (!AutoMineTargetManager.targetBlockTypes.isEmpty()) {
            int radius = GhostConfig.AutoMine.searchRadius;
            BlockPos playerPos = mc.thePlayer.getPosition();
            for (BlockPos pos : BlockPos.getAllInBox(playerPos.add(-radius, -radius, -radius), playerPos.add(radius, radius, radius))) {
                if (AutoMineTargetManager.targetBlockTypes.contains(mc.theWorld.getBlockState(pos).getBlock())) {
                    if (isTargetValid(pos)) {
                        candidates.add(pos);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        BlockPos bestPos = null;
        double minScore = Double.MAX_VALUE;

        for (BlockPos candidate : candidates) {
            double angleDiff = getAngleDifferenceToBlock(candidate);
            double distanceSq = mc.thePlayer.getDistanceSq(candidate);
            
            double score = angleDiff * 10.0 + distanceSq;

            if (score < minScore) {
                minScore = score;
                bestPos = candidate;
            }
        }

        return bestPos;
    }

    private double getAngleDifferenceToBlock(BlockPos pos) {
        Vec3 targetVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        float[] rotations = RotationUtil.getRotations(targetVec);
        
        EntityPlayerSP player = mc.thePlayer;
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(player.rotationYaw - rotations[0]));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(player.rotationPitch - rotations[1]));
        
        return yawDiff * yawDiff + pitchDiff * pitchDiff;
    }

    private boolean isTargetValid(BlockPos pos) {
        if (unmineableBlacklist.containsKey(pos)) {
            Block blacklistedBlock = unmineableBlacklist.get(pos);
            Block currentBlock = mc.theWorld.getBlockState(pos).getBlock();
            if (currentBlock != blacklistedBlock) {
                unmineableBlacklist.remove(pos);
            } else {
                return false;
            }
        }

        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();

        if (block == Blocks.air) {
            return false;
        }
        
        if (GhostConfig.AutoMine.preventDiggingDown) {
            int playerFootY = MathHelper.floor_double(mc.thePlayer.posY);
            if (pos.getY() < playerFootY) {
                return false;
            }
        }

        if (block.getBlockHardness(mc.theWorld, pos) < 0) {
            unmineableBlacklist.put(pos, block); 
            return false;
        }
        
        if (!AutoMineTargetManager.targetBlocks.contains(pos) && !AutoMineTargetManager.targetBlockTypes.contains(block)) {
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