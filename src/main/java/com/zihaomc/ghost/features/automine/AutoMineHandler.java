package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.event.ClickEvent;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Auto Mine" 功能的核心处理器。
 * (重构后) 负责状态管理、目标搜寻，并将具体的挖掘行为委托给策略对象。
 * 同时处理反作弊回弹检测。
 */
public class AutoMineHandler {

    public enum MiningMode {
        SIMULATE,
        PACKET_NORMAL,
        PACKET_INSTANT
    }

    private enum State {
        IDLE,
        SWITCHING_TARGET,
        MINING,
        WAITING,
        VALIDATING_BREAK // 新增状态：用于验证方块是否真的被破坏
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean isActive = false;
    private static BlockPos currentTarget = null;
    private static State currentState = State.IDLE;
    private int waitTicks = 0;

    private static final ConcurrentHashMap<BlockPos, Block> unmineableBlacklist = new ConcurrentHashMap<>();
    private Long miningStartTime = null;

    private static boolean modIsControllingSneak = false;

    private static int randomMoveTicks = 0;
    private static int currentMoveDuration = 0;
    private static KeyBinding currentMoveKey = null;

    private static IBlockState lastMinedState = null;
    private static final int DEFAULT_WEIGHT = 10;

    private static IMiningStrategy currentStrategy = new SimulateMiningStrategy();
    private static MiningMode currentMiningMode = MiningMode.SIMULATE;
    
    private static boolean isPausedByGui = false;
    
    // --- 反作弊回弹检测相关 ---
    private static BlockPos blockToValidate = null;
    private static int validationTicks = 0;
    private static final int VALIDATION_DELAY_TICKS = 8; // 等待 8 ticks (0.4秒) 来确认方块破坏
    private static boolean awaitingRollbackConfirmation = false;
    private static int packetBreaksSinceStart = 0; // 新增：计数器，记录开始后数据包挖掘的次数
    private static final int CHECKS_TO_PERFORM = 3; // 新增：只检查前3次挖掘
    private static boolean checksTemporarilyDisabled = false; // 新增：在本轮游戏中临时禁用检查

    public static void toggle() {
        if (awaitingRollbackConfirmation) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.rollback_confirm_pending")));
            return;
        }

        if (!isActive && AutoMineTargetManager.targetBlocks.isEmpty() && AutoMineTargetManager.targetBlockTypes.isEmpty()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.no_targets_set")));
            return;
        }

        isActive = !isActive;
        isPausedByGui = false;
        
        String status = isActive ? EnumChatFormatting.GREEN + LangUtil.translate("ghost.generic.enabled") : EnumChatFormatting.RED + LangUtil.translate("ghost.generic.disabled");
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.keybind.toggle.automine") + " " + status));

        if (isActive) {
            currentState = State.SWITCHING_TARGET;
            // <--- 核心修复点之一：只在开始时重置挖掘计数器 ---
            packetBreaksSinceStart = 0;
        } else {
            reset();
            // <--- 核心修复点之二：只在手动停止时，才重置“临时禁用”状态，为下一次手动开启做准备 ---
            checksTemporarilyDisabled = false;
        }
    }

    private static void reset() {
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }
        if (modIsControllingSneak) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            modIsControllingSneak = false;
        }
        if (currentMoveKey != null) {
            KeyBinding.setKeyBindState(currentMoveKey.getKeyCode(), false);
            currentMoveKey = null;
        }
        if (currentStrategy != null) {
            currentStrategy.onStopMining();
        }
        
        randomMoveTicks = 0;
        currentMoveDuration = 0;
        currentState = State.IDLE;
        currentTarget = null;
        isActive = false;
        unmineableBlacklist.clear();
        lastMinedState = null;
        blockToValidate = null;
        validationTicks = 0;
        // awaitingRollbackConfirmation 在 reset 时不清空，由用户选择决定
    }
    
    public static void setMiningMode(MiningMode mode) {
        if (currentMiningMode != mode) {
            currentMiningMode = mode;
            GhostConfig.setAutoMineMiningMode(mode.name());
            
            switch(mode) {
                case SIMULATE:
                    currentStrategy = new SimulateMiningStrategy();
                    break;
                case PACKET_NORMAL:
                    currentStrategy = new PacketNormalMiningStrategy();
                    break;
                case PACKET_INSTANT:
                    currentStrategy = new PacketInstantMiningStrategy();
                    break;
            }
            
            if (isActive) {
                reset();
                toggle();
            }
            mc.thePlayer.addChatMessage(new ChatComponentText(LangUtil.translate("ghost.automine.command.mode.set", mode.name())));

            if (mode == MiningMode.PACKET_NORMAL || mode == MiningMode.PACKET_INSTANT) {
                mc.thePlayer.addChatMessage(new ChatComponentText(" "));
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "==================== [ " + EnumChatFormatting.YELLOW + "风险警告" + EnumChatFormatting.RED + " ] ===================="));
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.warning.packet_mode")));
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.warning.recommend_simulate")));
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "====================================================="));
                mc.thePlayer.addChatMessage(new ChatComponentText(" "));
            } else if (mode == MiningMode.SIMULATE) {
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.info.simulate_safe")));
            }
        }
    }

    public static void setCurrentMiningMode_noMessage(MiningMode mode) {
        currentMiningMode = mode;
        switch(mode) {
            case SIMULATE: currentStrategy = new SimulateMiningStrategy(); break;
            case PACKET_NORMAL: currentStrategy = new PacketNormalMiningStrategy(); break;
            case PACKET_INSTANT: currentStrategy = new PacketInstantMiningStrategy(); break;
        }
    }

    public static MiningMode getMiningMode() {
        return currentMiningMode;
    }

    public static void clearBlacklist() {
        unmineableBlacklist.clear();
    }

    public static boolean isActive() {
        return isActive;
    }
    
    /**
     * 由策略类调用，用于启动方块破坏的验证流程。
     * @param pos 刚刚尝试破坏的方块坐标。
     */
    public static void startValidation(BlockPos pos) {
        packetBreaksSinceStart++;
        // 核心改动：只有在总开关开启、临时禁用未开启、且检查次数未达上限时才进行验证
        if (GhostConfig.AutoMine.antiCheatCheck && !checksTemporarilyDisabled && packetBreaksSinceStart <= CHECKS_TO_PERFORM) {
            blockToValidate = pos;
            validationTicks = 0;
            currentState = State.VALIDATING_BREAK;
        } else {
            // 否则直接跳过验证，进入下一个目标
            currentState = State.SWITCHING_TARGET;
        }
    }
    
    /**
     * 由内部命令调用，处理用户对回弹警告的反馈。
     * @param action 用户选择的操作 ("continue", "disable", 或 "stop")
     */
    public static void onRollbackFeedback(String action) {
        if (!awaitingRollbackConfirmation) return;

        awaitingRollbackConfirmation = false;

        if ("continue".equalsIgnoreCase(action)) {
            checksTemporarilyDisabled = true; // <--- 临时禁用检查
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.feedback.continue")));
            toggle(); // 重新启动
        } else if ("disable".equalsIgnoreCase(action)) {
            GhostConfig.setAutoMineAntiCheatCheck(false); // <--- 永久禁用
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + LangUtil.translate("ghost.automine.feedback.disable")));
            toggle(); // 重新启动
        } else { // "stop"
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.feedback.stop")));
            // 保持停止状态
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (currentMiningMode == MiningMode.SIMULATE) {
            if (mc.currentScreen != null) {
                if (isActive && !isPausedByGui) {
                    reset();
                    isPausedByGui = true;
                }
                return;
            } else {
                if (isPausedByGui) {
                    isPausedByGui = false;
                    toggle();
                }
            }
        }
        
        handleMovementKeys();

        if (!isActive) {
            if (modIsControllingSneak) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
                modIsControllingSneak = false;
            }
            return;
        }

        if (GhostConfig.AutoMine.sneakOnMine) {
            if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
            }
            modIsControllingSneak = true; 
        } else {
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
                handleSwitchingTarget();
                break;
            case WAITING:
                waitTicks++;
                if (waitTicks >= 20) { 
                    currentState = State.SWITCHING_TARGET;
                }
                break;
            case MINING:
                handleMining();
                break;
            case VALIDATING_BREAK:
                handleValidation();
                break;
        }
    }
    
    private void handleSwitchingTarget() {
        if (currentStrategy != null) currentStrategy.onStopMining();
        miningStartTime = null;
        
        BlockPos veinTarget = findVeinMineTarget();
        if (veinTarget != null) {
            currentTarget = veinTarget;
        } else {
            lastMinedState = null;
            currentTarget = findBestTarget();
        }

        if (currentTarget != null) {
            if (currentStrategy != null) currentStrategy.onStartMining(currentTarget);
            currentState = State.MINING;
        } else {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.status.waiting")));
            currentState = State.WAITING;
            waitTicks = 0;
        }
    }
    
    private void handleMining() {
        if (currentTarget == null) {
            currentState = State.SWITCHING_TARGET; 
            return;
        }

        // --- 新增逻辑：玩家手动覆盖 ---
        MovingObjectPosition mouseOver = mc.objectMouseOver;
        if (mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos crosshairTargetPos = mouseOver.getBlockPos();
            // 检查玩家是否正在看一个与当前目标不同，但同样有效的方块
            if (!crosshairTargetPos.equals(currentTarget) && isTargetValid(crosshairTargetPos)) {
                // 如果是，则将玩家的目标作为新目标
                currentStrategy.onStopMining();
                currentTarget = crosshairTargetPos;
                currentStrategy.onStartMining(currentTarget);
                miningStartTime = null; // 重置挖掘计时器
                return; // 立即返回，让下一个 tick 处理新目标的挖掘
            }
        }
        // --- 新增逻辑结束 ---
        
        IBlockState targetBlockState = mc.theWorld.getBlockState(currentTarget);
        Block blockAtTarget = targetBlockState.getBlock();
        if (blockAtTarget != Blocks.air) {
            lastMinedState = targetBlockState;
        }

        if (blockAtTarget == Blocks.air || !isTargetValid(currentTarget) || checkTimeout(blockAtTarget)) {
            currentState = State.SWITCHING_TARGET;
            return;
        }

        Vec3 bestPointToLookAt;

        // 检查准星是否已经对准了我们的目标方块
        if (mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mouseOver.getBlockPos().equals(currentTarget)) {
            // 如果是，就使用游戏返回的精确碰撞点，这对于非完整方块至关重要
            bestPointToLookAt = mouseOver.hitVec;
        } else {
            // 否则，回退到我们原来的方法，去寻找一个方块上的可见点来引导视角
            bestPointToLookAt = RotationUtil.getClosestVisiblePoint(currentTarget);
        }

        if (bestPointToLookAt == null) {
            currentState = State.SWITCHING_TARGET;
            return;
        }
        
        if (currentStrategy != null) {
            currentStrategy.handleMiningTick(currentTarget, bestPointToLookAt);
        }
    }

    private void handleValidation() {
        if (blockToValidate == null) {
            currentState = State.SWITCHING_TARGET;
            return;
        }
        
        validationTicks++;

        if (validationTicks >= VALIDATION_DELAY_TICKS) {
            IBlockState state = mc.theWorld.getBlockState(blockToValidate);
            if (state.getBlock() == Blocks.air) {
                // 成功
                currentState = State.SWITCHING_TARGET;
            } else {
                // 失败，检测到回弹
                handleRollbackDetected();
            }
            blockToValidate = null;
        }
    }
    
    private void handleRollbackDetected() {
        // 将导致回弹的方块加入黑名单
        unmineableBlacklist.put(blockToValidate, mc.theWorld.getBlockState(blockToValidate).getBlock());
        // 立即停止所有活动
        reset();
        
        awaitingRollbackConfirmation = true;

        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "====================================================="));
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD.toString() + EnumChatFormatting.BOLD + "      " + LangUtil.translate("ghost.automine.rollback.title")));
        mc.thePlayer.addChatMessage(new ChatComponentText(" "));
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.rollback.description")));
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.rollback.advice")));
        mc.thePlayer.addChatMessage(new ChatComponentText(" "));

        ChatComponentText optionsMessage = new ChatComponentText(EnumChatFormatting.WHITE + LangUtil.translate("ghost.automine.rollback.question"));
        
        // 选项1: 继续 (临时禁用)
        ChatComponentText continueButton = new ChatComponentText(" " + LangUtil.translate("ghost.automine.rollback.option.continue"));
        continueButton.setChatStyle(
            continueButton.getChatStyle()
                .setColor(EnumChatFormatting.GREEN)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/automine automine_internal_feedback continue"))
        );

        // 选项2: 永久禁用
        ChatComponentText disableButton = new ChatComponentText(" " + LangUtil.translate("ghost.automine.rollback.option.disable"));
        disableButton.setChatStyle(
            disableButton.getChatStyle()
                .setColor(EnumChatFormatting.GOLD)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/automine automine_internal_feedback disable"))
        );

        // 选项3: 停止
        ChatComponentText stopButton = new ChatComponentText(" " + LangUtil.translate("ghost.automine.rollback.option.stop"));
        stopButton.setChatStyle(
            stopButton.getChatStyle()
                .setColor(EnumChatFormatting.RED)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/automine automine_internal_feedback stop"))
        );

        optionsMessage.appendSibling(continueButton);
        optionsMessage.appendSibling(new ChatComponentText("  "));
        optionsMessage.appendSibling(disableButton);
        optionsMessage.appendSibling(new ChatComponentText("  "));
        optionsMessage.appendSibling(stopButton);
        
        mc.thePlayer.addChatMessage(optionsMessage);
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "====================================================="));
    }

    private boolean checkTimeout(Block blockAtTarget) {
        if (miningStartTime == null) {
            miningStartTime = System.currentTimeMillis();
            return false;
        }
        long mineTimeoutMs = GhostConfig.AutoMine.mineTimeoutSeconds * 1000L;
        if (System.currentTimeMillis() - miningStartTime > mineTimeoutMs) {
            unmineableBlacklist.put(currentTarget, blockAtTarget);
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.mining_timeout_blacklisted", blockAtTarget.getLocalizedName(), currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())));
            return true;
        }
        return false;
    }
    
    private void handleMovementKeys() {
        if (!isActive || !GhostConfig.AutoMine.enableRandomMovements) {
            if (currentMoveKey != null) {
                KeyBinding.setKeyBindState(currentMoveKey.getKeyCode(), false);
                currentMoveKey = null;
            }
            randomMoveTicks = 0;
            currentMoveDuration = 0;
            return;
        }
    
        if (currentMoveDuration > 0) {
            currentMoveDuration--;
        } else {
            if (currentMoveKey != null) {
                KeyBinding.setKeyBindState(currentMoveKey.getKeyCode(), false);
                currentMoveKey = null;
            }
    
            if (randomMoveTicks > 0) {
                randomMoveTicks--;
            } else {
                int variability = GhostConfig.AutoMine.randomMoveIntervalVariability;
                int baseInterval = GhostConfig.AutoMine.randomMoveInterval;
                
                randomMoveTicks = baseInterval + ThreadLocalRandom.current().nextInt(-variability, variability + 1);
                randomMoveTicks = Math.max(10, randomMoveTicks);

                currentMoveDuration = GhostConfig.AutoMine.randomMoveDuration;
    
                KeyBinding[] moveKeys = {mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack, mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight};
                currentMoveKey = moveKeys[ThreadLocalRandom.current().nextInt(moveKeys.length)];
                KeyBinding.setKeyBindState(currentMoveKey.getKeyCode(), true);
            }
        }
    }
    
    private BlockPos findVeinMineTarget() {
        if (!GhostConfig.AutoMine.enableVeinMining || currentTarget == null || lastMinedState == null || lastMinedState.getBlock() == Blocks.air || AutoMineTargetManager.targetBlocks.contains(currentTarget)) {
            return null;
        }
    
        if (mc.theWorld.getBlockState(currentTarget).getBlock() == Blocks.air) {
            BlockPos lastPos = currentTarget;
            BlockPos bestNeighbor = null;
            double minScore = Double.MAX_VALUE;
    
            for (EnumFacing facing : EnumFacing.values()) {
                BlockPos neighborPos = lastPos.offset(facing);
                IBlockState neighborState = mc.theWorld.getBlockState(neighborPos);
    
                if (neighborState == lastMinedState && isTargetValid(neighborPos)) {
                    double score = getAngleDifferenceToBlock(neighborPos);
                    if (score < minScore) {
                        minScore = score;
                        bestNeighbor = neighborPos;
                    }
                }
            }
            return bestNeighbor;
        }
        
        return null;
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
                if (isBlockTypeTargeted(mc.theWorld.getBlockState(pos))) {
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
            
            Block candidateBlock = mc.theWorld.getBlockState(candidate).getBlock();
            int weight = AutoMineTargetManager.targetBlockWeights.getOrDefault(candidateBlock, DEFAULT_WEIGHT);
            
            double score = (angleDiff * 10.0 + distanceSq) / weight;

            if (score < minScore) {
                minScore = score;
                bestPos = candidate;
            }
        }

        return bestPos;
    }
    
    private boolean isBlockTypeTargeted(IBlockState state) {
        Block block = state.getBlock();
        int meta = block.getMetaFromState(state);
        
        AutoMineTargetManager.BlockData specificBlock = new AutoMineTargetManager.BlockData(block, meta);
        AutoMineTargetManager.BlockData wildcardBlock = new AutoMineTargetManager.BlockData(block, -1);

        return AutoMineTargetManager.targetBlockTypes.contains(specificBlock) || AutoMineTargetManager.targetBlockTypes.contains(wildcardBlock);
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
        
        if (!AutoMineTargetManager.targetBlocks.contains(pos) && !isBlockTypeTargeted(state)) {
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