package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.pathfinding.PathfindingHandler;
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
import net.minecraftforge.client.event.ClientChatReceivedEvent;
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
 * 同时处理反作弊回弹检测，以及针对 Hypixel Skyblock 的自动重启恢复流程。
 */
public class AutoMineHandler {

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
        POST_SWITCH_DELAY
    }

    // --- 新增：自动恢复流程状态机 ---
    private enum RecoveryState {
        IDLE,
        SEND_HUB,       // 发送 /hub
        WAIT_FOR_HUB,   // 等待加载大厅
        SEND_WARP,      // 发送 /warp forge
        WAIT_FOR_FORGE, // 等待进入 Forge
        PATHING         // 正在使用 gpath 寻路回位
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean isActive = false;
    private static BlockPos currentTarget = null;
    private static IBlockState initialTargetState = null;
    private static State currentState = State.IDLE;
    private int waitTicks = 0;
    private int delayTicks = 0;

    // --- 恢复流程变量 ---
    private static RecoveryState recoveryStatus = RecoveryState.IDLE;
    private static int recoveryTicks = 0;
    private static BlockPos activeRecoveryTarget = null;
    // 固定坐标
    private static final BlockPos FIXED_RECOVERY_POS = new BlockPos(-49, 142, -23);

    private static final ConcurrentHashMap<BlockPos, Block> unmineableBlacklist = new ConcurrentHashMap<>();
    
    // 用于跟踪空闲搜索的时间 (找不到目标的时间)
    private static Long searchStartTime = null;

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

    /**
     * 发送带统一格式 [Ghost] 前缀的聊天消息。
     */
    private static void sendMessage(String text, EnumChatFormatting color) {
        if (mc.thePlayer == null) return;
        
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
        
        ChatComponentText body = new ChatComponentText(text); 
        body.getChatStyle().setColor(color);
        
        prefix.appendSibling(body);
        mc.thePlayer.addChatMessage(prefix);
    }

    public static void toggle() {
        if (awaitingRollbackConfirmation) {
            sendMessage(LangUtil.translate("ghost.automine.error.rollback_confirm_pending"), EnumChatFormatting.RED);
            return;
        }

        boolean hasAnyTargets = !AutoMineTargetManager.getCurrentTargetBlocks().isEmpty() || !AutoMineTargetManager.targetBlockTypes.isEmpty();
        if (!isActive && !hasAnyTargets) {
            sendMessage(LangUtil.translate("ghost.automine.error.no_targets_set"), EnumChatFormatting.RED);
            return;
        }

        isActive = !isActive;
        isPausedByGui = false;

        String status = isActive ? EnumChatFormatting.GREEN + LangUtil.translate("ghost.generic.enabled") : EnumChatFormatting.RED + LangUtil.translate("ghost.generic.disabled");
        sendMessage(LangUtil.translate("ghost.keybind.toggle.automine") + " " + status, EnumChatFormatting.AQUA);

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
        initialTargetState = null;
        isActive = false;
        unmineableBlacklist.clear();
        lastMinedState = null;
        blockToValidate = null;
        validationTicks = 0;
        isCleanupMode = false;
        mithrilOptimizationIsActive = false;
        cleanupToolTooWeakNotified = false;
        lastSkippedBlock = null;
        
        searchStartTime = null; // 重置搜索计时器
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
            sendMessage(LangUtil.translate("ghost.automine.command.mode.set", mode.name()), EnumChatFormatting.GRAY);
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
            sendMessage(LangUtil.translate("ghost.automine.feedback.continue"), EnumChatFormatting.YELLOW);
            toggle();
        } else if ("disable".equalsIgnoreCase(action)) {
            GhostConfig.setAutoMineAntiCheatCheck(false);
            sendMessage(LangUtil.translate("ghost.automine.feedback.disable"), EnumChatFormatting.GOLD);
            toggle();
        } else {
            sendMessage(LangUtil.translate("ghost.automine.feedback.stop"), EnumChatFormatting.GREEN);
        }
    }

    // --- 处理自动恢复流程的核心状态机 ---
    private void handleAutoRecovery() {
        if (recoveryStatus == RecoveryState.IDLE) return;

        recoveryTicks++;

        switch (recoveryStatus) {
            case SEND_HUB:
                // 在收到消息并停止挖掘后，稍微等待几秒模拟人类反应再回主城
                if (recoveryTicks > 40) { 
                    mc.thePlayer.sendChatMessage("/hub");
                    recoveryStatus = RecoveryState.WAIT_FOR_HUB;
                    recoveryTicks = 0;
                    sendMessage("检测到重启，正在尝试返回 Hub...", EnumChatFormatting.YELLOW);
                }
                break;

            case WAIT_FOR_HUB:
                // 等待 3-4 秒确保进入 Hub 并且客户端稳定
                if (recoveryTicks > 80) {
                    mc.thePlayer.sendChatMessage("/warp forge");
                    recoveryStatus = RecoveryState.WAIT_FOR_FORGE;
                    recoveryTicks = 0;
                    sendMessage("正在尝试前往 Forge...", EnumChatFormatting.YELLOW);
                }
                break;

            case WAIT_FOR_FORGE:
                // 等待 5 秒左右确保进入 Forge 地图且地形已加载
                if (recoveryTicks > 100) {
                    // 确定目标坐标点：优先使用用户设置的，否则使用固定坐标
                    if (!AutoMineTargetManager.recoveryPoints.isEmpty()) {
                        activeRecoveryTarget = AutoMineTargetManager.recoveryPoints.get(0);
                    } else {
                        activeRecoveryTarget = FIXED_RECOVERY_POS;
                    }
                    
                    sendMessage("已到达 Forge，开始寻路回挖掘位置...", EnumChatFormatting.AQUA);
                    // 调用现有的寻路处理器开始寻路
                    PathfindingHandler.setGlobalTarget(activeRecoveryTarget);
                    recoveryStatus = RecoveryState.PATHING;
                    recoveryTicks = 0;
                }
                break;

            case PATHING:
                // 每一秒检测一次是否到达
                if (recoveryTicks % 20 == 0) {
                    double distSq = mc.thePlayer.getDistanceSq(activeRecoveryTarget);
                    // 距离目标点小于 2 格（4 的平方），或者寻路已经由于某些原因停止（到达）
                    if (distSq < 4.0 || PathfindingHandler.getGlobalTarget() == null) {
                        sendMessage("已到达挖掘点，自动重新开启挖掘。", EnumChatFormatting.GREEN);
                        recoveryStatus = RecoveryState.IDLE;
                        activeRecoveryTarget = null;
                        
                        // 如果之前因为重启关闭了挖掘，现在重新开启
                        if (!isActive) {
                            toggle();
                        }
                    }
                }
                // 如果寻路时间超过 10 分钟（防止极端卡死），强制重置状态机
                if (recoveryTicks > 12000) {
                    recoveryStatus = RecoveryState.IDLE;
                    sendMessage("寻路回位超时，流程终止。", EnumChatFormatting.RED);
                }
                break;
        }
    }

    /**
     * 监听聊天消息，捕捉 Hypixel 的重启提示词
     */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        // 关键：只有在正在挖掘 且 启用了 Mithril 优化时，才监听重启消息
        if (!isActive || !GhostConfig.AutoMine.enableMithrilOptimization) return;

        // 获取不带颜色代码的文本
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        
        // 匹配提示词逻辑
        boolean isRestartNotice = msg.contains("[Important] This server will restart soon:") || 
                                  (msg.contains("[Important]") && msg.contains("60seconds") && msg.contains("warp out")) ||
                                  msg.contains("Evacuating to Hub...") || 
                                  msg.contains("Evauating to Hub..."); // 兼容拼写错误

        if (isRestartNotice && recoveryStatus == RecoveryState.IDLE) {
            // 立即标记为非活动，并进入恢复流程
            toggle(); // 调用 toggle 会执行 reset() 并把 isActive 设为 false
            recoveryStatus = RecoveryState.SEND_HUB;
            recoveryTicks = 0;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || mc.theWorld == null) return;

        // 插入自动恢复逻辑的处理
        handleAutoRecovery();

        // 如果状态机正在运作，阻止常规挖掘逻辑的运行
        if (recoveryStatus != RecoveryState.IDLE) return;

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
            case POST_SWITCH_DELAY: handlePostSwitchDelay(); break;
        }
    }

    private void handleSwitchingTarget() {
        if (currentStrategy != null) currentStrategy.onStopMining();
        initialTargetState = null;

        BlockPos veinTarget = findVeinMineTarget();
        if (veinTarget != null) {
            currentTarget = veinTarget;
        } else {
            lastMinedState = null;
            currentTarget = findBestTarget();
        }

        if (currentTarget != null) {
            // 找到了目标，重置搜索计时器
            searchStartTime = null;
            
            initialTargetState = mc.theWorld.getBlockState(currentTarget);
            if (currentStrategy != null) currentStrategy.onStartMining(currentTarget);
            currentState = State.MINING;
        } else {
            // 没有找到目标
            
            // --- 超时停止逻辑 (搜索超时 - 找不到目标) ---
            if (GhostConfig.AutoMine.stopOnTimeout) {
                if (searchStartTime == null) {
                    searchStartTime = System.currentTimeMillis();
                }
                long elapsed = System.currentTimeMillis() - searchStartTime;
                long timeoutMs = GhostConfig.AutoMine.mineTimeoutSeconds * 1000L;

                // 如果搜索时间超过了设定的超时时间
                if (elapsed > timeoutMs) {
                     sendMessage(LangUtil.translate("ghost.automine.error.search_timeout", GhostConfig.AutoMine.mineTimeoutSeconds), EnumChatFormatting.RED);
                     toggle();
                     return;
                }
            } else {
                // 如果功能未开启，确保计时器重置
                searchStartTime = null;
            }
            
            if (currentState != State.IDLE) {
                // 只有在第一次进入等待状态时才提示，避免刷屏
                if (waitTicks == 0) {
                    sendMessage(LangUtil.translate("ghost.automine.status.waiting"), EnumChatFormatting.GRAY);
                }
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
        
        IBlockState currentStateAtTarget = mc.theWorld.getBlockState(currentTarget);
        if (initialTargetState == null || currentStateAtTarget.getBlock() != initialTargetState.getBlock() || currentStateAtTarget.getBlock().getMetaFromState(currentStateAtTarget) != initialTargetState.getBlock().getMetaFromState(initialTargetState)) {
             currentState = State.SWITCHING_TARGET;
             return;
        }


        if (needsToolSwitch()) {
            if (handleToolSwitching()) {
                currentState = State.POST_SWITCH_DELAY;
                delayTicks = TOOL_SWITCH_DELAY_TICKS;
                if (currentStrategy != null) currentStrategy.onStopMining();
                return;
            } else {
                return;
            }
        }
        
        if (!isToolSufficientFor(currentTarget)) {
            unmineableBlacklist.put(currentTarget, currentStateAtTarget.getBlock());
            if (!currentTarget.equals(lastSkippedBlock)) {
                int requiredPower = isTitanium(currentStateAtTarget) ? 5 : 4;
                sendMessage(LangUtil.translate("ghost.automine.warning.tool_too_weak_detailed", 
                        currentStateAtTarget.getBlock().getLocalizedName(), getBreakingPower(mc.thePlayer.getCurrentEquippedItem()), requiredPower), EnumChatFormatting.GOLD);
                lastSkippedBlock = currentTarget;
            }
            currentState = State.SWITCHING_TARGET;
            return;
        }
        
        if (!mithrilOptimizationIsActive) {
            MovingObjectPosition mouseOver = mc.objectMouseOver;
            if (mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                BlockPos crosshairTargetPos = mouseOver.getBlockPos();
                
                if (!crosshairTargetPos.equals(currentTarget) && isTargetValid(crosshairTargetPos)) {
                    currentStrategy.onStopMining();
                    currentTarget = crosshairTargetPos;
                    initialTargetState = mc.theWorld.getBlockState(currentTarget);
                    currentStrategy.onStartMining(currentTarget);
                    return;
                }
            }
        }

        Block blockAtTarget = currentStateAtTarget.getBlock();
        if (blockAtTarget != Blocks.air) {
            lastMinedState = currentStateAtTarget;
        }

        if (checkTimeout(blockAtTarget)) {
            // 如果 checkTimeout 因为超时关闭了功能，立即返回，防止下面代码重置状态
            if (!isActive) return;
            
            currentState = State.SWITCHING_TARGET;
            return;
        }
        
        MovingObjectPosition mouseOver = mc.objectMouseOver;
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
    
    private void handlePostSwitchDelay() {
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }

        delayTicks--;
        if (delayTicks <= 0) {
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
        // [已移除旧逻辑]
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
        if (!GhostConfig.AutoMine.enableVeinMining || currentTarget == null || lastMinedState == null || mc.theWorld.getBlockState(currentTarget).getBlock() != Blocks.air) return null;
        
        BlockPos lastPos = currentTarget;
        BlockPos bestNeighbor = null;
        double minScore = Double.MAX_VALUE;
        for (EnumFacing facing : EnumFacing.values()) {
            BlockPos neighborPos = lastPos.offset(facing);
            IBlockState neighborState = mc.theWorld.getBlockState(neighborPos);
            if (neighborState.equals(lastMinedState) && isTargetValid(neighborPos)) {
                double score = getAngleDifferenceToBlock(neighborPos);
                if (score < minScore) {
                    minScore = score;
                    bestNeighbor = neighborPos;
                }
            }
        }
        return bestNeighbor;
    }

    private BlockPos findBestTarget() {
        BlockPos blockToTemporarilyIgnore = null;
        // 优化点：当首次进入清理模式时，暂时忽略导致模式切换的那个方块
        if (mithrilOptimizationIsActive && !isCleanupMode) {
            Set<BlockPos> tempTitanium = new HashSet<>();
            findAndCategorizeMithrilAndTitanium(new HashSet<>(), tempTitanium);
            if (tempTitanium.size() >= GhostConfig.AutoMine.mithrilCleanupThreshold) {
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                    if(isTitanium(mc.theWorld.getBlockState(mop.getBlockPos()))){
                        blockToTemporarilyIgnore = mop.getBlockPos();
                    }
                }
            }
        }


        if (!mithrilOptimizationIsActive) {
            Set<BlockPos> allCandidates = new HashSet<>();
            findAndCategorizeMithrilAndTitanium(allCandidates, new HashSet<>());
            return findBestCandidate(allCandidates, null);
        }

        Set<BlockPos> mithrilCandidates = new HashSet<>();
        Set<BlockPos> titaniumCandidates = new HashSet<>();
        findAndCategorizeMithrilAndTitanium(mithrilCandidates, titaniumCandidates);

        boolean hasAbilityToMineTitanium;
        if (GhostConfig.AutoMine.enableAutomaticToolSwitching) {
            boolean hasBP5plus = findToolByBreakingPower(5, true) != -1;
            boolean hasBP4 = findToolByBreakingPower(4, false) != -1;
            hasAbilityToMineTitanium = hasBP5plus && hasBP4;
        } else {
            hasAbilityToMineTitanium = getBreakingPower(mc.thePlayer.getCurrentEquippedItem()) >= 5;
        }

        boolean shouldEnterCleanupMode = false;
        if (hasAbilityToMineTitanium && !titaniumCandidates.isEmpty()) {
            boolean cleanupThresholdMet = titaniumCandidates.size() >= GhostConfig.AutoMine.mithrilCleanupThreshold;
            boolean noMithrilLeft = mithrilCandidates.isEmpty();
            
            if (cleanupThresholdMet || (isCleanupMode && !titaniumCandidates.isEmpty()) || noMithrilLeft) {
                shouldEnterCleanupMode = true;
            }
        }

        if (shouldEnterCleanupMode) {
            if (!isCleanupMode) {
                isCleanupMode = true;
                sendMessage(LangUtil.translate("ghost.automine.status.cleanup_start"), EnumChatFormatting.GOLD);
            }
            return findBestCandidate(titaniumCandidates, blockToTemporarilyIgnore);
        } else {
            if (isCleanupMode) {
                isCleanupMode = false;
                sendMessage(LangUtil.translate("ghost.automine.status.cleanup_complete"), EnumChatFormatting.GREEN);
            }
            
            if (mithrilCandidates.isEmpty() && !titaniumCandidates.isEmpty() && !hasAbilityToMineTitanium && !cleanupToolTooWeakNotified) {
                sendMessage(LangUtil.translate("ghost.automine.warning.tool_too_weak_for_cleanup"), EnumChatFormatting.YELLOW);
                cleanupToolTooWeakNotified = true;
            }
            
            return findBestCandidate(mithrilCandidates, null);
        }
    }

    private void findAndCategorizeMithrilAndTitanium(Set<BlockPos> primaryOut, Set<BlockPos> secondaryOut) {
        int radius = GhostConfig.AutoMine.searchRadius;
        BlockPos playerPos = mc.thePlayer.getPosition();
        
        for (BlockPos pos : BlockPos.getAllInBox(playerPos.add(-radius, -radius, -radius), playerPos.add(radius, radius, radius))) {
            if (!isTargetValid(pos)) continue;
            
            IBlockState state = mc.theWorld.getBlockState(pos);
            
            if (mithrilOptimizationIsActive && isTitanium(state)) {
                secondaryOut.add(new BlockPos(pos));
            } 
            else if (isBlockTypeTargeted(state)) {
                primaryOut.add(new BlockPos(pos));
            }
        }
    }

    private BlockPos findBestCandidate(Set<BlockPos> candidates, BlockPos blockToIgnore) {
        if (candidates == null || candidates.isEmpty()) return null;

        BlockPos bestPos = null;
        double minScore = Double.MAX_VALUE;

        // 如果候选列表在移除忽略方块后只剩下一个或没有，那么就没必要忽略了
        boolean canAffordToIgnore = candidates.size() > 1;

        for (BlockPos candidate : candidates) {
            // “瞬时回避”逻辑
            if (canAffordToIgnore && candidate.equals(blockToIgnore)) {
                continue; // 跳过这个方块
            }

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
        
        // 如果因为忽略了方块导致没选到任何目标，那就退一步，把被忽略的方块选上
        if (bestPos == null && blockToIgnore != null && candidates.contains(blockToIgnore)) {
            return blockToIgnore;
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

        boolean isMithrilTarget = isBlockTypeTargeted(state);
        boolean isTitaniumTarget = mithrilOptimizationIsActive && isTitanium(state);

        if (!isMithrilTarget && !isTitaniumTarget) {
            return false;
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
            sendMessage(LangUtil.translate("ghost.automine.error.no_tool_found", requiredPower), EnumChatFormatting.RED);
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
            sendMessage(LangUtil.translate("ghost.automine.warning.no_mithril_targets"), EnumChatFormatting.YELLOW);
            return;
        }
        mithrilOptimizationIsActive = true;
        updateTitaniumBlockTypes();
    }
}