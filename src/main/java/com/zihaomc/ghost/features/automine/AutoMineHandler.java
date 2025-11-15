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
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.*;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Auto Mine" 功能的核心处理器。
 * (重构后) 负责状态管理、目标搜寻，并将具体的挖掘行为委托给策略对象。
 * 同时处理反作弊回弹检测。
 */
public class AutoMineHandler {

    // 您可以在这里调整切换工具后的等待时间（以游戏刻为单位，20 ticks = 1秒）
    private static final int TOOL_SWITCH_DELAY_TICKS = 5;

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
        VALIDATING_BREAK,
        POST_SWITCH_DELAY // 新增：工具切换后的延迟状态
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean isActive = false;
    private static BlockPos currentTarget = null;
    private static State currentState = State.IDLE;
    private int waitTicks = 0;
    private int delayTicks = 0; // 用于新状态的计时器

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

    private static BlockPos blockToValidate = null;
    private static int validationTicks = 0;
    private static final int VALIDATION_DELAY_TICKS = 8;
    private static boolean awaitingRollbackConfirmation = false;
    private static int packetBreaksSinceStart = 0;
    private static final int CHECKS_TO_PERFORM = 3;
    private static boolean checksTemporarilyDisabled = false;

    private static final Set<AutoMineTargetManager.BlockData> titaniumBlockTypes = new HashSet<>();
    private static final Set<String> MITHRIL_ORE_IDS = new HashSet<>(Arrays.asList(
            "minecraft:wool:7", "minecraft:prismarine", "minecraft:wool:11", "minecraft:stained_hardened_clay:9"
    ));
    private static int originalSlot = -1;
    private static final Pattern BREAKING_POWER_PATTERN = Pattern.compile("Breaking Power (\\d+)");
    private static boolean isCleanupMode = false;
    private static boolean mithrilOptimizationIsActive = false;
    private static boolean cleanupToolTooWeakNotified = false;
    private static BlockPos lastSkippedBlock = null;

    public static void toggle() {
        if (awaitingRollbackConfirmation) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.rollback_confirm_pending")));
            return;
        }

        boolean hasAnyTargets = !AutoMineTargetManager.getCurrentTargetBlocks().isEmpty() || !AutoMineTargetManager.targetBlockTypes.isEmpty();
        if (!isActive && !hasAnyTargets) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.automine.error.no_targets_set")));
            return;
        }

        isActive = !isActive;
        isPausedByGui = false;

        String status = isActive ? EnumChatFormatting.GREEN + LangUtil.translate("ghost.generic.enabled") : EnumChatFormatting.RED + LangUtil.translate("ghost.generic.disabled");
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + LangUtil.translate("ghost.keybind.toggle.automine") + " " + status));

        if (isActive) {
            validateAndActivateMithrilOptimization();
            currentState = State.SWITCHING_TARGET;
            packetBreaksSinceStart = 0;
        } else {
            reset();
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

        if (originalSlot != -1) {
            mc.thePlayer.inventory.currentItem = originalSlot;
            originalSlot = -1;
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
        isCleanupMode = false;
        mithrilOptimizationIsActive = false;
        cleanupToolTooWeakNotified = false;
        lastSkippedBlock = null;
    }

    public static void setMiningMode(MiningMode mode) {
        if (currentMiningMode != mode) {
            currentMiningMode = mode;
            GhostConfig.setAutoMineMiningMode(mode.name());

            switch (mode) {
                case SIMULATE: currentStrategy = new SimulateMiningStrategy(); break;
                case PACKET_NORMAL: currentStrategy = new PacketNormalMiningStrategy(); break;
                case PACKET_INSTANT: currentStrategy = new PacketInstantMiningStrategy(); break;
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
        switch (mode) {
            case SIMULATE: currentStrategy = new SimulateMiningStrategy(); break;
            case PACKET_NORMAL: currentStrategy = new PacketNormalMiningStrategy(); break;
            case PACKET_INSTANT: currentStrategy = new PacketInstantMiningStrategy(); break;
        }
    }

    public static MiningMode getMiningMode() { return currentMiningMode; }
    public static void clearBlacklist() { unmineableBlacklist.clear(); }
    public static boolean isActive() { return isActive; }

    public static void startValidation(BlockPos pos) {
        packetBreaksSinceStart++;
        if (GhostConfig.AutoMine.antiCheatCheck && !checksTemporarilyDisabled && packetBreaksSinceStart <= CHECKS_TO_PERFORM) {
            blockToValidate = pos;
            validationTicks = 0;
            currentState = State.VALIDATING_BREAK;
        } else {
            currentState = State.SWITCHING_TARGET;
        }
    }

    public static void onRollbackFeedback(String action) {
        if (!awaitingRollbackConfirmation) return;
        awaitingRollbackConfirmation = false;

        if ("continue".equalsIgnoreCase(action)) {
            checksTemporarilyDisabled = true;
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.feedback.continue")));
            toggle();
        } else if ("disable".equalsIgnoreCase(action)) {
            GhostConfig.setAutoMineAntiCheatCheck(false);
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + LangUtil.translate("ghost.automine.feedback.disable")));
            toggle();
        } else {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.automine.feedback.stop")));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) return;

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

        switch (currentState) {
            case SWITCHING_TARGET: handleSwitchingTarget(); break;
            case WAITING:
                waitTicks++;
                if (waitTicks >= 20) currentState = State.SWITCHING_TARGET;
                break;
            case MINING: handleMining(); break;
            case VALIDATING_BREAK: handleValidation(); break;
            case POST_SWITCH_DELAY: handlePostSwitchDelay(); break; // 处理新状态
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
            if (currentState != State.IDLE) {
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.automine.status.waiting")));
                currentState = State.WAITING;
                waitTicks = 0;
            }
        }
    }

    private void handleMining() {
        if (currentTarget == null) {
            currentState = State.SWITCHING_TARGET;
            return;
        }

        if (needsToolSwitch()) {
            if (handleToolSwitching()) {
                // 切换成功，进入延迟状态而不是立即挖掘
                currentState = State.POST_SWITCH_DELAY;
                delayTicks = TOOL_SWITCH_DELAY_TICKS;
                // 确保在延迟期间不发送挖掘数据包
                if (currentStrategy != null) currentStrategy.onStopMining();
                return;
            } else {
                // 切换失败，handleToolSwitching内部会重置
                return;
            }
        }
        
        if (!isToolSufficientFor(currentTarget)) {
            IBlockState state = mc.theWorld.getBlockState(currentTarget);
            unmineableBlacklist.put(currentTarget, state.getBlock());
            if (!currentTarget.equals(lastSkippedBlock)) {
                int requiredPower = isTitanium(state) ? 5 : 4;
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + String.format("[Ghost] Skipping %s, tool is too weak (BP %d < %d)",
                        state.getBlock().getLocalizedName(), getBreakingPower(mc.thePlayer.getCurrentEquippedItem()), requiredPower)));
                lastSkippedBlock = currentTarget;
            }
            currentState = State.SWITCHING_TARGET;
            return;
        }
        
        MovingObjectPosition mouseOver = mc.objectMouseOver;
        if (mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos crosshairTargetPos = mouseOver.getBlockPos();
            if (!crosshairTargetPos.equals(currentTarget) && isTargetValid(crosshairTargetPos)) {
                currentStrategy.onStopMining();
                currentTarget = crosshairTargetPos;
                currentStrategy.onStartMining(currentTarget);
                miningStartTime = null;
                return;
            }
        }

        IBlockState targetBlockState = mc.theWorld.getBlockState(currentTarget);
        Block blockAtTarget = targetBlockState.getBlock();
        if (blockAtTarget != Blocks.air) {
            lastMinedState = targetBlockState;
        }
        if (blockAtTarget == Blocks.air || !isTargetValid(currentTarget) || checkTimeout(blockAtTarget)) {
            currentState = State.SWITCHING_TARGET;
            return;
        }

        Vec3 bestPointToLookAt = (mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mouseOver.getBlockPos().equals(currentTarget))
                ? mouseOver.hitVec : RotationUtil.getClosestVisiblePoint(currentTarget);

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
                currentState = State.SWITCHING_TARGET;
            } else {
                handleRollbackDetected();
            }
            blockToValidate = null;
        }
    }
    
    // 新增：处理工具切换后延迟的方法
    private void handlePostSwitchDelay() {
        // 确保在延迟期间不攻击
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }

        delayTicks--;
        if (delayTicks <= 0) {
            // 延迟结束，回到挖掘状态
            currentState = State.MINING;
        }
    }

    private void handleRollbackDetected() {
        unmineableBlacklist.put(blockToValidate, mc.theWorld.getBlockState(blockToValidate).getBlock());
        reset();
        awaitingRollbackConfirmation = true;

        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "====================================================="));
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD.toString() + EnumChatFormatting.BOLD + "      " + LangUtil.translate("ghost.automine.rollback.title")));
        mc.thePlayer.addChatMessage(new ChatComponentText(" "));
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.rollback.description")));
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.automine.rollback.advice")));
        mc.thePlayer.addChatMessage(new ChatComponentText(" "));

        ChatComponentText optionsMessage = new ChatComponentText(EnumChatFormatting.WHITE + LangUtil.translate("ghost.automine.rollback.question"));
        
        IChatComponent continueButton = new ChatComponentText(" " + LangUtil.translate("ghost.automine.rollback.option.continue"))
                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN).setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/automine automine_internal_feedback continue")));
        
        IChatComponent disableButton = new ChatComponentText(" " + LangUtil.translate("ghost.automine.rollback.option.disable"))
                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GOLD).setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/automine automine_internal_feedback disable")));
        
        IChatComponent stopButton = new ChatComponentText(" " + LangUtil.translate("ghost.automine.rollback.option.stop"))
                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED).setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/automine automine_internal_feedback stop")));
        
        optionsMessage.appendSibling(continueButton)
                      .appendSibling(new ChatComponentText("  "))
                      .appendSibling(disableButton)
                      .appendSibling(new ChatComponentText("  "))
                      .appendSibling(stopButton);

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
        if (!GhostConfig.AutoMine.enableVeinMining || currentTarget == null || lastMinedState == null || lastMinedState.getBlock() == Blocks.air) return null;
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
        Set<BlockPos> primaryCandidates = new HashSet<>();
        Set<BlockPos> secondaryCandidates = new HashSet<>();
        findAndCategorizeCandidates(primaryCandidates, secondaryCandidates);

        if (!mithrilOptimizationIsActive || (primaryCandidates.isEmpty() && secondaryCandidates.isEmpty())) {
            return findBestCandidate(primaryCandidates.isEmpty() ? secondaryCandidates : primaryCandidates);
        }

        boolean hasAbilityToCleanTitanium = GhostConfig.AutoMine.enableAutomaticToolSwitching
                ? findToolByBreakingPower(5, true) != -1
                : getBreakingPower(mc.thePlayer.getCurrentEquippedItem()) >= 5;

        boolean shouldCleanTitanium = false;
        if (hasAbilityToCleanTitanium && !secondaryCandidates.isEmpty()) {
            boolean cleanupThresholdMet = secondaryCandidates.size() >= GhostConfig.AutoMine.mithrilCleanupThreshold;
            if (cleanupThresholdMet || isCleanupMode || primaryCandidates.isEmpty()) {
                shouldCleanTitanium = true;
            }
        } else {
            if (isCleanupMode) {
                 isCleanupMode = false;
                 mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] " + LangUtil.translate("ghost.automine.status.cleanup_complete")));
            }
        }

        if (shouldCleanTitanium) {
            if (!isCleanupMode) {
                isCleanupMode = true;
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "[Ghost] " + LangUtil.translate("ghost.automine.status.cleanup_start")));
            }
            return findBestCandidate(secondaryCandidates);
        } else {
            if (primaryCandidates.isEmpty() && !secondaryCandidates.isEmpty() && !cleanupToolTooWeakNotified) {
                String warningMessage = "[Ghost] " + LangUtil.translate("ghost.automine.warning.tool_too_weak_for_cleanup");
                mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + warningMessage));
                cleanupToolTooWeakNotified = true;
            }
            if (isCleanupMode) {
                 isCleanupMode = false;
                 mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] " + LangUtil.translate("ghost.automine.status.cleanup_complete")));
            }
            return findBestCandidate(primaryCandidates);
        }
    }

    private void findAndCategorizeCandidates(Set<BlockPos> primary, Set<BlockPos> secondary) {
        int radius = GhostConfig.AutoMine.searchRadius;
        BlockPos playerPos = mc.thePlayer.getPosition();
        for (BlockPos pos : BlockPos.getAllInBox(playerPos.add(-radius, -radius, -radius), playerPos.add(radius, radius, radius))) {
            if (!isTargetValid(pos)) continue;
            
            if (isCleanupMode) {
                secondary.add(new BlockPos(pos));
            } else {
                primary.add(new BlockPos(pos));
            }
        }
    }

    private BlockPos findBestCandidate(Set<BlockPos> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
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
        IBlockState state = mc.theWorld.getBlockState(pos);
        if (unmineableBlacklist.containsKey(pos)) {
            if (state.getBlock() != unmineableBlacklist.get(pos)) unmineableBlacklist.remove(pos);
            else return false;
        }
        if (GhostConfig.AutoMine.enableVoidSafetyCheck && pos.getY() < GhostConfig.AutoMine.voidSafetyYLimit) return false;
        if (state.getBlock() == Blocks.air) return false;
        if (GhostConfig.AutoMine.preventDiggingDown && pos.getY() < MathHelper.floor_double(mc.thePlayer.posY)) return false;
        if (state.getBlock().getBlockHardness(mc.theWorld, pos) < 0) {
            unmineableBlacklist.put(pos, state.getBlock());
            return false;
        }

        boolean isPrimary = isBlockTypeTargeted(state);
        boolean isSecondary = mithrilOptimizationIsActive && isTitanium(state);

        if (mithrilOptimizationIsActive) {
            if (isCleanupMode) {
                if (!isSecondary) return false;
            } else {
                if (!isPrimary) return false;
            }
        } else {
            if (!isPrimary) return false;
        }

        double reach = GhostConfig.AutoMine.maxReachDistance;
        Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (mc.thePlayer.getPositionEyes(1.0f).squareDistanceTo(blockCenter) > reach * reach) return false;
        
        return RotationUtil.getClosestVisiblePoint(pos) != null;
    }

    private static void updateTitaniumBlockTypes() {
        titaniumBlockTypes.clear();
        for (String id : GhostConfig.AutoMine.titaniumBlockIds) {
            try {
                String blockName = id;
                int meta = -1;
                String[] parts = id.split(":");
                if (parts.length > 2) {
                    try {
                        meta = Integer.parseInt(parts[parts.length - 1]);
                        blockName = String.join(":", Arrays.copyOf(parts, parts.length - 1));
                    } catch (NumberFormatException e) {}
                }
                Block block = Block.getBlockFromName(blockName);
                if (block != null) titaniumBlockTypes.add(new AutoMineTargetManager.BlockData(block, meta));
            } catch (Exception e) {}
        }
    }

    private boolean isTitanium(IBlockState state) {
        Block block = state.getBlock();
        int meta = block.getMetaFromState(state);
        AutoMineTargetManager.BlockData specificBlock = new AutoMineTargetManager.BlockData(block, meta);
        AutoMineTargetManager.BlockData wildcardBlock = new AutoMineTargetManager.BlockData(block, -1);
        return titaniumBlockTypes.contains(specificBlock) || titaniumBlockTypes.contains(wildcardBlock);
    }
    
    private boolean isToolSufficientFor(BlockPos target) {
        if (!mithrilOptimizationIsActive) return true;
        IBlockState targetState = mc.theWorld.getBlockState(target);
        int requiredPower = isTitanium(targetState) ? 5 : 4;
        return getBreakingPower(mc.thePlayer.getCurrentEquippedItem()) >= requiredPower;
    }

    private boolean needsToolSwitch() {
        if (!mithrilOptimizationIsActive || !GhostConfig.AutoMine.enableAutomaticToolSwitching || currentTarget == null) return false;
        
        IBlockState targetState = mc.theWorld.getBlockState(currentTarget);
        boolean isTargetTitanium = isTitanium(targetState);
        int requiredPower = isTargetTitanium ? 5 : 4;
        
        int toolSlot = findToolByBreakingPower(requiredPower, isTargetTitanium);
        
        return toolSlot != -1 && mc.thePlayer.inventory.currentItem != toolSlot;
    }

    private boolean handleToolSwitching() {
        if (!GhostConfig.AutoMine.enableAutomaticToolSwitching) return true;
        
        IBlockState targetState = mc.theWorld.getBlockState(currentTarget);
        boolean isTargetTitanium = isTitanium(targetState);
        int requiredPower = isTargetTitanium ? 5 : 4;

        int toolSlot = findToolByBreakingPower(requiredPower, isTargetTitanium);
        
        if (toolSlot == -1) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Ghost] " + LangUtil.translate("ghost.automine.error.no_tool_found", requiredPower)));
            reset();
            return false;
        }
        if (mc.thePlayer.inventory.currentItem != toolSlot) {
            if (originalSlot == -1) originalSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = toolSlot;
        }
        return true;
    }

    private static int getBreakingPower(ItemStack stack) {
        if (stack == null || stack.getTagCompound() == null) return 0;
        NBTTagCompound display = stack.getTagCompound().getCompoundTag("display");
        if (display.hasKey("Lore", Constants.NBT.TAG_LIST)) {
            NBTTagList lore = display.getTagList("Lore", Constants.NBT.TAG_STRING);
            for (int j = 0; j < lore.tagCount(); j++) {
                String colorlessLine = EnumChatFormatting.getTextWithoutFormattingCodes(lore.getStringTagAt(j));
                Matcher matcher = BREAKING_POWER_PATTERN.matcher(colorlessLine);
                if (matcher.find()) {
                    try { return Integer.parseInt(matcher.group(1)); } 
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        return 0;
    }

    private static int findToolByBreakingPower(int requiredPower, boolean findStrongest) {
        int bestSlot = -1;
        int bestPower = findStrongest ? -1 : Integer.MAX_VALUE;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack == null) continue;
            
            int power = getBreakingPower(stack);
            
            if (power >= requiredPower) {
                if (findStrongest) {
                    if (power > bestPower) {
                        bestPower = power;
                        bestSlot = i;
                    }
                } else {
                    if (power < bestPower) {
                        bestPower = power;
                        bestSlot = i;
                    }
                }
            }
        }
        return bestSlot;
    }

    private static void validateAndActivateMithrilOptimization() {
        mithrilOptimizationIsActive = false;
        if (!GhostConfig.AutoMine.enableMithrilOptimization) return;
        boolean hasMithrilInTargets = false;
        for (AutoMineTargetManager.BlockData target : AutoMineTargetManager.targetBlockTypes) {
            for (String mithrilId : MITHRIL_ORE_IDS) {
                if (target.toString().equals(mithrilId)) {
                    hasMithrilInTargets = true;
                    break;
                }
            }
            if (hasMithrilInTargets) break;
        }
        if (!hasMithrilInTargets) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[Ghost] " + LangUtil.translate("ghost.automine.warning.no_mithril_targets")));
            return;
        }
        mithrilOptimizationIsActive = true;
        updateTitaniumBlockTypes();
    }
}