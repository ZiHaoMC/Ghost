package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig; // 引入配置类
import java.io.Writer;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.block.Block; // 需要引入 Block
import net.minecraft.block.state.IBlockState; // 需要引入 IBlockState
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.ClickEvent.Action;
import net.minecraft.init.Blocks; // 需要引入 Blocks
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.*;

public class GhostBlockCommand extends CommandBase {

    private static final Map<String, ClearConfirmation> pendingConfirmations = new HashMap<>();
    private static final int CONFIRMATION_TIMEOUT = 30 * 1000; // 30秒超时
    private static int lastTrackedDimension = 0; // 新增静态字段
    private static boolean isFirstJoin = true;
    private static final AtomicInteger taskIdCounter = new AtomicInteger(0); // 任务管理ID
    private static final Map<Integer, TaskSnapshot> pausedTasks = new HashMap<>();
    private static final Deque<UndoRecord> undoHistory = new ArrayDeque<>();

    // 使用同步列表确保线程安全
    private static final List<FillTask> activeTasks = Collections.synchronizedList(new ArrayList<>());
    private static final List<LoadTask> activeLoadTasks = Collections.synchronizedList(new ArrayList<>());
    private static final List<ClearTask> activeClearTasks = Collections.synchronizedList(new ArrayList<>()); // 确保这个定义只出现一次且是 synchronized

    // --- 颜色常量 ---
    private static final EnumChatFormatting LABEL_COLOR = EnumChatFormatting.GRAY;    // 标签颜色（如"填充进度"）
    private static final EnumChatFormatting VALUE_COLOR = EnumChatFormatting.YELLOW; // 数值颜色（如百分比）
    private static final EnumChatFormatting FINISH_COLOR = EnumChatFormatting.GREEN; // 完成消息颜色
        // ========= 用于延迟自动放置的静态成员变量 =========
    private static GhostBlockData.GhostBlockEntry pendingAutoPlaceEntry = null;
    private static BlockPos pendingAutoPlaceTargetPos = null;
    private static File pendingAutoPlaceFileRef = null; // 存储文件引用以便删除
    private static int autoPlaceTickDelayCounter = 0;
  //  private static final int AUTO_PLACE_TICKS_TO_WAIT = 40; // 等待2秒 (40 ticks)
    private static final int AUTO_PLACE_DURATION_TICKS = 40; // 持续放置2秒 (40 ticks)
    private static final int AUTO_PLACE_MAX_ATTEMPT_TICKS = 100; // 最多等待5秒 (100 ticks)
    private static boolean autoPlaceInProgress = false; // 标记自动放置过程是否已启动，防止isFirstJoin等逻辑过早执行
    // ========= 延迟自动放置成员变量结束 =========


    // ================ 撤销记录类 ================
    private static class UndoRecord {
        public enum OperationType { SET, CLEAR_BLOCK } // 操作类型枚举

        public final String undoFileName;
        public final Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups;
        public final OperationType operationType; // 操作类型标记

        public UndoRecord(String undoFileName, Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups, OperationType type) {
            this.undoFileName = undoFileName;
            // 防御性拷贝，确保内部状态不被外部修改
            this.fileBackups = fileBackups != null ? new HashMap<>(fileBackups) : new HashMap<>();
            this.operationType = type; // 记录操作类型
        }
    }

    private static class ClearConfirmation {
        final long timestamp;
        final List<File> targetFiles;

        // 修改构造函数为接受两个参数
        ClearConfirmation(List<File> targetFiles, long timestamp) {
            this.timestamp = timestamp;
            // 防御性拷贝，使列表不可变
            this.targetFiles = Collections.unmodifiableList(new ArrayList<>(targetFiles));
        }
    }

    public static void register() {
        System.out.println("[GhostBlock-DEBUG] 注册事件监听...");
        GhostBlockCommand instance = new GhostBlockCommand(); // 创建实例以注册非静态事件处理器
        MinecraftForge.EVENT_BUS.register(instance);
        MinecraftForge.EVENT_BUS.register(new CommandEventHandler()); // 注册静态事件处理器
    }

    // 静态内部类用于处理静态事件和注册命令
    private static class CommandEventHandler {
        public CommandEventHandler() {
            System.out.println("[GhostBlock-DEBUG] CommandEventHandler 已初始化 (处理Tick事件)");
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            // --- 其他Tick逻辑 (确认请求、任务队列) ---
            Iterator<Map.Entry<String, ClearConfirmation>> confirmIter = pendingConfirmations.entrySet().iterator();
            while (confirmIter.hasNext()) {
                Map.Entry<String, ClearConfirmation> entry = confirmIter.next();
                if (System.currentTimeMillis() - entry.getValue().timestamp > CONFIRMATION_TIMEOUT) {
                    confirmIter.remove();
                }
            }
            synchronized (activeTasks) {
                Iterator<FillTask> taskIter = activeTasks.iterator();
                while (taskIter.hasNext()) {
                    FillTask task = taskIter.next();
                    if (task.processBatch()) {
                        taskIter.remove();
                        if (!task.cancelled) System.out.println("[GhostBlock] 填充任务 #" + task.getTaskId() + " 完成");
                    }
                }
            }
            synchronized (activeLoadTasks) {
                Iterator<LoadTask> taskIter = activeLoadTasks.iterator();
                while (taskIter.hasNext()) {
                    LoadTask task = taskIter.next();
                    if (task.processBatch()) {
                        taskIter.remove();
                        if (!task.cancelled) System.out.println("[GhostBlock] 加载任务 #" + task.getTaskId() + " 完成");
                    }
                }
            }
            synchronized (activeClearTasks) {
                Iterator<ClearTask> taskIter = activeClearTasks.iterator();
                while (taskIter.hasNext()) {
                    ClearTask task = taskIter.next();
                    if (task.processBatch()) {
                        taskIter.remove();
                        if (!task.cancelled) System.out.println("[GhostBlock] 清除任务 #" + task.getTaskId() + " 完成");
                    }
                }
            }
            // --- 其他Tick逻辑结束 ---


        // ========= 处理持续自动放置的逻辑 =========
        if (GhostBlockCommand.autoPlaceInProgress && GhostBlockCommand.pendingAutoPlaceEntry != null) {
            GhostBlockCommand.autoPlaceTickDelayCounter++;
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            WorldClient world = Minecraft.getMinecraft().theWorld;

            GhostBlockEntry entryToRestore = GhostBlockCommand.pendingAutoPlaceEntry;
            BlockPos centerOriginalRecordedPos = GhostBlockCommand.pendingAutoPlaceTargetPos; // 这是文件中记录的原始中心幽灵方块位置
            File autoPlaceFile = GhostBlockCommand.pendingAutoPlaceFileRef;

            if (player == null || world == null || centerOriginalRecordedPos == null || autoPlaceFile == null) {
                System.out.println("[GhostBlock-DEBUG AutoPlaceTick] Tick " + GhostBlockCommand.autoPlaceTickDelayCounter + ": 玩家/世界/目标数据不完整，取消。");
                GhostBlockCommand.cleanupPendingAutoPlaceStatic(true);
                return;
            }

            int fileDimension = GhostBlockData.getDimensionFromFileName(autoPlaceFile.getName());
            if (fileDimension == Integer.MIN_VALUE || player.dimension != fileDimension) {
                System.out.println("[GhostBlock-DEBUG AutoPlaceTick] Tick " + GhostBlockCommand.autoPlaceTickDelayCounter + ": 维度检查失败或不符 (玩家:" + player.dimension + ", 文件:" + fileDimension + ")，取消。");
                GhostBlockCommand.cleanupPendingAutoPlaceStatic(true);
                return;
            }

            // 计算3x3平台的中心实际放置坐标 (例如，在原始记录位置下方1格，让玩家正好落在平台上)
            BlockPos centerActualPlacePos = centerOriginalRecordedPos.down(1); // <--- Y轴偏移量，可以调整为1或2

            if (GhostBlockCommand.autoPlaceTickDelayCounter <= AUTO_PLACE_DURATION_TICKS) {
                System.out.println("[GhostBlock-DEBUG AutoPlaceTick] Tick " + GhostBlockCommand.autoPlaceTickDelayCounter + "/" + AUTO_PLACE_DURATION_TICKS +
                                   ": 尝试在以 " + centerActualPlacePos + " 为中心的3x3平台 (原记录中心: " + centerOriginalRecordedPos + ") 放置幽灵方块。");

                BlockPos playerCurrentBlockPos = player.getPosition();
                BlockPos expectedPlayerStandPos = centerOriginalRecordedPos.up(); // 玩家应该站在原始中心幽灵方块的上面

                // --- 范围检查 ---
                // 检查玩家当前是否大致在期望站立位置的水平中心附近 (例如 XZ 误差 +/- 2格)
                // Y轴的检查可以放宽松一点，因为玩家可能正在下落
                boolean isInHorizontalRange = Math.abs(playerCurrentBlockPos.getX() - expectedPlayerStandPos.getX()) <= 2 &&
                                            Math.abs(playerCurrentBlockPos.getZ() - expectedPlayerStandPos.getZ()) <= 2;
                // 并且玩家的Y不能太低 (比如不能低于平台下方太多)
                boolean isVerticallyReasonable = playerCurrentBlockPos.getY() >= centerActualPlacePos.getY() - 2 && // 不低于平台下方2格
                                                 playerCurrentBlockPos.getY() <= centerActualPlacePos.getY() + 5;   // 不高于平台上方太多 (允许出生在更高处然后掉下来)


                System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 玩家当前实体格: " + playerCurrentBlockPos +
                                   ", 期望站立格(中心): " + expectedPlayerStandPos +
                                   ", 是否在水平范围: " + isInHorizontalRange + ", 是否在垂直合理范围: " + isVerticallyReasonable);

                if (isInHorizontalRange && isVerticallyReasonable) {
                    System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 玩家在期望范围内。开始尝试放置3x3平台...");
                    Block ghostBlockToPlace = Block.getBlockFromName(entryToRestore.blockId);
                    IBlockState stateToSet = null;
                    if (ghostBlockToPlace != null && ghostBlockToPlace != Blocks.air) {
                        stateToSet = ghostBlockToPlace.getStateFromMeta(entryToRestore.metadata);
                    } else {
                        System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 文件记录的幽灵方块类型无效或为空气。无法构成平台。");
                        GhostBlockCommand.cleanupPendingAutoPlaceStatic(true); // 清理并退出
                        return;
                    }

                    boolean platformPartiallyPlaced = false;
                    try {
                        // 遍历3x3区域
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                BlockPos currentPlatformPos = centerActualPlacePos.add(dx, 0, dz);
                                IBlockState stateAtPlatformPos = world.getBlockState(currentPlatformPos);

                                if (stateAtPlatformPos.getBlock() == Blocks.air) { // 只在空气位置放置
                                    boolean successThisBlock = world.setBlockState(currentPlatformPos, stateToSet, 2 | 16);
                                    if (successThisBlock) {
                                        platformPartiallyPlaced = true; // 只要有一个方块成功放置，就认为平台部分放置了
                                        world.markBlockForUpdate(currentPlatformPos);
                                        // 光照和渲染更新可以对整个区域进行一次，或者每个方块都做
                                        world.checkLightFor(EnumSkyBlock.BLOCK, currentPlatformPos);
                                        world.checkLightFor(EnumSkyBlock.SKY, currentPlatformPos);
                                        Minecraft.getMinecraft().renderGlobal.markBlockRangeForRenderUpdate(
                                            currentPlatformPos.getX(), currentPlatformPos.getY(), currentPlatformPos.getZ(),
                                            currentPlatformPos.getX(), currentPlatformPos.getY(), currentPlatformPos.getZ()
                                        );
                                    }
                                    if (GhostBlockCommand.autoPlaceTickDelayCounter == 1 && successThisBlock) {
                                        System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 平台部分在 " + currentPlatformPos + " 首次尝试成功。");
                                    }
                                } else if (GhostBlockCommand.autoPlaceTickDelayCounter == 1){
                                     System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 平台部分 " + currentPlatformPos + " 非空气 ("+stateAtPlatformPos.getBlock().getRegistryName()+")，跳过。");
                                }
                            }
                        }

                        if (platformPartiallyPlaced && (GhostBlockCommand.autoPlaceTickDelayCounter == 1 || (GhostBlockCommand.autoPlaceTickDelayCounter == AUTO_PLACE_DURATION_TICKS && GhostBlockCommand.autoPlaceInProgress))) {
                            player.addChatMessage(GhostBlockCommand.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.autoplace.platform_success", centerActualPlacePos.getX(), centerActualPlacePos.getY(), centerActualPlacePos.getZ()));
                            System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 3x3幽灵平台部分或全部放置 (tick: " + GhostBlockCommand.autoPlaceTickDelayCounter + ")");
                        }
                        
                        // 如果在持续时间内成功放置了部分平台，我们让它持续到结束
                        if (GhostBlockCommand.autoPlaceTickDelayCounter >= AUTO_PLACE_DURATION_TICKS) {
                             GhostBlockCommand.cleanupPendingAutoPlaceStatic(true); // 时间到，清理
                        }

                    } catch (Exception e) {
                        System.err.println("[GhostBlock-ERROR AutoPlaceTick] Tick " + GhostBlockCommand.autoPlaceTickDelayCounter + ": 持续放置3x3平台时发生异常:");
                        e.printStackTrace();
                        GhostBlockCommand.cleanupPendingAutoPlaceStatic(true);
                    }
                } else { // 玩家不在范围内
                    System.out.println("[GhostBlock-DEBUG AutoPlaceTick] Tick " + GhostBlockCommand.autoPlaceTickDelayCounter + ": 玩家不在期望的恢复范围内。");
                    // 如果超时了，即使不在范围内也要清理
                     if (GhostBlockCommand.autoPlaceTickDelayCounter > AUTO_PLACE_MAX_ATTEMPT_TICKS) {
                        System.out.println("[GhostBlock-DEBUG AutoPlaceTick] 玩家持续不在范围内且已超时，放弃并清理。");
                        GhostBlockCommand.cleanupPendingAutoPlaceStatic(true);
                    }
                }
            }

            // 总超时检查 (如果上面的逻辑没有提前清理)
            if (GhostBlockCommand.autoPlaceInProgress && GhostBlockCommand.autoPlaceTickDelayCounter > AUTO_PLACE_MAX_ATTEMPT_TICKS) {
                System.out.println("[GhostBlock-DEBUG AutoPlaceTick] Tick " + GhostBlockCommand.autoPlaceTickDelayCounter + ": 超过最大尝试Tick数 ("+AUTO_PLACE_MAX_ATTEMPT_TICKS+")，强制结束。");
                GhostBlockCommand.cleanupPendingAutoPlaceStatic(true);
            }
        }
    }
}
// ========= CommandEventHandler 修改结束 =========
        
        /**
         * 清理待处理的自动放置状态和相关文件引用。
         * @param deleteFile 如果为true，则尝试删除 pendingAutoPlaceFileRef 指向的文件。
         */
        private void cleanupPendingAutoPlace(boolean deleteFile) {
            if (deleteFile && GhostBlockCommand.pendingAutoPlaceFileRef != null && GhostBlockCommand.pendingAutoPlaceFileRef.exists()) {
                if (GhostBlockCommand.pendingAutoPlaceFileRef.delete()) {
                    System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 已成功删除自动放置文件: " + GhostBlockCommand.pendingAutoPlaceFileRef.getName());
                } else {
                    System.err.println("[GhostBlock-ERROR AutoPlaceCleanup] 未能删除自动放置文件: " + GhostBlockCommand.pendingAutoPlaceFileRef.getName());
                }
            }
            GhostBlockCommand.pendingAutoPlaceEntry = null;
            GhostBlockCommand.pendingAutoPlaceTargetPos = null;
            GhostBlockCommand.pendingAutoPlaceFileRef = null;
            GhostBlockCommand.autoPlaceTickDelayCounter = 0;
            boolean wasInProgress = GhostBlockCommand.autoPlaceInProgress; // 记录清理前的状态
            GhostBlockCommand.autoPlaceInProgress = false; // 标记过程结束
            System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 已清理待处理的自动放置状态。autoPlaceInProgress 设置为 false。");

            // 关键：如果自动放置过程（无论成功、失败或超时）结束了，
            // 并且之前 onEntityJoinWorld 因为 autoPlaceInProgress=true 而提前返回了，
            // 那么现在需要处理 isFirstJoin 的逻辑，否则玩家会一直卡在 isFirstJoin=true 的状态，
            // 并且 cleanupAndRestoreOnLoad 可能永远不会在后续的同维度重进时被调用。
            if (wasInProgress && GhostBlockCommand.isFirstJoin) {
                WorldClient world = Minecraft.getMinecraft().theWorld;
                EntityPlayer player = Minecraft.getMinecraft().thePlayer;
                // 确保我们仍然在有效的游戏环境中
                if (world != null && player != null) {
                    // 检查维度是否与当前玩家维度一致，避免在错误的上下文中执行
                    int fileDim = (GhostBlockCommand.pendingAutoPlaceFileRef != null) ?
                                  GhostBlockData.getDimensionFromFileName(GhostBlockCommand.pendingAutoPlaceFileRef.getName()) : player.dimension; // Fallback
                    if (player.dimension == fileDim || fileDim == Integer.MIN_VALUE) { // 如果维度匹配或无法从文件名解析
                        System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 自动放置处理结束，且仍是 isFirstJoin。处理首次加入逻辑...");
                        GhostBlockCommand.isFirstJoin = false;
                        GhostBlockCommand.lastTrackedDimension = player.dimension;
                        // 调用者 (GhostBlockCommand的实例) 的 cleanupAndRestoreOnLoad
                        // 由于这是静态内部类，需要获取外部类实例或将 cleanupAndRestoreOnLoad 设为静态
                        // 为了简单，假设 GhostBlockCommand 的一个实例可以被访问，或者 cleanupAndRestoreOnLoad 是静态的
                        // 这里我们先假设 cleanupAndRestoreOnLoad 可以被调用。
                        // 如果 cleanupAndRestoreOnLoad 不是静态的，你可能需要一个 GhostBlockCommand 的实例来调用它，
                        // 或者更好的做法是将 isFirstJoin 和 lastTrackedDimension 的管理放在 GhostBlockCommand 的非静态方法中，
                        // 然后由 tick 事件回调到那个非静态方法。
                        // 但为了保持当前结构，我们先这样：
                        GhostBlockCommand.cleanupAndRestoreOnLoad(world); // 直接通过类名调用静态方法
                    } else {
                        System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 自动放置处理结束，但玩家维度已改变，不执行 isFirstJoin 的 cleanupAndRestoreOnLoad。");
                    }
                }
            }
        }
    
    // 辅助方法：获取自动放置功能专用的保存文件名 (不含 .json 后缀)
    private static String getAutoPlaceSaveFileName(World world) {
        if (world == null) return "autoplace_unknown_world"; // 预防性代码
        return "autoplace_" + GhostBlockData.getWorldIdentifier(world);
    }

    // 非静态事件处理器，需要注册 GhostBlockCommand 的实例
        // ========= onEntityJoinWorld 方法 (混合方案：立即尝试 + 失败则转Tick延迟) =========
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
    if (!event.world.isRemote) return;
    if (!(event.entity instanceof EntityPlayer)) return;
    EntityPlayer player = Minecraft.getMinecraft().thePlayer;
    if (player == null || !event.entity.equals(player)) return;

    WorldClient world = (WorldClient) event.world;
    if (world == null) {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 世界为 null，无法继续。");
        return;
    }

    int currentDim = player.dimension;
    System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 玩家加入世界/切换维度 - 当前维度: " + currentDim + ", 上次跟踪维度: " + lastTrackedDimension);

    if (autoPlaceInProgress) {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 检测到 autoPlaceInProgress 为 true，等待Tick处理完成。");
        return;
    }
    if (pendingAutoPlaceEntry != null) {
        System.out.println("[GhostBlock-WARN onEntityJoinWorld] 进入时发现残留的 pendingAutoPlaceEntry，强制清理。");
        cleanupPendingAutoPlaceStatic(true);
    }

    if (GhostConfig.enableAutoPlaceOnJoin) {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 自动放置功能已启用。");
        String autoPlaceFileName = getAutoPlaceSaveFileName(world);
        File autoPlaceFile = GhostBlockData.getDataFile(world, autoPlaceFileName);

        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 尝试加载自动放置文件: " + autoPlaceFileName + ".json");
        List<GhostBlockData.GhostBlockEntry> autoPlaceEntries = GhostBlockData.loadData(world, Collections.singletonList(autoPlaceFileName));
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 从自动放置文件加载了 " + autoPlaceEntries.size() + " 个条目。");

        if (!autoPlaceEntries.isEmpty()) {
            pendingAutoPlaceEntry = autoPlaceEntries.get(0);
            pendingAutoPlaceTargetPos = new BlockPos(pendingAutoPlaceEntry.x, pendingAutoPlaceEntry.y, pendingAutoPlaceEntry.z);
            pendingAutoPlaceFileRef = autoPlaceFile;
            autoPlaceTickDelayCounter = 0; // 重置计数器
            autoPlaceInProgress = true;   // 标记开始Tick处理

            System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 已设置待处理的自动放置条目在 " + pendingAutoPlaceTargetPos + "。将由Tick事件持续处理。onEntityJoinWorld 返回。");
            // 返回，让Tick事件接管后续的放置、isFirstJoin判断等
            return;
        } else {
            System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 未找到自动放置文件或文件为空。不会启动延迟/持续放置。");
            autoPlaceInProgress = false; // 确保标记为false
        }
    } else {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 自动放置功能被禁用。");
        autoPlaceInProgress = false; // 确保标记为false
    }

    // 只有当不进行自动放置时，才执行标准的 isFirstJoin/维度切换逻辑
    System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] 未启动自动放置流程 (autoPlaceInProgress=" + autoPlaceInProgress + ")。继续执行标准的加入世界逻辑...");
    if (isFirstJoin) {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] (标准流程) 首次进入世界。初始化维度为 " + currentDim);
        lastTrackedDimension = currentDim;
        isFirstJoin = false;
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] (标准流程) 执行 cleanupAndRestoreOnLoad (首次加入)。");
        cleanupAndRestoreOnLoad(world);
        return;
    }
    if (lastTrackedDimension != currentDim) {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] (标准流程) 检测到维度变化: " + lastTrackedDimension + " → " + currentDim);
        cancelAllTasks(player);
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] (标准流程) 执行 cleanupAndRestoreOnLoad (维度切换)。");
        cleanupAndRestoreOnLoad(world);
    } else {
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] (标准流程) 重新加入相同维度 (" + currentDim + ")。");
        System.out.println("[GhostBlock-DEBUG onEntityJoinWorld] (标准流程) 执行 cleanupAndRestoreOnLoad (同维度重进)。");
        cleanupAndRestoreOnLoad(world);
    }
    lastTrackedDimension = currentDim;
}
// ========= onEntityJoinWorld 方法修改结束 =========

    @Override
    public String getCommandName() {
        return "cghostblock";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        // 使用 LangUtil 获取翻译后的用法字符串
        return LangUtil.translate("ghostblock.commands.usage");
    }

    @Override
    public int getRequiredPermissionLevel() {
        // 客户端命令通常不需要权限级别，或者返回 0
        return 0;
    }

     @Override
     public boolean canCommandSenderUseCommand(ICommandSender sender) {
         // 客户端命令通常对所有玩家可用
         return true;
     }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("cgb");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        WorldClient world = Minecraft.getMinecraft().theWorld; // 获取 world 实例

        // --- 处理 help 子命令 ---
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            displayHelp(sender); // 调用新的帮助显示方法
            return; // 显示帮助后直接返回，不继续执行后续逻辑
        }
        // --- 帮助处理结束 ---

        // --- 现有逻辑 ---
        if (world == null) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.not_in_world"));
        }

        // 如果希望无参数时也显示帮助：
        if (args.length == 0) {
             displayHelp(sender);
             return;
             // 或者抛出用法错误:
             // throw new WrongUsageException(getCommandUsage(sender));
        }

        String subCommand = args[0].toLowerCase(); // 转小写方便比较

        // ================ set 子命令 ================
        if ("set".equals(subCommand)) {
            // 用法检查
            if (args.length < 5) {
                 throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.set.usage")); // set x y z block [-s [filename]]
            }
            // 解析必须参数
            BlockPos pos = parseBlockPosLegacy(sender, args, 1); // 调用你自己的方法
            BlockStateProxy state = parseBlockState(args[4]);
            Block block = Block.getBlockById(state.blockId);
            if (block == null || block == Blocks.air) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
            }

            // 解析可选参数
            String saveFileName = null;
            boolean saveToFile = false;
            boolean userProvidedSave = false; // 标记用户是否输入了 -s
            if (args.length > 5) {
                 if (args[5].equalsIgnoreCase("-s") || args[5].equalsIgnoreCase("--save")) {
                     userProvidedSave = true; // 用户输入了 -s
                     saveToFile = true;
                     if (args.length > 6) {
                         saveFileName = args[6];
                          // 检查用户是否输入了 "filename" 作为占位符，如果是则视为使用默认文件名
                          if ("filename".equalsIgnoreCase(saveFileName) || saveFileName.trim().isEmpty()) {
                             saveFileName = null;
                         }
                     } else {
                        saveFileName = null; // -s 后面没有跟文件名，使用默认
                     }
                     if (args.length > 7) {
                         throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.set.usage"));
                     }
                 } else {
                     throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.set.usage"));
                 }
            }

            // 检查自动保存配置 (仅当用户未指定 -s 时)
            if (!userProvidedSave && GhostConfig.enableAutoSave) {
                saveToFile = true; // 启用保存
                saveFileName = GhostConfig.defaultSaveFileName; // 获取配置的默认文件名
                // 如果配置的默认名为空或 "default"，则使用 null (表示世界标识符)
                if (saveFileName == null || saveFileName.trim().isEmpty() || saveFileName.equalsIgnoreCase("default")) {
                   saveFileName = null;
                }
                // 可选：发送提示消息，告知用户自动保存已激活
                // sender.addChatMessage(formatMessage(EnumChatFormatting.ITALIC, "ghostblock.commands.autosave.activated"));
                System.out.println("[GhostBlock-DEBUG SET] 自动保存已启用，将保存到: " + (saveFileName == null ? "默认文件" : saveFileName));
            }


            List<BlockPos> positions = Collections.singletonList(pos);

            // --- 准备工作 (无论区块是否加载都需要) ---

            // ================ 收集原始方块 & 自动保存 ================
            // 注意：这里会尝试收集原始方块信息，即使区块未加载，world.getBlockState 也能在客户端获取缓存信息或默认值（如空气）
            List<GhostBlockData.GhostBlockEntry> autoEntries = collectOriginalBlocks(world, positions, state);
            if (!autoEntries.isEmpty()) {
                GhostBlockData.saveData(
                    world,
                    autoEntries,
                    getAutoClearFileName(world),
                    false // 合并模式
                );
                 System.out.println("[GhostBlock-DEBUG SET] 自动保存 " + autoEntries.size() + " 个条目。");
            } else {
                System.out.println("[GhostBlock-DEBUG SET] 无需自动保存 (可能已存在于自动文件)。");
            }

            // === 创建撤销记录 ===
            String baseId = GhostBlockData.getWorldBaseIdentifier(world);
            String undoFileName = "undo_" + baseId + "_dim_" + world.provider.getDimensionId() +
            "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            // 撤销文件保存的是 *将被覆盖* 的原始方块信息 (来自 autoEntries)
            GhostBlockData.saveData(world, autoEntries, undoFileName, true); // 覆盖模式写入撤销文件
            System.out.println("[GhostBlock-DEBUG SET] 创建撤销文件: " + undoFileName);

            // === 备份用户文件 (如果需要) ===
            Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups = new HashMap<>();
            String actualSaveFileNameForBackup = null; // 存储实际的文件名用于备份 key
            if (saveToFile) {
                 actualSaveFileNameForBackup = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                 List<GhostBlockData.GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(actualSaveFileNameForBackup));
                 fileBackups.put(actualSaveFileNameForBackup, existingEntries); // 备份
                 System.out.println("[GhostBlock-DEBUG SET] 备份用户文件: " + actualSaveFileNameForBackup);
            }

            // === 推送撤销记录 ===
            // 注意：撤销记录在实际放置方块或创建任务 *之前* 推送
             undoHistory.push(new UndoRecord(
                 undoFileName, // 指向包含原始方块信息的撤销文件
                 fileBackups,  // 包含用户文件的备份（如果保存了）
                 UndoRecord.OperationType.SET // 标记为 SET 操作
             ));
             System.out.println("[GhostBlock-DEBUG SET] 推送撤销记录。");


            // --- 检查区块是否加载 ---
            ChunkProviderClient chunkProvider = (ChunkProviderClient) world.getChunkProvider();
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            boolean sectionIsReady = false;
            if (chunkProvider.chunkExists(chunkX, chunkZ)) {
                Chunk chunk = chunkProvider.provideChunk(chunkX, chunkZ);
                int storageY = pos.getY() >> 4;
                 // 检查 Y 坐标有效性及 ExtendedBlockStorage 是否存在
                 if (pos.getY() >= 0 && pos.getY() < 256 && storageY >= 0 && storageY < chunk.getBlockStorageArray().length) {
                     if (chunk.getBlockStorageArray()[storageY] != null) {
                         sectionIsReady = true;
                     }
                 }
            }

             System.out.println("[GhostBlock-DEBUG SET] 检查坐标 " + pos + ", sectionIsReady=" + sectionIsReady);

            // --- 根据区块加载状态执行 ---
            if (sectionIsReady) {
                 // --- 区块已加载：直接放置 ---
                 System.out.println("[GhostBlock-DEBUG SET] 区块已加载，直接设置。");

                 // ================ 用户保存逻辑 (立即执行) ================
                 if (saveToFile) {
                     // 获取实际使用的保存文件名（处理 null 情况）
                     String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                     // 准备要保存到用户文件的数据
                     // 需要重新构建这个条目，因为它需要包含 *即将设置* 的幽灵方块信息 和 *当前* 的原始方块信息
                     // (autoEntries 可能是在区块加载前获取的，原始信息可能不准确，或者 autoEntries 为空)
                     IBlockState currentOriginalState = world.getBlockState(pos); // 获取当前的实际状态
                     Block currentOriginalBlock = currentOriginalState.getBlock();
                     List<GhostBlockData.GhostBlockEntry> userEntryToSave = Collections.singletonList(
                         new GhostBlockData.GhostBlockEntry(
                             pos,
                             block.getRegistryName().toString(), // 要设置的幽灵方块
                             state.metadata,                   // 要设置的元数据
                             currentOriginalBlock.getRegistryName().toString(), // 当前的原始方块
                             currentOriginalBlock.getMetaFromState(currentOriginalState) // 当前的元数据
                         )
                     );

                     // 保存到用户文件，使用合并模式 (false)
                     GhostBlockData.saveData(world, userEntryToSave, actualSaveFileName, false);
                      System.out.println("[GhostBlock-DEBUG SET] 保存到用户文件: " + actualSaveFileName);

                     String displayName = (saveFileName == null) ?
                        LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world))
                        : saveFileName;
                     sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                        "ghostblock.commands.save.success", displayName));
                 }

                 // ================ 设置幽灵方块 ================
                 try {
                    setGhostBlock(world, pos, state); // 调用静态方法设置
                    sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                        "ghostblock.commands.cghostblock.set.success", pos.getX(), pos.getY(), pos.getZ()));
                 } catch (CommandException e) {
                     // 理论上不应在这里失败，因为我们检查了 sectionIsReady
                     System.err.println("[GhostBlock SET ERROR] 在已加载区块设置失败: " + e.getMessage());
                     // 也许需要撤销之前的保存操作？（目前不处理，但可以考虑）
                     throw e; // 重新抛出异常
                 }

            } else {
                 // --- 区块未加载：创建任务 ---
                 System.out.println("[GhostBlock-DEBUG SET] 区块未加载，创建 FillTask。");

                 int taskId = taskIdCounter.incrementAndGet();

                 // **重要**: 准备用于 FillTask 保存的用户条目
                 // 这个条目需要在任务执行时被保存，因此现在就要准备好
                 List<GhostBlockData.GhostBlockEntry> entryForTaskSave = new ArrayList<>();
                 if (saveToFile && !autoEntries.isEmpty()) {
                     // 使用 autoEntries 中的信息，因为它是在命令执行时收集的
                     // (假设 autoEntries 包含对应 pos 的信息)
                     entryForTaskSave.add(autoEntries.get(0));
                 } else if (saveToFile) {
                      // 如果 autoEntries 为空（例如，因为坐标已在自动文件里），
                      // 我们需要基于当前（可能未加载的）世界状态创建一个条目
                      // 这可能不是完美的，但提供了最佳尝试
                      IBlockState currentOriginalState = world.getBlockState(pos); // 获取当前状态（可能是空气）
                      Block currentOriginalBlock = currentOriginalState.getBlock();
                      entryForTaskSave.add(new GhostBlockData.GhostBlockEntry(
                             pos,
                             block.getRegistryName().toString(), // 要设置的幽灵方块
                             state.metadata,                   // 要设置的元数据
                             currentOriginalBlock.getRegistryName().toString(), // 当前获取的原始方块
                             currentOriginalBlock.getMetaFromState(currentOriginalState) // 当前获取的元数据
                      ));
                       System.out.println("[GhostBlock-DEBUG SET] autoEntries 为空，为任务保存创建了新条目。");
                 }


                 // 创建 FillTask，注意将 saveToFile, saveFileName 和 *准备好的用户条目* 传递进去
                 FillTask task = new FillTask(
                     world,
                     state,
                     positions, // 包含单个位置的列表
                     1,        // totalBlocks (修正，现在构造函数内部会计算)
                     1,        // batchSize
                     saveToFile, // 是否需要用户保存
                     saveFileName,// 用户保存文件名
                     sender,
                     taskId,
                     entryForTaskSave // **传递准备好的、用于用户保存的条目**
                 );

                 synchronized (activeTasks) {
                     activeTasks.add(task);
                 }
                  System.out.println("[GhostBlock-DEBUG SET] 添加任务 #" + taskId + " 到 activeTasks。");

                 // 发送延迟放置的消息
                 sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW,
                     "ghostblock.commands.cghostblock.set.deferred", pos.getX(), pos.getY(), pos.getZ()));
                 // 发送通用提示
                 sender.addChatMessage(formatMessage(EnumChatFormatting.AQUA,
                     "ghostblock.commands.task.chunk_aware_notice"));
            }

        // ================ fill 子命令 ================
        } else if ("fill".equals(subCommand)) {
            // fill x1 y1 z1 x2 y2 z2 block [-b [size]] [-s [filename]]
             // 使用 LangUtil 获取 fill 命令的用法
            if (args.length < 8) {
                throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.fill.usage"));
            }
            // 解析必须参数
            BlockPos from = parseBlockPosLegacy(sender, args, 1);
            BlockPos to = parseBlockPosLegacy(sender, args, 4);
            BlockStateProxy state = parseBlockState(args[7]);
            Block block = Block.getBlockById(state.blockId);
            if (block == null || block == Blocks.air) { // 不允许填充空气
                 throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
            }

            // --- 配置读取 ---
            boolean configForceBatch = GhostConfig.alwaysBatchFill;
            int configForcedSize = GhostConfig.forcedBatchSize; // 配置中指定的强制大小 (>0 时有效)

            // --- 参数解析 (结合配置) ---
            boolean useBatch = configForceBatch; // 默认值受配置影响
            int batchSize = 100; // 默认批次大小
            boolean userProvidedBatchFlag = false; // 标记用户是否输入了 -b
            boolean userProvidedBatchSize = false; // 标记用户是否输入了 -b <大小>

            boolean saveToFile = false;
            String saveFileName = null;
            boolean userProvidedSave = false;

            for (int i = 8; i < args.length; ) { // 从第 9 个参数开始检查可选参数
                 String flag = args[i].toLowerCase();
                 if (flag.equals("-b") || flag.equals("--batch")) {
                     userProvidedBatchFlag = true; // 用户手动输入了 -b
                     useBatch = true; // 用户输入 -b 会覆盖配置中的 false (如果配置为 false)
                     i++; // 移动到可能的批次大小参数
                     if (i < args.length && isNumber(args[i])) { // 检查后面是否跟数字
                         try {
                             batchSize = Integer.parseInt(args[i]);
                             validateBatchSize(batchSize);
                             userProvidedBatchSize = true; // 用户指定了大小
                             i++; // 消耗数字参数
                         } catch (NumberFormatException | CommandException e) {
                              // 使用 LangUtil 获取错误信息
                             throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_batch_size"));
                         }
                     }
                     // 如果 -b 后面不是数字或没有参数了，则不设置 userProvidedBatchSize=true
                 } else if (flag.equals("-s") || flag.equals("--save")) {
                     userProvidedSave = true; // 用户输入了 -s
                     saveToFile = true;
                     i++; // 移动到可能的文件名参数
                     if (i < args.length && !args[i].startsWith("-")) {
                         saveFileName = args[i];
                         // 检查用户是否输入了 "filename" 作为占位符
                         if ("filename".equalsIgnoreCase(saveFileName) || saveFileName.trim().isEmpty()) {
                              saveFileName = null; // 使用默认
                         }
                         i++; // 消耗文件名参数
                     } else {
                         saveFileName = null; // -s 后面没有参数或跟着另一个标志，使用默认
                     }
                 } else {
                     throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.fill.usage"));
                 }
            }

            // --- 最终决定批次大小 (如果需要批处理) ---
            if (useBatch && !userProvidedBatchSize) {
                // 如果确定使用批处理 (来自配置或用户输入了-b但没给大小)
                // 并且用户没有明确指定大小
                if (configForceBatch && configForcedSize > 0) {
                    // 如果配置强制批处理且配置了强制大小，则使用配置的大小
                    batchSize = configForcedSize;
                    System.out.println("[GhostBlock DEBUG] Fill: 使用配置文件强制的批次大小: " + batchSize);
                } else {
                    // 否则（配置没强制大小，或用户只输入了-b），使用默认大小 100
                    batchSize = 100;
                     System.out.println("[GhostBlock DEBUG] Fill: 使用默认批次大小: " + batchSize);
                }
            } else if (useBatch && userProvidedBatchSize) {
                 System.out.println("[GhostBlock DEBUG] Fill: 使用用户指定的批次大小: " + batchSize);
            }

            // 检查自动保存配置 (仅当用户未指定 -s 时)
            if (!userProvidedSave && GhostConfig.enableAutoSave) {
                saveToFile = true; // 启用保存
                saveFileName = GhostConfig.defaultSaveFileName; // 获取配置的默认文件名
                // 如果配置的默认名为空或 "default"，则使用 null (表示世界标识符)
                if (saveFileName == null || saveFileName.trim().isEmpty() || saveFileName.equalsIgnoreCase("default")) {
                   saveFileName = null;
                }
                // 可选：发送提示消息
                // sender.addChatMessage(formatMessage(EnumChatFormatting.ITALIC, "ghostblock.commands.autosave.activated"));
                System.out.println("[GhostBlock-DEBUG FILL] 自动保存已启用，将保存到: " + (saveFileName == null ? "默认文件" : saveFileName));
            }

             // --- 添加配置相关的调试信息 ---
             System.out.println("[GhostBlock DEBUG] Fill Config: alwaysBatchFill=" + configForceBatch + ", forcedBatchSize=" + configForcedSize);
             System.out.println("[GhostBlock DEBUG] Fill Params: userProvidedBatchFlag=" + userProvidedBatchFlag + ", userProvidedBatchSize=" + userProvidedBatchSize);
             System.out.println("[GhostBlock DEBUG] Fill Final Decision: useBatch=" + useBatch + ", finalBatchSize=" + (useBatch ? batchSize : "N/A"));


            // 计算所有方块位置
            List<BlockPos> allBlocks = new ArrayList<>();
            int minX = Math.min(from.getX(), to.getX());
            int maxX = Math.max(from.getX(), to.getX());
            int minY = Math.min(from.getY(), to.getY());
            int maxY = Math.max(from.getY(), to.getY());
            int minZ = Math.min(from.getZ(), to.getZ());
            int maxZ = Math.max(from.getZ(), to.getZ());

            long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            System.out.println("[GhostBlock DEBUG] Fill: 计算体积: " + volume + " 方块。");
             // 对过大的同步填充增加一个警告或检查点 (可选，但建议保留)
            if (volume > 32768 && !useBatch) {
                 System.out.println("[GhostBlock WARN] Fill: 尝试进行大型同步填充 (" + volume + " 方块)。这可能导致延迟或失败。考虑使用 -b。");
            }

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        allBlocks.add(new BlockPos(x, y, z));
                    }
                }
            }

             if (allBlocks.isEmpty()) {
                  sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.fill.empty_area"));
                 return;
             }
             System.out.println("[GhostBlock DEBUG] Fill: 总计需处理方块: " + allBlocks.size());

            // ================ 自动保存逻辑 (填充前收集) ================
            List<GhostBlockData.GhostBlockEntry> autoEntries = collectOriginalBlocks(world, allBlocks, state);
            if (!autoEntries.isEmpty()) {
                System.out.println("[GhostBlock DEBUG] Fill: 已收集 " + autoEntries.size() + " 个原始方块用于自动保存/撤销。");
                GhostBlockData.saveData( world, autoEntries, getAutoClearFileName(world), false );
            } else {
                 System.out.println("[GhostBlock DEBUG] Fill: 无新的原始方块可收集。");
            }

            // === 创建撤销记录 ===
            String baseId = GhostBlockData.getWorldBaseIdentifier(world);
            String undoFileName = "undo_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            GhostBlockData.saveData(world, autoEntries, undoFileName, true); // 覆盖模式
            System.out.println("[GhostBlock DEBUG] Fill: 已将撤销数据保存到 " + undoFileName + ".json");

            // ================ 用户文件备份 (用于撤销) ================
            Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups = new HashMap<>();
            if (saveToFile) {
                String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                System.out.println("[GhostBlock DEBUG] Fill: 指定了 -s。正在备份文件: " + actualSaveFileName);
                List<GhostBlockData.GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(actualSaveFileName));
                fileBackups.put(actualSaveFileName, existingEntries);
            }

             // === 创建并推送撤销记录 ===
             undoHistory.push(new UndoRecord( undoFileName, fileBackups, UndoRecord.OperationType.SET ));
             System.out.println("[GhostBlock DEBUG] Fill: 已推送撤销记录。");


            // --- 修改后的检查逻辑: 检查 ExtendedBlockStorage ---
            boolean implicitBatchRequired = false;
            if (!useBatch && !allBlocks.isEmpty()) {
                boolean foundNotReady = false;
                System.out.println("[GhostBlock DEBUG] Fill: 开始使用 ExtendedBlockStorage 进行隐式批处理检查 (useBatch=" + useBatch + ")");
                ChunkProviderClient chunkProvider = (ChunkProviderClient) world.getChunkProvider();
                int checkLimit = 5000; // 保持检查上限
                int checkedCount = 0;

                for (BlockPos pos : allBlocks) {
                    if (checkedCount >= checkLimit) {
                        System.out.println("[GhostBlock DEBUG] Fill: 达到检查限制 (" + checkLimit + "), 假设可能存在未就绪的区块。");
                        foundNotReady = true;
                        break;
                    }
                    checkedCount++;

                    int chunkX = pos.getX() >> 4;
                    int chunkZ = pos.getZ() >> 4;
                    boolean sectionReady = false;

                    // 1. 检查 Chunk 对象是否存在
                    if (chunkProvider.chunkExists(chunkX, chunkZ)) {
                        Chunk chunk = chunkProvider.provideChunk(chunkX, chunkZ);
                        int storageY = pos.getY() >> 4;
                        // 2. 检查 Y 坐标是否有效，以及对应的 ExtendedBlockStorage 是否存在 (非 null)
                        if (pos.getY() >= 0 && pos.getY() < 256 && storageY >= 0 && storageY < chunk.getBlockStorageArray().length) {
                            if (chunk.getBlockStorageArray()[storageY] != null) {
                                sectionReady = true; // 只有 Chunk 存在且 Storage 存在才算准备好
                            }
                        }
                    }

                    // 打印调试信息
                    if (!sectionReady || checkedCount < 10 || checkedCount % 1000 == 0) {
                         System.out.println("[GhostBlock DEBUG] Fill Check EBS: Pos=" + pos + ", sectionReady=" + sectionReady);
                    }

                    if (!sectionReady) {
                        System.out.println("[GhostBlock DEBUG] Fill: *** 在检查 " + checkedCount + " 个方块后发现未就绪的区块位于 " + pos + "。 ***");
                        foundNotReady = true;
                        break; // 找到一个未就绪的就足够了
                    }
                }
                System.out.println("[GhostBlock DEBUG] Fill: EBS 检查完成。总检查数: " + checkedCount + ", foundNotReady=" + foundNotReady);

                if (foundNotReady) {
                    implicitBatchRequired = true;
                    System.out.println("[GhostBlock DEBUG] Fill: 由于发现未就绪区块，设置 implicitBatchRequired = true。");
                    // 仅在用户未指定 -b 时才提示 (因为 -b 优先级更高)
                    if (!userProvidedBatchFlag) {
                        sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.fill.implicit_batch_notice"));
                    }
                } else {
                     System.out.println("[GhostBlock DEBUG] Fill: 所有已检查区块似乎都已就绪。implicitBatchRequired 保持 false。");
                }
            } else {
                 System.out.println("[GhostBlock DEBUG] Fill: 跳过隐式批处理检查，因为 useBatch=" + useBatch + " 或 allBlocks 为空。");
            }
            // --- 检查结束 ---

            System.out.println("[GhostBlock DEBUG] Fill: 决策点 -> useBatch(最终)=" + useBatch + ", implicitBatchRequired=" + implicitBatchRequired);

            // ================ 执行填充 (同步或异步, 使用最终的 useBatch 和 batchSize) ================
            if (useBatch || implicitBatchRequired) { // <--- 使用最终决定的 useBatch
                 System.out.println("[GhostBlock DEBUG] Fill: === 进入批处理执行路径 ===");
                 int fillTaskId = taskIdCounter.incrementAndGet();
                 int actualBatchSize = batchSize; // <--- 使用最终决定的 batchSize

                 // 如果是隐式批处理触发，但最终批次大小仍是默认值，而配置中有强制大小，优先使用配置大小
                 if (implicitBatchRequired && !userProvidedBatchSize && configForceBatch && configForcedSize > 0) {
                     actualBatchSize = configForcedSize;
                     System.out.println("[GhostBlock DEBUG] Fill: 隐式批处理触发，使用配置文件强制大小: " + actualBatchSize);
                 } else if (implicitBatchRequired && actualBatchSize == 100) {
                      System.out.println("[GhostBlock DEBUG] Fill: 隐式批处理触发，使用默认大小: 100");
                 }


                 System.out.println("[GhostBlock DEBUG] Fill: 启动 FillTask #" + fillTaskId + "，实际批次大小=" + actualBatchSize);
                 activeTasks.add(new FillTask(world, state, allBlocks, allBlocks.size(), actualBatchSize, saveToFile, saveFileName, sender, fillTaskId, autoEntries));
                 sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.fill.batch_started", fillTaskId, allBlocks.size(), actualBatchSize));
                 sender.addChatMessage(formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.task.chunk_aware_notice"));
            } else {
                 System.out.println("[GhostBlock DEBUG] Fill: === 进入同步执行路径 ===");
                int count = 0;
                long startTime = System.currentTimeMillis();

                for(BlockPos pos : allBlocks) {
                    try {
                        // setGhostBlock 现在内部不直接抛出 Unloaded 异常，但在批量模式下会检查
                        // 在同步模式下，我们依赖之前的 EBS 检查确保了安全
                        if (world.isRemote) { // 确保只在客户端执行实际放置
                            IBlockState blockStateToSet = block.getStateFromMeta(state.metadata);
                            world.setBlockState(pos, blockStateToSet, 3); // 使用标志3尝试更新邻居和光照
                        } else {
                            // 理论上不应执行到这里，但作为保险
                            setGhostBlock(world, pos, state);
                        }
                        count++;
                    } catch (Exception e) { // 捕捉更广泛的异常以防万一
                        // 理论上这个错误不应该在这个分支发生，因为 EBS 检查通过了
                        System.err.println("[GhostBlock Fill Sync FATAL] 设置方块时发生意外错误 (本应已就绪) " + pos + ": " + e.getMessage());
                        e.printStackTrace();
                        sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "ghostblock.commands.error.internal_sync_place_failed", pos.toString()));
                        // 考虑是否中断
                        // return;
                    }
                }

                long endTime = System.currentTimeMillis();
                System.out.println("[GhostBlock DEBUG] Fill: 同步执行在 " + (endTime - startTime) + " ms 内完成 " + count + " 个方块。");

                // 同步填充完成后执行用户保存逻辑
                if (saveToFile) {
                     String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                     System.out.println("[GhostBlock DEBUG] Fill SYNC: 保存用户数据到 " + actualSaveFileName);
                     // --- 修正：传递给 saveData 的应该是 autoEntries ---
                     // 因为 autoEntries 包含了每个位置对应的原始方块信息，这才是用户保存时需要的
                     if (autoEntries != null && !autoEntries.isEmpty()) {
                         GhostBlockData.saveData(world, autoEntries, actualSaveFileName, false); // 使用 autoEntries 保存

                         String displayName = (saveFileName == null) ?
                            LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world))
                            : saveFileName;
                         sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                            "ghostblock.commands.save.success", displayName));
                     } else {
                         System.out.println("[GhostBlock DEBUG] Fill SYNC WARN: autoEntries 为空，无法保存用户文件。");
                         // 可以选择性地通知用户保存失败
                          sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW,
                              "ghostblock.commands.save.warn.no_data",
                              (saveFileName == null ? LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world)) : saveFileName)));
                     }
                }

                // 发送填充成功消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                    "ghostblock.commands.cghostblock.fill.success", count));
            }

        // ================ load 子命令 ================
        } else if ("load".equals(subCommand)) {
            // load [filename...] [-b [size]]

            List<String> fileNames = new ArrayList<>();
            int loadBatchSize = 100; // 默认批次大小
            boolean useBatch = false;
            boolean explicitFilesProvided = false;

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.equalsIgnoreCase("-b") || arg.equalsIgnoreCase("--batch")) {
                    useBatch = true;
                    i++;
                    if (i < args.length && isNumber(args[i])) {
                        try {
                            loadBatchSize = Integer.parseInt(args[i]);
                            validateBatchSize(loadBatchSize);
                        } catch (NumberFormatException | CommandException e) {
                             throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_batch_size"));
                        }
                    } else {
                         i--; // 回退索引
                         loadBatchSize = 100; // 使用默认值
                    }
                } else if (!arg.startsWith("-")) {
                     explicitFilesProvided = true;
                     if (!arg.toLowerCase().startsWith("clear_") && !arg.toLowerCase().startsWith("undo_")) {
                        fileNames.add(arg);
                     } else {
                        sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.ignored_internal_file", arg));
                     }
                } else {
                     throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.load.usage"));
                }
            }

            if (args.length == 1) { // 只输入 /cgb load
                String defaultFile = GhostBlockData.getWorldIdentifier(world);
                if (!defaultFile.toLowerCase().startsWith("clear_") && !defaultFile.toLowerCase().startsWith("undo_")) {
                    fileNames.add(null); // null 代表默认
                    sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.load.using_default_file"));
                } else {
                    throw new CommandException(LangUtil.translate("ghostblock.commands.load.error.default_is_internal"));
                }
            } else if (fileNames.isEmpty()) { // 输入了参数但没有有效文件名
                 if (!explicitFilesProvided) { // 如果用户没尝试提供文件名 (e.g., /cgb load -b)
                     String defaultFile = GhostBlockData.getWorldIdentifier(world);
                     if (!defaultFile.toLowerCase().startsWith("clear_") && !defaultFile.toLowerCase().startsWith("undo_")) {
                         fileNames.add(null);
                         sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.load.using_default_file"));
                     } else {
                         throw new CommandException(LangUtil.translate("ghostblock.commands.load.error.default_is_internal"));
                     }
                 } else { // 用户提供了文件名但都被过滤了
                     throw new CommandException(LangUtil.translate("ghostblock.commands.load.error.no_valid_files"));
                 }
            }

            // --- 加载数据 ---
            List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world, fileNames);

            if (entries.isEmpty()) {
                String fileDescription;
                if (fileNames.contains(null)) {
                     fileDescription = LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world));
                } else {
                     fileDescription = String.join(", ", fileNames);
                }
                sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.empty_or_missing", fileDescription));
                return;
            }

            // --- 自动保存和撤销记录 (在检查之前执行) ---
            // ================ 加载时收集原始方块并自动保存 ================
            List<GhostBlockData.GhostBlockEntry> validAutoSaveEntries = new ArrayList<>();
            String autoFileName = getAutoClearFileName(world);
            List<GhostBlockData.GhostBlockEntry> existingAutoEntries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
            Set<String> existingKeys = existingAutoEntries.stream()
                .map(e -> e.x + "," + e.y + "," + e.z)
                .collect(Collectors.toSet());

            for (GhostBlockData.GhostBlockEntry loadedEntry : entries) {
                BlockPos pos = new BlockPos(loadedEntry.x, loadedEntry.y, loadedEntry.z);
                String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                if (!existingKeys.contains(key)) {
                     IBlockState originalState = world.getBlockState(pos); // 获取当前世界的方块
                     Block originalBlock = originalState.getBlock();
                     validAutoSaveEntries.add(new GhostBlockData.GhostBlockEntry(
                         pos,
                         loadedEntry.blockId, // 这是要加载的幽灵方块
                         loadedEntry.metadata,
                         originalBlock.getRegistryName().toString(), // 这是当前的原始方块
                         originalBlock.getMetaFromState(originalState)
                     ));
                }
            }
            if (!validAutoSaveEntries.isEmpty()) {
                GhostBlockData.saveData( world, validAutoSaveEntries, autoFileName, false ); // 合并模式
                System.out.println("[GhostBlock-DEBUG LOAD] 自动保存 " + validAutoSaveEntries.size() + " 个新条目。");
            }

            // === 创建撤销记录 ===
            String baseId = GhostBlockData.getWorldBaseIdentifier(world);
            String undoFileName = "undo_" + baseId + "_dim_" + world.provider.getDimensionId() +
            "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
             // 保存将被覆盖的原始方块信息 (来自 validAutoSaveEntries)
            GhostBlockData.saveData(world, validAutoSaveEntries, undoFileName, true); // 覆盖模式
            Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups = new HashMap<>(); // load 不修改用户文件
            undoHistory.push(new UndoRecord( undoFileName, fileBackups, UndoRecord.OperationType.SET ));
            System.out.println("[GhostBlock-DEBUG LOAD] 创建并推送撤销记录: " + undoFileName);


            // --- 检查是否需要隐式批处理 ---
            boolean implicitBatchRequired = false;
            if (!useBatch && !entries.isEmpty()) { // 仅在未指定 -b 且有条目时检查
                System.out.println("[GhostBlock DEBUG Load] 开始隐式批处理检查...");
                ChunkProviderClient chunkProvider = (ChunkProviderClient) world.getChunkProvider();
                int checkLimit = 5000; // 检查上限，防止卡顿
                int checkedCount = 0;
                boolean foundNotReady = false;

                for (GhostBlockData.GhostBlockEntry entry : entries) {
                    if (checkedCount >= checkLimit) {
                        System.out.println("[GhostBlock DEBUG Load] 达到检查上限 (" + checkLimit + "), 强制使用批处理模式。");
                        foundNotReady = true;
                        break;
                    }
                    checkedCount++;

                    BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                    // 执行 Section 准备情况检查
                    int chunkX = pos.getX() >> 4;
                    int chunkZ = pos.getZ() >> 4;
                    boolean sectionIsReady = false;
                    if (chunkProvider.chunkExists(chunkX, chunkZ)) {
                         Chunk chunk = chunkProvider.provideChunk(chunkX, chunkZ);
                         int storageY = pos.getY() >> 4;
                         if (pos.getY() >= 0 && pos.getY() < 256 && storageY >= 0 && storageY < chunk.getBlockStorageArray().length) {
                             if (chunk.getBlockStorageArray()[storageY] != null) {
                                 sectionIsReady = true;
                             }
                         }
                    }

                    if (!sectionIsReady) {
                        System.out.println("[GhostBlock DEBUG Load] 在检查了 " + checkedCount + " 个条目后发现未就绪的 Section: " + pos + ". 强制使用批处理模式。");
                        foundNotReady = true;
                        break; // 找到一个就够了
                    }
                }
                System.out.println("[GhostBlock DEBUG Load] 隐式批处理检查完成。已检查: " + checkedCount + ", foundNotReady=" + foundNotReady);
                if (foundNotReady) {
                    implicitBatchRequired = true;
                    // 仅在用户未明确要求批处理时发出通知
                    if (!useBatch) {
                         sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.implicit_batch_notice")); // 语言键
                    }
                }
            }
            // --- 检查结束 ---


            // --- 执行加载 (同步或异步) ---
            if (useBatch || implicitBatchRequired) {
                 // === 批处理模式 (LoadTask) ===
                 System.out.println("[GhostBlock DEBUG Load] 进入 BATCH 执行路径。");
                 int loadTaskId = taskIdCounter.incrementAndGet();
                 // 如果是隐式批处理，使用默认批次大小；否则使用用户指定的或默认的
                 int actualBatchSize = useBatch ? loadBatchSize : 100;
                 activeLoadTasks.add(new LoadTask(world, entries, actualBatchSize, sender, loadTaskId));
                 sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.load.batch_started", loadTaskId, entries.size(), actualBatchSize));
                 sender.addChatMessage(formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.task.chunk_aware_notice")); // 通用提示

            } else {
                // === 同步加载模式 ===
                 System.out.println("[GhostBlock DEBUG Load] 进入 SYNC 执行路径。");
                int successCount = 0;
                int failCount = 0;
                int skippedCount = 0; // 用于记录理论上不应发生的跳过
                long startTime = System.currentTimeMillis();

                for (GhostBlockData.GhostBlockEntry entry : entries) {
                    BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);

                    // 预检查应该保证这里是加载的，但保留 world.isBlockLoaded 作为最后防线
                    if (world.isBlockLoaded(pos)) {
                        Block block = Block.getBlockFromName(entry.blockId);
                        if (block != null && block != Blocks.air) {
                            try {
                                 // 使用 setGhostBlock 确保客户端正确设置
                                 setGhostBlock(world, pos, new BlockStateProxy(Block.getIdFromBlock(block), entry.metadata));
                                 successCount++;
                            } catch (CommandException e) { // setGhostBlock 内部可能抛出
                                 System.out.println("[GhostBlock Load Sync WARN] 在已加载区块设置方块时失败: " + pos + " (" + e.getMessage() + ")");
                                 failCount++;
                            } catch (Exception e) { // 捕获意外错误
                                System.err.println("[GhostBlock Load Sync FATAL] 设置方块时发生意外错误 " + pos + ": " + e.getMessage());
                                e.printStackTrace();
                                failCount++;
                           }
                        } else {
                            System.out.println("[GhostBlock Load Sync WARN] 无效的方块 ID '" + entry.blockId + "' 或尝试在 " + pos + " 加载空气方块。");
                            failCount++;
                        }
                    } else {
                        // 这个情况理论上不应该发生，因为预检查通过了
                        System.out.println("[GhostBlock Load Sync WARN] 方块 " + pos + " 未加载，跳过 (这可能表示预检查逻辑或时序问题)。");
                        skippedCount++;
                    }
                }
                long endTime = System.currentTimeMillis();
                System.out.println("[GhostBlock DEBUG Load] 同步执行在 " + (endTime - startTime) + " ms 内完成。");

                // 发送同步加载结果
                sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                    "ghostblock.commands.load.complete", successCount, entries.size()));
                 if (failCount > 0) {
                     sender.addChatMessage(formatMessage(EnumChatFormatting.RED,
                         "ghostblock.commands.load.failed", failCount));
                 }
                 if (skippedCount > 0) { // 如果有跳过的 (异常情况)
                      sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW,
                          "ghostblock.commands.task.sync_skipped", skippedCount)); // 复用现有键
                 }
            }

        // ================ clear 子命令 ================
        } else if ("clear".equalsIgnoreCase(subCommand)) {
            handleClearCommand(sender, world, args);

        // ================ cancel 子命令 ================
        } else if ("cancel".equalsIgnoreCase(subCommand)) {
            handleCancelCommand(sender, args);

        // ================ resume 子命令 ================
        } else if ("resume".equalsIgnoreCase(subCommand)) {
            handleResumeCommand(sender, args);

        // ================ undo 子命令 ================
        } else if ("undo".equalsIgnoreCase(subCommand)) {
            handleUndoCommand(sender, world);

        // ================ confirm_clear 子命令 ================
        } else if ("confirm_clear".equalsIgnoreCase(subCommand)) {
            if (!(sender instanceof EntityPlayer)) {
                 // 使用 LangUtil 获取翻译 (这个键通常是 Minecraft 自带的)
                throw new CommandException("commands.generic.player.unsupported");
            }
             // 使用 LangUtil 获取 clear 命令的用法
            if (args.length < 2) { // 至少需要 /cgb confirm_clear <filename>
                 throw new CommandException(LangUtil.translate("ghostblock.commands.clear.usage")); // 或其他适当的错误
            }

            // 获取命令中指定要确认删除的文件名列表
            List<String> filesToConfirm = Arrays.asList(args).subList(1, args.length);

            // 从挂起确认中查找匹配发送者的记录
            ClearConfirmation confirmation = pendingConfirmations.get(sender.getName());

            if (confirmation == null || System.currentTimeMillis() - confirmation.timestamp > CONFIRMATION_TIMEOUT) {
                pendingConfirmations.remove(sender.getName()); // 清除过期或不存在的
                 // 使用 LangUtil 获取错误信息
                throw new CommandException(LangUtil.translate("ghostblock.commands.clear.confirm_expired"));
            }

            // 验证待确认的文件列表是否与命令中的匹配 (忽略顺序和 .json 后缀)
            // 从确认记录中获取基础文件名 (不含 .json)
            Set<String> confirmationBaseFileNames = confirmation.targetFiles.stream()
                    .map(f -> f.getName().replace(".json", ""))
                    .collect(Collectors.toSet());
            // 从命令参数中获取基础文件名
            Set<String> commandBaseFileNames = filesToConfirm.stream()
                     .map(s -> s.replace(".json", "")) // 确保比较的是基础名
                     .collect(Collectors.toSet());

            if (!confirmationBaseFileNames.equals(commandBaseFileNames)) {
                 // 文件列表不匹配，可能是过期或错误的确认命令
                  // 使用 LangUtil 获取错误信息
                 throw new CommandException(LangUtil.translate("ghostblock.commands.clear.confirm_expired")); // 复用过期消息
            }

            // --- 确认成功，开始执行删除 ---

            // === 备份要删除的文件内容 ===
            Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups = new HashMap<>();
             // 使用命令中的基础文件名列表进行备份
            for (String baseFileName : commandBaseFileNames) {
                 List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(baseFileName));
                 fileBackups.put(baseFileName, entries); // 使用基础文件名作为 key
            }

            // === 创建撤销记录 (在删除之前) ===
            String baseId = GhostBlockData.getWorldBaseIdentifier(world);
            // 使用特定名称格式表示文件清除操作
            String undoFileName = "undo_clear_file_" + baseId + "_dim_" + world.provider.getDimensionId()
                + "_" + System.currentTimeMillis(); // 撤销文件名本身不含 .json

            // 不需要实际创建 undo 文件来存储备份，UndoRecord 类直接持有备份数据
            undoHistory.push(new UndoRecord(
                undoFileName, // 这个文件名仅作为标识符
                fileBackups,  // 包含文件的备份数据
                UndoRecord.OperationType.CLEAR_BLOCK // 用 CLEAR_BLOCK 类型标记文件清除
            ));
             // 使用 formatMessage 发送带翻译的消息
            sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.record_created_clear")); // 可选反馈


            // --- 执行实际删除 ---
            List<String> deletedFiles = new ArrayList<>(); // 存储删除成功的带 .json 的文件名
            List<String> failedFiles = new ArrayList<>();   // 存储删除失败的带 .json 的文件名

            for (File file : confirmation.targetFiles) { // 使用确认对象中的 File 列表进行删除
                if (file.exists()) { // 再次检查文件是否存在
                    if (file.delete()) {
                        deletedFiles.add(file.getName()); // 添加带 .json 的名称
                    } else {
                        failedFiles.add(file.getName()); // 添加带 .json 的名称
                    }
                } else {
                    // 文件在确认后被删除？可以忽略或记录
                    System.out.println("[GhostBlock] 文件在确认删除时已不存在: " + file.getName());
                    // 也可以算作删除成功？或者单独记录
                }
            }

            // 清除确认状态
            pendingConfirmations.remove(sender.getName());

            // 发送结果
            if (!deletedFiles.isEmpty()) {
                 // 使用 formatMessage 发送带翻译的消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                    "ghostblock.commands.clear.success",
                    String.join(", ", deletedFiles))); // 显示带 .json 的文件名
            }
            if (!failedFiles.isEmpty()) {
                // 不需要抛出异常，只需报告失败
                 // 使用 formatMessage 发送带翻译的消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.RED,
                    "ghostblock.commands.clear.failed",
                    String.join(", ", failedFiles))); // 显示带 .json 的文件名
            }
        } else {
             throw new WrongUsageException(getCommandUsage(sender)); // 处理未知子命令
        }
    }
    
        // ========= 旧版本坐标解析方法开始 =========
    /**
     * 解析命令参数中的坐标，支持相对坐标(~)。
     * 这是为了更精确地控制基于玩家浮点位置的相对坐标计算。
     *
     * @param sender 命令发送者
     * @param args   命令参数数组
     * @param index  坐标参数的起始索引
     * @return 解析后的 BlockPos
     * @throws CommandException 如果坐标无效
     */
    private BlockPos parseBlockPosLegacy(ICommandSender sender, String[] args, int index) throws CommandException {
        // 检查参数数量是否足够
        if (args.length < index + 3) {
            // 通常由调用者在之前检查，但这里也加一层保护
            throw new WrongUsageException(getCommandUsage(sender)); // 或者更具体的错误消息
        }

        EntityPlayer player = sender instanceof EntityPlayer ? (EntityPlayer) sender : null;
        // 获取玩家精确的 double 型坐标作为相对坐标的基准
        // 如果发送者不是玩家，或者玩家对象不存在，则基准为0,0,0 (虽然此时~坐标会失败)
        double baseX = (player != null) ? player.posX : 0.0D;
        double baseY = (player != null) ? player.posY : 0.0D;
        double baseZ = (player != null) ? player.posZ : 0.0D;

        double x = parseRelativeCoordinateLegacy(sender, args[index], baseX);
        double y = parseRelativeCoordinateLegacy(sender, args[index + 1], baseY);
        double z = parseRelativeCoordinateLegacy(sender, args[index + 2], baseZ);

        // 注意：这里使用 Math.floor 来模拟原版命令对浮点坐标转换为整数方块坐标的行为
        return new BlockPos(
            Math.floor(x),
            Math.floor(y),
            Math.floor(z)
        );
    }

    /**
     * 解析单个相对或绝对坐标值。
     *
     * @param sender 命令发送者 (用于检查~坐标的权限/上下文)
     * @param input  坐标字符串 (如 "10", "~", "~-5")
     * @param base   相对坐标的基准值 (玩家的精确X, Y, 或 Z)
     * @return 解析后的 double 型坐标值
     * @throws CommandException 如果输入无效
     */
    private double parseRelativeCoordinateLegacy(ICommandSender sender, String input, double base) throws CommandException {
        if (input.startsWith("~")) {
            // 相对坐标必须由玩家执行 (理论上客户端命令总是玩家)
            // if (!(sender instanceof EntityPlayer)) {
            //     throw new CommandException("commands.generic.permission"); // 或者其他错误
            // }
            String offsetStr = input.substring(1);
            if (offsetStr.isEmpty()) {
                return base; // "~" 表示基准值本身
            } else {
                try {
                    return base + Double.parseDouble(offsetStr); // "~offset"
                } catch (NumberFormatException e) {
                    // LangUtil.translate("commands.generic.num.invalid", input) // Forge 1.8.9 的键名可能不同
                    throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_coordinate_format", input)); // 自定义错误键
                }
            }
        } else {
            // 绝对坐标
            try {
                return Double.parseDouble(input);
            } catch (NumberFormatException e) {
                // LangUtil.translate("commands.generic.num.invalid", input)
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_coordinate_format", input)); // 自定义错误键
            }
        }
    }
    // ========= 旧版本坐标解析方法结束 =========

    /**
     * 显示 /cgb 命令的帮助信息。
     * @param sender 命令发送者。
     */
    private void displayHelp(ICommandSender sender) {
        EnumChatFormatting hl = EnumChatFormatting.GOLD; // 高亮颜色
        EnumChatFormatting tx = EnumChatFormatting.GRAY;  // 文本颜色
        EnumChatFormatting sc = EnumChatFormatting.AQUA; // 子命令颜色
        EnumChatFormatting us = EnumChatFormatting.YELLOW; // 用法颜色

        // --- 标题 ---
        sender.addChatMessage(new ChatComponentText(hl + LangUtil.translate("ghostblock.commands.cghostblock.help.header")));

        // --- 描述 ---
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.description")));

        // --- 主要用法 ---
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.usage.main") + ": " + us + getCommandUsage(sender))); // 复用 getCommandUsage

        // --- 可用子命令 ---
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommands.header")));
        sender.addChatMessage(formatHelpLine(sc + "help", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.help")));
        sender.addChatMessage(formatHelpLine(sc + "set", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.set") + us + " " + LangUtil.translate("ghostblock.commands.cghostblock.set.usage")));
        sender.addChatMessage(formatHelpLine(sc + "fill", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.fill") + us + " " + LangUtil.translate("ghostblock.commands.cghostblock.fill.usage")));
        sender.addChatMessage(formatHelpLine(sc + "load", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.load") + us + " " + LangUtil.translate("ghostblock.commands.cghostblock.load.usage")));
        sender.addChatMessage(formatHelpLine(sc + "clear", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.clear") + us + " " + LangUtil.translate("ghostblock.commands.clear.usage")));
        sender.addChatMessage(formatHelpLine(sc + "cancel", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.cancel") + us + " " + LangUtil.translate("ghostblock.commands.cancel.usage")));
        sender.addChatMessage(formatHelpLine(sc + "resume", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.resume") + us + " " + LangUtil.translate("ghostblock.commands.resume.usage")));
        sender.addChatMessage(formatHelpLine(sc + "undo", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.undo") + us + " " + LangUtil.translate("ghostblock.commands.undo.usage")));

        // --- 别名 ---
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.aliases") + ": " + hl + String.join(", ", getCommandAliases())));

        // --- 页脚 ---
        // sender.addChatMessage(new ChatComponentText(hl + "------------------------------------")); // 可选
    }

    /**
     * 格式化帮助文本中的一行（子命令 + 描述）。
     * @param command 子命令部分 (带颜色)。
     * @param description 描述部分 (带颜色)。
     * @return 格式化后的 IChatComponent。
     */
    private IChatComponent formatHelpLine(String command, String description) {
        // 直接创建分离的组件以确保颜色正确
        ChatComponentText line = new ChatComponentText("  ");
        ChatComponentText cmdComp = new ChatComponentText(command); // 假设 command 字符串已包含颜色代码
        ChatComponentText descComp = new ChatComponentText(" - " + description); // 假设 description 字符串已包含颜色代码

        line.appendSibling(cmdComp);
        line.appendSibling(descComp);
        return line;
    }

    // 获取当前世界/维度的自动清除文件名
    private static String getAutoClearFileName(WorldClient world) {
        return "clear_" + GhostBlockData.getWorldIdentifier(world);
    }

    // 处理撤销命令
    private void handleUndoCommand(ICommandSender sender, WorldClient world) throws CommandException {
        if (undoHistory.isEmpty()) {
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.undo.empty"));
        }
        UndoRecord record = undoHistory.pop(); // 取出最新的撤销记录

        // 1. 恢复用户文件备份（如果存在）
        if (record.fileBackups != null && !record.fileBackups.isEmpty()) {
             // 使用 formatMessage 发送带翻译的消息
            sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_user_files"));
            for (Map.Entry<String, List<GhostBlockData.GhostBlockEntry>> entry : record.fileBackups.entrySet()) {
                String fileName = entry.getKey(); // 这是基础文件名
                List<GhostBlockData.GhostBlockEntry> backupEntries = entry.getValue();
                // 使用覆盖模式 (true) 将备份内容写回文件
                GhostBlockData.saveData(world, backupEntries, fileName, true);
                 // 使用 formatMessage 发送带翻译的消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.user_file_restored", fileName));
            }
        }


        // 2. 处理核心撤销逻辑（基于操作类型）
        int restoredCount = 0;
         // 撤销文件可能不存在（例如，如果撤销的是文件删除），先检查
        File undoDataFile = GhostBlockData.getDataFile(world, record.undoFileName);
        List<GhostBlockData.GhostBlockEntry> entriesFromUndoFile = new ArrayList<>();
         if (undoDataFile.exists()) {
             entriesFromUndoFile = GhostBlockData.loadData(world, Collections.singletonList(record.undoFileName)); // 加载撤销文件数据
         }


        switch (record.operationType) {
            case SET: // 撤销 set/fill/load 操作
                if (entriesFromUndoFile.isEmpty()) {
                      // 使用 formatMessage 发送带翻译的消息
                     sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.error.data_file_empty"));
                     break;
                }
                 // 使用 formatMessage 发送带翻译的消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_blocks"));
                List<BlockPos> affectedPositionsForSet = new ArrayList<>();
                for (GhostBlockData.GhostBlockEntry entry : entriesFromUndoFile) {
                    BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                    Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
                    if (originalBlock != null) { // 允许恢复空气
                        try {
                            world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                            world.markBlockForUpdate(pos); // 确保客户端看到变化
                            restoredCount++;
                            affectedPositionsForSet.add(pos); // 添加位置以便从自动清除文件中移除
                        } catch (Exception e) { // 捕获更通用的异常
                             System.err.println("[GhostBlock Undo Error] Restoring SET failed at " + pos + ": " + e.getMessage());
                              // 使用 formatMessage 发送带翻译的消息
                             sender.addChatMessage(formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error.restore_failed", pos.getX(), pos.getY(), pos.getZ(), e.getMessage()));
                        }
                    } else {
                          // 使用 formatMessage 发送带翻译的消息
                         sender.addChatMessage(formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error_block_lookup", entry.originalBlockId, pos.getX(), pos.getY(), pos.getZ()));
                    }
                }
                // 从自动清除文件中移除这些位置
                removeEntriesFromAutoClearFile(world, affectedPositionsForSet);
                 // 使用 formatMessage 发送带翻译的消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                        "ghostblock.commands.undo.success_set", restoredCount));
                break;

            case CLEAR_BLOCK: // 撤销 clear block 或 clear file 操作
                // 如果是撤销文件删除，我们已经在步骤 1 中恢复了文件，这里不需要做额外操作
                if (record.undoFileName.startsWith("undo_clear_file_")) {
                     // fileBackups 非空才说明有文件被恢复了
                     if (record.fileBackups != null && !record.fileBackups.isEmpty()) {
                           // 使用 formatMessage 发送带翻译的消息
                          sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.undo.success_clear_file"));
                     } else {
                           // 使用 formatMessage 发送带翻译的消息
                          sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.warning.no_files_to_restore"));
                     }
                } else { // 撤销 clear block 操作
                    if (entriesFromUndoFile.isEmpty()) {
                         // 使用 formatMessage 发送带翻译的消息
                        sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.error.data_file_empty_ghost"));
                        break;
                    }
                     // 使用 formatMessage 发送带翻译的消息
                    sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.undo.restoring_ghost_blocks"));
                    List<GhostBlockData.GhostBlockEntry> restoredGhostEntriesForAutoClear = new ArrayList<>(); // 用于重新填充自动清除文件
                    for (GhostBlockData.GhostBlockEntry entry : entriesFromUndoFile) { // entriesFromUndoFile 包含 幽灵->原始 的映射
                        BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                        Block ghostBlock = Block.getBlockFromName(entry.blockId); // 恢复幽灵方块
                        if (ghostBlock != null && ghostBlock != Blocks.air) {
                             try {
                                 // 使用 setGhostBlock 恢复
                                 setGhostBlock(world, pos, new BlockStateProxy(Block.getIdFromBlock(ghostBlock), entry.metadata));
                                 restoredCount++;
                                 // 将这个 幽灵->原始 映射添加到列表中，以便重新填充自动清除文件
                                 restoredGhostEntriesForAutoClear.add(entry);
                             } catch (Exception e) { // 捕获更通用的异常
                                 System.err.println("[GhostBlock Undo Error] Restoring CLEAR_BLOCK failed at " + pos + ": " + e.getMessage());
                                   // 使用 formatMessage 发送带翻译的消息
                                 sender.addChatMessage(formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error.restore_ghost_failed", pos.getX(), pos.getY(), pos.getZ(), e.getMessage()));
                             }
                        } else {
                              // 使用 formatMessage 发送带翻译的消息
                             sender.addChatMessage(formatMessage(EnumChatFormatting.RED,"ghostblock.commands.undo.error_block_lookup", entry.blockId, pos.getX(), pos.getY(), pos.getZ()));
                        }
                    }
                     // 重新填充自动清除文件（合并模式 false，添加回去）
                    if (!restoredGhostEntriesForAutoClear.isEmpty()) {
                        String autoFileName = getAutoClearFileName(world);
                        GhostBlockData.saveData(world, restoredGhostEntriesForAutoClear, autoFileName, false); // 使用合并模式添加回去
                          // 使用 formatMessage 发送带翻译的消息
                         sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.auto_file_restored", restoredGhostEntriesForAutoClear.size()));
                    }
                     // 使用 formatMessage 发送带翻译的消息
                    sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                            "ghostblock.commands.undo.success_clear", restoredCount));
                }
                break;
        }

        // 3. 删除撤销数据文件本身（如果它存在）
        if (undoDataFile.exists()) {
            if (!undoDataFile.delete()) {
                 System.err.println("[GhostBlock] 删除撤销文件失败: " + undoDataFile.getPath());
                 // 可以选择性地通知用户
                 // sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "ghostblock.commands.undo.error.delete_undo_file", record.undoFileName));
            } else {
                 System.out.println("[GhostBlock] 已删除撤销文件: " + undoDataFile.getPath());
            }
        } else {
            System.out.println("[GhostBlock] 撤销文件未找到或无需删除: " + record.undoFileName);
        }
    }


    // 从指定文件中移除指定位置的条目
    public static void removeEntriesFromFile(World world, String fileName, List<BlockPos> positionsToRemove) {
        File file = GhostBlockData.getDataFile(world, fileName); // 获取文件对象
        if (!file.exists()) {
            return;
        }
        // 加载现有条目
        List<GhostBlockData.GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(fileName)); // 使用基础文件名加载
        if (existingEntries.isEmpty()) {
            return; // 文件是空的，无需操作
        }
        // 创建位置坐标字符串集合用于快速查找
        Set<String> removalKeys = positionsToRemove.stream()
            .map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
            .collect(Collectors.toSet());

        // 过滤出需要保留的条目
        List<GhostBlockData.GhostBlockEntry> newEntries = existingEntries.stream()
            .filter(entry -> !removalKeys.contains(entry.x + "," + entry.y + "," + entry.z))
            .collect(Collectors.toList());

        // 使用 saveData 保存过滤后的结果（覆盖模式），saveData 会处理空列表删除文件的情况
        GhostBlockData.saveData(world, newEntries, fileName, true); // 使用覆盖模式
    }

    // 从自动清除文件中移除指定位置的条目
    private void removeEntriesFromAutoClearFile(World world, List<BlockPos> positionsToRemove) {
        if (positionsToRemove.isEmpty()) {
            return;
        }
        String autoFileName = getAutoClearFileName((WorldClient) world);
        removeEntriesFromFile(world, autoFileName, positionsToRemove); // 复用通用方法
        System.out.println("[GhostBlock] 撤销操作后更新了自动清除文件 (" + autoFileName + ")，移除了 " + positionsToRemove.size() + " 个条目。");
    }

    // 辅助方法：恢复方块列表到原始状态（似乎未被直接使用，但保留可能有价值）
    private int restoreBlocks(WorldClient world, List<GhostBlockData.GhostBlockEntry> entries) {
        AtomicInteger count = new AtomicInteger();
        entries.forEach(entry -> {
            BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
            Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
            if (originalBlock != null) { // 允许恢复空气
                try {
                    world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                    world.markBlockForUpdate(pos);
                    count.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("[GhostBlock restoreBlocks Error] Failed at " + pos + ": " + e.getMessage());
                }
            }
        });
        return count.get();
    }

    // 处理恢复任务命令
    private void handleResumeCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
             // 使用 LangUtil 获取 resume 命令的用法
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.resume.usage"));
        }

        int taskId;
        try {
            taskId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_id", args[1]));
        }

        TaskSnapshot snapshot = pausedTasks.get(taskId);
        if (snapshot == null) {
            // 检查任务是否仍在运行 (可能用户输错了或者任务已完成)
            boolean isRunning = false;
             synchronized(activeTasks) { isRunning = activeTasks.stream().anyMatch(t -> t.getTaskId() == taskId); }
             if (!isRunning) { synchronized(activeLoadTasks) { isRunning = activeLoadTasks.stream().anyMatch(t -> t.getTaskId() == taskId); } }
             // ClearTask 不能恢复
             if (isRunning) {
                  // 使用 LangUtil 获取错误信息
                 throw new CommandException(LangUtil.translate("ghostblock.commands.resume.error.already_running", taskId));
             } else {
                 // 使用 LangUtil 获取错误信息
                throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_id", taskId));
             }
        }

        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) {
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.not_in_world"));
        }

        // 根据快照类型恢复任务
        if ("fill".equals(snapshot.type)) {
            FillTask newTask = new FillTask(
                world,
                snapshot.state,
                snapshot.remainingBlocks, // 传递剩余列表
                snapshot.total,          // 总数
                snapshot.batchSize,
                snapshot.saveToFile,
                snapshot.saveFileName,
                snapshot.sender, // 原始发送者
                snapshot.taskId,
                snapshot.entriesToSaveForUserFile // 传递用户保存条目
            );
             // 将新任务添加到活动列表并从暂停列表移除
             synchronized(activeTasks) { activeTasks.add(newTask); }
             pausedTasks.remove(taskId);
              // 使用 formatMessage 发送带翻译的消息
             sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                "ghostblock.commands.resume.success", taskId));
        } else if ("load".equals(snapshot.type)) {
            LoadTask newTask = new LoadTask(
                world,
                snapshot.remainingEntries, // 传递剩余条目列表
                snapshot.batchSize,
                snapshot.sender, // 原始发送者
                snapshot.taskId
            );
             // 将新任务添加到活动列表并从暂停列表移除
             synchronized(activeLoadTasks) { activeLoadTasks.add(newTask); }
             pausedTasks.remove(taskId);
              // 使用 formatMessage 发送带翻译的消息
             sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                "ghostblock.commands.resume.success", taskId));
        } else {
            // 不支持恢复其他类型或未知类型
            pausedTasks.remove(taskId); // 移除无效的快照
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.resume.invalid_type"));
        }
    }

    // 内部类：任务快照，用于暂停和恢复
    private static class TaskSnapshot {
        public final String type; // "fill" 或 "load"
        public final List<BlockPos> remainingBlocks; // fill 任务剩余方块
        public final List<GhostBlockData.GhostBlockEntry> remainingEntries; // load 任务剩余条目
        public final int batchSize;
        public final int total; // 任务总数
        public final BlockStateProxy state; // fill 任务的方块状态
        public final boolean saveToFile; // fill 任务是否保存
        public final String saveFileName; // fill 任务保存文件名
        public final ICommandSender sender; // 任务发起者
        public final int taskId;
        public final List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile; // fill 任务为 -s 准备的条目

        // FillTask 的构造函数
        public TaskSnapshot(FillTask task) {
            this.type = "fill";
            this.remainingBlocks = new ArrayList<>(task.remainingBlocks); // 需要复制剩余列表
            this.batchSize = task.batchSize;
            this.total = task.totalBlocks;
            this.state = task.state;
            this.saveToFile = task.saveToFile;
            this.saveFileName = task.saveFileName;
            this.sender = task.sender;
            this.taskId = task.taskId;
            this.remainingEntries = null;
            // 使用防御性拷贝保存用户文件条目列表
            this.entriesToSaveForUserFile = task.entriesToSaveForUserFile != null ? new ArrayList<>(task.entriesToSaveForUserFile) : new ArrayList<>(); // <--- 保存列表
        }

        // LoadTask 的构造函数
        public TaskSnapshot(LoadTask task) {
            this.type = "load";
            // 保存剩余的条目列表 (从当前索引到末尾)
            this.remainingEntries = task.entries != null && task.currentIndex < task.entries.size()
                                    ? new ArrayList<>(task.entries.subList(task.currentIndex, task.entries.size()))
                                    : new ArrayList<>();
            this.batchSize = task.batchSize;
            this.total = task.entries != null ? task.entries.size() : 0; // 总数是原始列表大小
            this.state = null;
            this.saveToFile = false;
            this.saveFileName = null;
            this.sender = task.sender;
            this.taskId = task.taskId;
            this.remainingBlocks = null;
            this.entriesToSaveForUserFile = null; // <--- 初始化为 null
        }
    }

    // 收集指定位置的原始方块信息，用于自动保存和撤销
    // state 参数用于知道即将设置的幽灵方块是什么
    private List<GhostBlockData.GhostBlockEntry> collectOriginalBlocks(WorldClient world, List<BlockPos> blocks, BlockStateProxy state) {
        List<GhostBlockData.GhostBlockEntry> entries = new ArrayList<>();
        Block ghostBlock = (state != null) ? Block.getBlockById(state.blockId) : null; // 即将设置的幽灵方块
        String ghostBlockId = (ghostBlock != null) ? ghostBlock.getRegistryName().toString() : "minecraft:air"; // 默认空气？
        int ghostMeta = (state != null) ? state.metadata : 0;

        // 加载自动保存文件中的已有数据，避免重复记录
        String autoFileName = getAutoClearFileName(world);
        List<GhostBlockData.GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
        Set<String> existingKeys = existingEntries.stream()
            .map(e -> e.x + "," + e.y + "," + e.z)
            .collect(Collectors.toSet());

        // 只收集未记录在自动文件中的坐标的原始方块信息
        for (BlockPos pos : blocks) {
            String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (existingKeys.contains(key)) {
                continue; // 如果已记录，跳过
            }
            IBlockState originalState = world.getBlockState(pos);
            Block originalBlock = originalState.getBlock();
            // 记录原始方块信息，以及 *即将* 设置的幽灵方块信息
            entries.add(new GhostBlockData.GhostBlockEntry(
                pos,
                ghostBlockId, // 即将设置的幽灵方块ID
                ghostMeta,    // 即将设置的幽灵方块元数据
                originalBlock.getRegistryName().toString(), // 当前的原始方块ID
                originalBlock.getMetaFromState(originalState) // 当前的原始方块元数据
            ));
        }
        return entries;
    }

    // 同步清除所有幽灵方块（恢复为原始方块）
    private void clearAllGhostBlocksSync(ICommandSender sender, WorldClient world, List<GhostBlockData.GhostBlockEntry> entries) {
        int restored = 0;
        int failed = 0;

        for (GhostBlockData.GhostBlockEntry entry : entries) {
            try {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block originalBlock = Block.getBlockFromName(entry.originalBlockId);

                // 允许 originalBlock 为 null (表示无效ID) 或 air，尝试恢复
                if (originalBlock == null) {
                     System.err.println("[GhostBlock 同步清除错误] 无法找到原始方块: " + entry.originalBlockId + " at " + pos);
                     failed++;
                     continue; // 跳过这个方块
                }

                // 恢复原始方块
                world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                world.markBlockForUpdate(pos);
                restored++;
            } catch (Exception e) {
                failed++;
                System.err.println("[GhostBlock 同步清除错误] 恢复方块时发生异常 " + entry.x + "," + entry.y + "," + entry.z + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 发送结果 (使用 formatMessage)
        sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
            "ghostblock.commands.clear.block.success", restored));

        if (failed > 0) {
             // 使用 formatMessage 发送带翻译的消息
            sender.addChatMessage(formatMessage(EnumChatFormatting.RED,
        "ghostblock.commands.clear.block.partial_fail", restored, failed));
        }

        // 删除自动保存文件 的逻辑移到调用此方法的地方 (handleClearBlockCommand)
    }

    // 辅助方法：获取待确认操作的文件列表
    private List<File> getPendingConfirmation(String senderName) {
        ClearConfirmation confirmation = pendingConfirmations.get(senderName);
        if (confirmation == null) {
            return null;
        }
        // 检查是否超时
        if (System.currentTimeMillis() - confirmation.timestamp > CONFIRMATION_TIMEOUT) {
            pendingConfirmations.remove(senderName); // 超时则移除
            return null;
        }
        return confirmation.targetFiles; // 返回不可变的文件列表
    }

    // 处理取消任务命令
    private void handleCancelCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
             // 使用 LangUtil 获取 cancel 命令的用法
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cancel.usage"));
        }

        List<Integer> successIds = new ArrayList<>();
        List<String> invalidIds = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String taskIdStr = args[i];
            try {
                int taskId = Integer.parseInt(taskIdStr);
                boolean cancelled = cancelTask(taskId); // 尝试取消任务
                if (cancelled) {
                    successIds.add(taskId);
                } else {
                     // 如果 cancelTask 返回 false，检查是否在暂停列表中
                     if (pausedTasks.containsKey(taskId)) {
                          // 如果在暂停列表中，也认为取消成功，并将其移除
                          pausedTasks.remove(taskId);
                          successIds.add(taskId);
                     } else {
                        // 否则，是无效 ID
                        invalidIds.add(taskIdStr);
                     }
                }
            } catch (NumberFormatException e) {
                invalidIds.add(taskIdStr); // 不是数字，无效 ID
            }
        }

        // 发送结果反馈
        if (!successIds.isEmpty()) {
             // 使用 formatMessage 发送带翻译的消息
            String successMsg = LangUtil.translate("ghostblock.commands.cancel.success.multi",
                successIds.size(), formatIdList(successIds));
            sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN, successMsg));
        }

        if (!invalidIds.isEmpty()) {
             // 使用 formatMessage 发送带翻译的消息
            String errorMsg = LangUtil.translate("ghostblock.commands.cancel.invalid_ids",
                formatIdList(invalidIds));
            // 不抛出异常，只发送错误消息
            sender.addChatMessage(formatMessage(EnumChatFormatting.RED, errorMsg));
        }
    }

    // 格式化 ID 列表为逗号分隔字符串
    private String formatIdList(Collection<?> ids) {
        return String.join(", ", ids.stream().map(Object::toString).collect(Collectors.toList()));
    }

    // 尝试取消指定 ID 的任务
    private boolean cancelTask(int taskId) {
        boolean found = false;

        // 取消 FillTask
        synchronized (activeTasks) {
            Iterator<FillTask> fillIter = activeTasks.iterator();
            while (fillIter.hasNext()) {
                FillTask task = fillIter.next();
                if (task.getTaskId() == taskId) {
                    task.cancel(); // 标记为取消
                    TaskSnapshot snapshot = new TaskSnapshot(task); // 创建快照
                    fillIter.remove(); // 从活动列表移除
                    pausedTasks.put(taskId, snapshot); // 添加到暂停列表
                    found = true;
                    System.out.println("[GhostBlock] 填充任务 #" + taskId + " 已取消并暂停。");
                    break;
                }
            }
        }

        // 取消 LoadTask
        if (!found) {
            synchronized (activeLoadTasks) {
                Iterator<LoadTask> loadIter = activeLoadTasks.iterator();
                while (loadIter.hasNext()) {
                    LoadTask task = loadIter.next();
                    if (task.getTaskId() == taskId) {
                        task.cancel(); // 标记为取消
                        TaskSnapshot snapshot = new TaskSnapshot(task); // 创建快照
                        loadIter.remove(); // 从活动列表移除
                        pausedTasks.put(taskId, snapshot); // 添加到暂停列表
                        found = true;
                        System.out.println("[GhostBlock] 加载任务 #" + taskId + " 已取消并暂停。");
                        break;
                    }
                }
            }
        }

        // 取消 ClearTask (无快照，无法恢复)
        if (!found) {
            synchronized (activeClearTasks) {
                Iterator<ClearTask> clearIter = activeClearTasks.iterator();
                while (clearIter.hasNext()) {
                    ClearTask task = clearIter.next();
                    if (task.getTaskId() == taskId) {
                        task.cancel(); // 标记为取消
                        clearIter.remove(); // 直接从活动列表移除
                        found = true;
                        System.out.println("[GhostBlock] 清除任务 #" + taskId + " 已取消。");
                        break;
                    }
                }
            }
        }

        return found; // 返回是否找到了并处理了该任务
    }

    // 处理 clear 命令
    private void handleClearCommand(ICommandSender sender, World world, String[] args) throws CommandException {
         // 使用 LangUtil 获取 clear 命令的用法
        if (args.length < 2) { // clear <block|file> ...
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage"));
        }
        String clearType = args[1].toLowerCase();
        if ("file".equals(clearType)) {
            handleClearFileCommand(sender, world, args); // 处理 clear file
        } else if ("block".equals(clearType)) {
            handleClearBlockCommand(sender, world, args); // 处理 clear block
        } else {
            // 无效的清除类型
             // 使用 LangUtil 获取 clear 命令的用法
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage"));
        }
    }

    // 处理 clear file 命令
    private void handleClearFileCommand(ICommandSender sender, World world, String[] args) throws CommandException {
         // 使用 LangUtil 获取 clear 命令的用法 (或更具体的错误)
        if (args.length < 3) { // clear file <filename...>
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage.file_missing_args"));
        }

        List<String> fileNames = Arrays.asList(args).subList(2, args.length); // 获取所有文件名参数
        List<File> targetFiles = new ArrayList<>(); // 存储实际存在的文件对象
        List<String> missingFiles = new ArrayList<>(); // 存储不存在的文件名
        List<String> validFileNamesForMessage = new ArrayList<>(); // 存储有效的基础文件名（用于确认消息）

        for (String fileName : fileNames) {
             // 自动处理 .json 后缀，获取基础文件名
             String baseFileName = fileName.toLowerCase().endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
             // 忽略内部文件
             if (baseFileName.startsWith("clear_") || baseFileName.startsWith("undo_")) {
                  // 使用 formatMessage 发送带翻译的消息
                 sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.ignored_internal_file", fileName));
                 continue;
             }

            File file = GhostBlockData.getDataFile(world, baseFileName); // 使用基础文件名获取文件
            if (file.exists()) {
                targetFiles.add(file); // 添加文件对象
                validFileNamesForMessage.add(baseFileName); // 添加基础文件名
            } else {
                missingFiles.add(fileName); // 添加用户输入的原始名称
            }
        }

        // 如果所有指定的文件都不存在或被忽略
        if (targetFiles.isEmpty()) {
             if (!missingFiles.isEmpty()) {
                 // 使用 LangUtil 获取错误信息
                throw new CommandException(LangUtil.translate("ghostblock.commands.clear.missing_files",
                    String.join(", ", missingFiles)));
             } else {
                // 都被忽略了
                 // 使用 LangUtil 获取错误信息
                throw new CommandException(LangUtil.translate("ghostblock.commands.clear.error.no_valid_files_to_delete"));
             }
        }

        // 如果部分文件不存在，发出警告并继续处理存在的文件
        if (!missingFiles.isEmpty()) {
             // 使用 formatMessage 发送带翻译的消息
             sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, LangUtil.translate("ghostblock.commands.clear.missing_files",
                String.join(", ", missingFiles))));
        }

        // --- 生成确认消息 ---
        // 构建确认命令，包含所有有效的基础文件名
        String confirmCommand = "/cgb confirm_clear " + String.join(" ", validFileNamesForMessage);

        IChatComponent message = new ChatComponentText("")
        .appendSibling(new ChatComponentText(EnumChatFormatting.GOLD +
            // 使用 LangUtil 获取翻译
            LangUtil.translate("ghostblock.commands.clear.confirm.question") + "\n"))
        // 在确认消息中显示基础文件名
        .appendSibling(new ChatComponentText(EnumChatFormatting.WHITE + String.join(", ", validFileNamesForMessage) + "\n"))
        .appendSibling(
            new ChatComponentText(LangUtil.translate("ghostblock.commands.clear.confirm.button"))
                .setChatStyle(new ChatStyle()
                    .setColor(EnumChatFormatting.RED)
                    .setBold(true)
                    .setChatClickEvent(new ClickEvent(
                        Action.RUN_COMMAND, confirmCommand
                    ))
                ));

        sender.addChatMessage(message);
        // 存储待确认状态，使用实际的文件对象列表
        pendingConfirmations.put(sender.getName(),
            new ClearConfirmation(targetFiles, System.currentTimeMillis()));
    }

    // 内部类：批量清除任务
    private static class ClearTask {
        private final WorldClient world;
        private final List<GhostBlockData.GhostBlockEntry> entries; // 要清除的条目 (幽灵 -> 原始 映射)
        private int currentIndex;
        private final int batchSize;
        private final ICommandSender sender;
        private long lastUpdateTime = 0;
        private float lastReportedPercent = -1;
        private volatile boolean cancelled = false; // 取消标志
        private final int taskId;
        private final File autoClearFile; // 需要删除的自动清除文件

        public ClearTask(WorldClient world, List<GhostBlockData.GhostBlockEntry> entries,
                        int batchSize, ICommandSender sender, int taskId,
                        File autoClearFile) {
            this.world = world;
            this.entries = entries; // 要恢复的方块列表
            this.batchSize = batchSize;
            this.sender = sender;
            this.currentIndex = 0;
            this.taskId = taskId;
            this.autoClearFile = autoClearFile; // 任务完成后要删除的文件
        }

        // 处理一个批次
        boolean processBatch() {
            if (cancelled) {
                return true; // 任务结束（已取消）
            }

            int endIndex = Math.min(currentIndex + batchSize, entries.size());
            int processedInBatch = 0;

            for (int i = currentIndex; i < endIndex; i++) {
                if (cancelled) {
                    break; // 在循环内部检查取消状态
                }

                GhostBlockData.GhostBlockEntry entry = entries.get(i);
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
                if (originalBlock != null) { // 允许恢复空气
                    try {
                        world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                        world.markBlockForUpdate(pos);
                        processedInBatch++;
                    } catch (Exception e) {
                         System.err.println("[GhostBlock 清除任务错误] 恢复方块失败 " + pos + ": " + e.getMessage());
                    }
                } else {
                     System.err.println("[GhostBlock 清除任务错误] 无法找到原始方块: " + entry.originalBlockId + " at " + pos);
                }
            }
            currentIndex = endIndex;

            if (cancelled) {
                return true; // 如果在批处理中取消了，直接返回
            }

            boolean finished = currentIndex >= entries.size();

            if (finished) {
                sendFinalProgress();
            } else {
                float currentPercent = (currentIndex * 100.0f) / entries.size();
                sendProgressIfNeeded(currentPercent, false);
            }

            return finished; // 返回任务是否完成
        }

        // 根据需要发送进度消息
        private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
            currentPercent = Math.round(currentPercent * 10) / 10.0f; // 保留一位小数
            boolean shouldSend = forceSend ||
                Math.abs(currentPercent - lastReportedPercent) >= 0.1f || // 变化足够大时发送
                System.currentTimeMillis() - lastUpdateTime > 1000; // 或者距离上次发送超过1秒

            if (shouldSend) {
                String progressBar = createProgressBar(currentPercent, 10); // 创建10段进度条
                IChatComponent message = createProgressMessage(
                    "ghostblock.commands.clear.progress", // 使用清除进度翻译键
                    (int) currentPercent, // 传递整数百分比
                    progressBar
                );
                sender.addChatMessage(message); // 发送消息

                lastReportedPercent = currentPercent; // 更新上次报告的百分比
                lastUpdateTime = System.currentTimeMillis(); // 更新上次发送时间
            }
        }

        // 发送最终进度并执行清理
        private void sendFinalProgress() {
            sendProgressIfNeeded(100.0f, true); // 强制发送100%进度
            // 发送完成消息，仅在未取消时
             if (!cancelled) {
                  // 使用 formatMessage 发送带翻译的消息
                 sender.addChatMessage(formatMessage(FINISH_COLOR, "ghostblock.commands.clear.finish", entries.size()));
             }

            // 删除自动清除文件
            if (autoClearFile != null && autoClearFile.exists()) {
                boolean deleted = autoClearFile.delete();
                System.out.println("[GhostBlock] 自动清理文件 " + autoClearFile.getName() +
                                 " 删除结果: " + deleted);
                 if (!deleted && !cancelled) { // 如果删除失败且任务未被取消
                       // 使用 formatMessage 发送带翻译的消息
                      sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "ghostblock.commands.clear.block.delete_failed", autoClearFile.getName()));
                 }
            } else if (autoClearFile != null) {
                 System.out.println("[GhostBlock] 自动清理文件 " + autoClearFile.getName() + " 在清除任务完成时已不存在。");
            }
        }

        // 标记任务为取消
        public void cancel() {
            this.cancelled = true;
        }

        // 获取任务 ID
        public int getTaskId() {
            return taskId;
        }
    }

    // 创建进度条字符串
    private static String createProgressBar(float progressPercent, int length) {
        int progress = (int) (progressPercent / 100 * length);
        progress = Math.min(progress, length); // 确保不超过总长度
        progress = Math.max(0, progress);      // 确保不小于0
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GREEN); // 进度条完成部分的颜色
        for (int i = 0; i < progress; i++) {
            sb.append("="); // 完成部分的字符
        }
        if (progress < length) {
            sb.append(EnumChatFormatting.GOLD).append(">"); // 指示当前进度的字符和颜色
            sb.append(EnumChatFormatting.GRAY); // 未完成部分的颜色
            for (int i = progress + 1; i < length; i++) {
                sb.append("-"); // 未完成部分的字符
            }
        }
        return sb.toString();
    }

    // --- Tab 补全逻辑 ---

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        String prefix = args[args.length - 1].toLowerCase(); // 当前输入的参数前缀

        // 补全第一个参数 (子命令)
        if (args.length == 1) {
            // --- 修改这里 ---
            return CommandBase.getListOfStringsMatchingLastWord(args,
                "help", "set", "fill", "load", "clear", "cancel", "resume", "undo");
            // --- 修改结束 ---
        }

        String subCommand = args[0].toLowerCase(); // 获取子命令

        // 如果第一个参数是 help，则不提供后续补全
        if (subCommand.equals("help")) {
            return Collections.emptyList();
        }

        // 根据子命令进行后续参数补全
        switch (subCommand) {
            case "set":
                return handleSetTabCompletion(sender, args, pos); // 使用 pos
            case "fill":
                return handleFillTabCompletion(sender, args, pos); // 使用 pos
            case "load":
                return handleLoadTabCompletion(sender, args);
            case "clear":
                return handleClearTabCompletion(sender, args);
            case "cancel":
                // 补全正在运行或已暂停的任务ID
                List<String> taskIds = new ArrayList<>();
                 synchronized(activeTasks) { activeTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
                 synchronized(activeLoadTasks) { activeLoadTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
                 synchronized(activeClearTasks) { activeClearTasks.forEach(t -> taskIds.add(String.valueOf(t.getTaskId()))); }
                 taskIds.addAll(pausedTasks.keySet().stream().map(String::valueOf).collect(Collectors.toList()));
                 // 只补全尚未输入的 Task ID
                 List<String> existingTaskIds = Arrays.asList(args).subList(1, args.length - 1);
                 taskIds.removeIf(existingTaskIds::contains);
                return CommandBase.getListOfStringsMatchingLastWord(args, taskIds);
            case "resume":
                // 只补全已暂停的任务ID
                 List<String> pausedIds = pausedTasks.keySet().stream().map(String::valueOf).collect(Collectors.toList());
                return CommandBase.getListOfStringsMatchingLastWord(args, pausedIds);
            case "undo":
            case "confirm_clear": // 这两个命令通常没有后续参数需要补全
                return Collections.emptyList();
            default: // 未知子命令或 help
                return Collections.emptyList();
        }
    }


    // 处理 clear block 的 Tab 补全
    private List<String> handleClearBlockTabCompletion(String[] args) {
        List<String> suggestions = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(); // 当前输入的前缀
        String lastFullArg = (args.length > 2) ? args[args.length - 2].toLowerCase() : ""; // 上一个完整参数

        boolean hasBatchFlag = hasFlag(args, "-b", "--batch");
        boolean hasConfirmFlag = hasFlag(args, "confirm");

        // 检查是否在 -b 后面输入
        if (lastFullArg.equals("-b") || lastFullArg.equals("--batch")) {
             // 如果后面还没输入数字，建议数字
             if (!isNumber(prefix)) {
                 suggestions.addAll(Arrays.asList("100", "500", "1000"));
             }
             // 即使输入了数字，或者刚输入完 -b，都可以建议 confirm
             if (!hasConfirmFlag) {
                  suggestions.add("confirm");
             }
        } else {
            // 不在 -b 后面输入
            // 如果还没有 -b，建议 -b
             if (!hasBatchFlag) {
                 suggestions.add("-b");
                 // suggestions.add("--batch"); // 通常补全一个即可
             }
             // 如果还没有 confirm，建议 confirm
             if (!hasConfirmFlag) {
                 suggestions.add("confirm");
             }
        }

        return CommandBase.getListOfStringsMatchingLastWord(args, suggestions); // 使用基类过滤
    }

    // 处理 set 命令的 Tab 补全
    private List<String> handleSetTabCompletion(ICommandSender sender, String[] args, BlockPos targetPos) { // 添加 targetPos
        int currentArgIndex = args.length - 1; // 当前正在输入的参数索引

        // 1. 坐标补全 (索引 1, 2, 3)
        if (currentArgIndex >= 1 && currentArgIndex <= 3) {
             return CommandBase.getListOfStringsMatchingLastWord(args, getCoordinateSuggestions(sender, currentArgIndex - 1, targetPos)); // 使用改进的坐标建议
        }
        // 2. 方块名称补全 (索引 4)
        else if (currentArgIndex == 4) {
            return CommandBase.getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
        }
        // 3. 可选标志 -s (索引 5)
        else if (currentArgIndex == 5) {
            if (!hasFlag(args, "-s", "--save")) {
                return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("-s", "--save"));
            }
        }
        // 4. 文件名补全 (索引 6, 在 -s 之后)
        else if (currentArgIndex == 6) {
             String prevArg = args[currentArgIndex - 1];
             if (prevArg.equalsIgnoreCase("-s") || prevArg.equalsIgnoreCase("--save")) {
                 List<String> suggestions = new ArrayList<>(getAvailableFileNames());
                 suggestions.add(0, "filename"); // 将占位符放在最前面
                 return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
             }
        }
        return Collections.emptyList();
    }


    // 处理 fill 命令的 Tab 补全
    private List<String> handleFillTabCompletion(ICommandSender sender, String[] args, BlockPos targetPos) { // 添加 targetPos
        int currentArgIndex = args.length - 1; // 正在输入的参数索引

        // 1. 坐标补全 (索引 1-3 for pos1, 4-6 for pos2)
        if (currentArgIndex >= 1 && currentArgIndex <= 3) { // pos1 坐标
             return CommandBase.getListOfStringsMatchingLastWord(args, getCoordinateSuggestions(sender, currentArgIndex - 1, targetPos));
        } else if (currentArgIndex >= 4 && currentArgIndex <= 6) { // pos2 坐标
             return CommandBase.getListOfStringsMatchingLastWord(args, getCoordinateSuggestions(sender, currentArgIndex - 4, targetPos));
        }
        // 2. 方块名称补全 (索引 7)
        else if (currentArgIndex == 7) {
            return CommandBase.getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
        }
        // 3. 可选标志和参数 (索引 8 及之后)
        else if (currentArgIndex >= 8) {
            String prevArg = args[currentArgIndex - 1].toLowerCase(); // 上一个完整参数
            String prefix = args[currentArgIndex].toLowerCase(); // 当前输入前缀

            // a) 如果上一个是 -s 或 --save，建议文件名
            if (prevArg.equals("-s") || prevArg.equals("--save")) {
                 List<String> suggestions = new ArrayList<>(getAvailableFileNames());
                 suggestions.add(0, "filename"); // 将占位符放在前面
                 return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
            }

            // b) 如果上一个是 -b 或 --batch，建议批次大小 (如果当前输入不是数字)
            if (prevArg.equals("-b") || prevArg.equals("--batch")) {
                 if (!isNumber(prefix)) { // 检查当前输入的是否 *不是* 数字
                     return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("100", "500", "1000"));
                 }
                 // 如果已经是数字了，则向下执行，建议其他标志
            }

            // c) 在其他情况下（包括刚输入完 -b <数字>），建议尚未使用的标志
            List<String> suggestions = new ArrayList<>();
            boolean hasBatch = hasFlag(args, "-b", "--batch"); // 检查 -b 是否已存在（不含当前正在输入的）
            boolean hasSave = hasFlag(args, "-s", "--save");   // 检查 -s 是否已存在（不含当前正在输入的）

            // 如果 -b 尚未存在，则建议
            if (!hasBatch) {
                 suggestions.add("-b");
                 // suggestions.add("--batch"); // 一般建议一个短的就行
            }
            // 如果 -s 尚未存在，则建议
            if (!hasSave) {
                 suggestions.add("-s");
                 // suggestions.add("--save");
            }

            return CommandBase.getListOfStringsMatchingLastWord(args, suggestions); // 使用基类方法进行过滤
        }
        return Collections.emptyList();
    }

    // 处理 clear 命令的 Tab 补全
    private List<String> handleClearTabCompletion(ICommandSender sender, String[] args) {
        int currentArgIndex = args.length - 1;

        // 1. 建议 "file" 或 "block" (索引 1)
        if (currentArgIndex == 1) {
            return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("file", "block"));
        }

        // 2. 根据 "file" 或 "block" 处理后续补全 (索引 2 及之后)
        if (currentArgIndex >= 2) {
            String clearType = args[1].toLowerCase();
            if ("file".equals(clearType)) {
                // 建议可用的文件名 (排除已输入的)
                List<String> allFiles = getAvailableFileNames();
                // 获取已完整输入的参数（不包括当前正在输入的）
                List<String> enteredFiles = Arrays.asList(args).subList(2, args.length -1);
                List<String> availableForCompletion = allFiles.stream()
                        .filter(file -> !containsIgnoreCase(enteredFiles, file)) // 过滤掉已输入的
                        .collect(Collectors.toList());
                return CommandBase.getListOfStringsMatchingLastWord(args, availableForCompletion);
            } else if ("block".equals(clearType)) {
                // 使用专门的方法处理 "clear block" 的选项 (-b, confirm)
                return handleClearBlockTabCompletion(args);
            }
        }
        return Collections.emptyList(); // 其他情况无建议
    }

    // 处理 load 命令的 Tab 补全
    private List<String> handleLoadTabCompletion(ICommandSender sender, String[] args) {
        int currentArgIndex = args.length - 1;
        String prevArg = (currentArgIndex > 0) ? args[currentArgIndex - 1].toLowerCase() : "";
        String prefix = args[currentArgIndex].toLowerCase();

        // 检查是否在 -b 后面输入
        if (prevArg.equals("-b") || prevArg.equals("--batch")) {
            // 如果还没输入数字，建议批次大小
            if (!isNumber(prefix)) {
                return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("100", "500", "1000"));
            }
            // 如果已经输入了数字，则继续建议文件名或其他标志
        }

        // 建议可用的文件名和/或 -b 标志
        List<String> suggestions = new ArrayList<>();

        // 1. 建议文件名:
        List<String> allFiles = getAvailableFileNames();
        List<String> enteredFiles = new ArrayList<>();
        boolean batchValueEntered = false; // 标记 -b 后面的数字是否已输入
        for (int i = 1; i < args.length -1 ; i++) { // 迭代到倒数第二个参数
            String current = args[i];
            String previous = (i>0) ? args[i-1] : "";
            if (!current.startsWith("-")) {
                 if (!( (previous.equalsIgnoreCase("-b") || previous.equalsIgnoreCase("--batch")) && isNumber(current) )) {
                      enteredFiles.add(current);
                 } else {
                     batchValueEntered = true;
                 }
            }
        }
        allFiles.stream()
                .filter(file -> !containsIgnoreCase(enteredFiles, file))
                .forEach(suggestions::add);

        // 2. 建议标志:
        if (!hasFlag(args, "-b", "--batch") && !(prevArg.equals("-b") || prevArg.equals("--batch"))) {
            suggestions.add("-b");
            // suggestions.add("--batch");
        }

        return CommandBase.getListOfStringsMatchingLastWord(args, suggestions); // 使用基类过滤
    }


    // --- 辅助方法 ---

    // 获取所有可用文件名（不含.json扩展名, 不含 clear_, 不含 undo_)
    private List<String> getAvailableFileNames() {
        List<String> files = new ArrayList<>();
        File[] jsonFiles = getFilteredSaveFiles(); // 使用统一过滤方法获取所有 .json 文件

        if (jsonFiles != null) {
            for (File file : jsonFiles) {
                String name = file.getName();
                if (name.toLowerCase().endsWith(".json")) {
                     String baseName = name.substring(0, name.length() - 5); // 移除 .json 后缀
                     // 在移除后缀后检查前缀
                     if (!baseName.isEmpty() && !baseName.toLowerCase().startsWith("clear_") && !baseName.toLowerCase().startsWith("undo_")) {
                        files.add(baseName); // 添加基础文件名
                     }
                }
            }
        }
        Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
        return files;
    }

    // 获取 saves 目录下所有 .json 文件
    private File[] getFilteredSaveFiles() {
        File savesDir = new File(GhostBlockData.SAVES_DIR);
        if (!savesDir.exists() || !savesDir.isDirectory()) {
            return new File[0]; // 目录不存在或不是目录
        }
        return savesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
    }

    // 辅助方法：不区分大小写地检查列表中是否包含某个字符串
    private boolean containsIgnoreCase(List<String> list, String target) {
        if (target == null) {
            return false;
        }
        return list.stream().anyMatch(s -> target.equalsIgnoreCase(s));
    }

    // 辅助方法：检查参数数组中是否包含某个标志 (不区分大小写)
    private boolean hasFlag(String[] args, String... flags) {
        // 检查除最后一个参数（正在输入的）之外的所有参数
        for (int i = 0; i < args.length -1; i++) {
            for (String flag : flags) {
                if (args[i].equalsIgnoreCase(flag)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 辅助方法：检查字符串是否可以解析为整数 (静态)
    private static boolean isNumber(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 改进的坐标建议方法
    private List<String> getCoordinateSuggestions(ICommandSender sender, int coordinateIndex, BlockPos targetPos) {
        List<String> suggestions = new ArrayList<>();
        // 1. 添加目标方块坐标 (如果可用)
        if (targetPos != null) {
             switch (coordinateIndex) {
                 case 0: suggestions.add(String.valueOf(targetPos.getX())); break;
                 case 1: suggestions.add(String.valueOf(targetPos.getY())); break;
                 case 2: suggestions.add(String.valueOf(targetPos.getZ())); break;
             }
        }
        // 2. 添加相对坐标 "~"
        suggestions.add("~");
        // 3. 添加玩家当前整数坐标 (如果与目标不同)
        if (sender instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) sender;
            int playerCoord = 0;
            int targetCoord = Integer.MIN_VALUE; // 确保不相等
            switch (coordinateIndex) {
                case 0: playerCoord = (int) Math.floor(player.posX); if(targetPos!=null) targetCoord = targetPos.getX(); break;
                case 1: playerCoord = (int) Math.floor(player.posY); if(targetPos!=null) targetCoord = targetPos.getY(); break;
                case 2: playerCoord = (int) Math.floor(player.posZ); if(targetPos!=null) targetCoord = targetPos.getZ(); break;
            }
            if (targetPos == null || playerCoord != targetCoord) {
                suggestions.add(String.valueOf(playerCoord));
            }
        } else if (targetPos == null) {
            // 如果是非玩家且没有目标方块，建议 0
             suggestions.add("0");
        }

        // 移除重复项（比如目标方块就是脚下）
        return suggestions.stream().distinct().collect(Collectors.toList());
    }


    // 处理 clear block 命令
    private void handleClearBlockCommand(ICommandSender sender, World world, String[] args) throws CommandException {
        // clear block [-b [size]] [confirm]
        boolean batchMode = false;
        int batchSize = 100;
        boolean confirmed = false;

        // 解析可选参数
        for (int i = 2; i < args.length; ) { // 从第 3 个参数开始检查
             String flag = args[i].toLowerCase();
             if (flag.equals("-b") || flag.equals("--batch")) {
                 batchMode = true;
                 i++; // 移动到可能的批次大小
                 if (i < args.length && isNumber(args[i])) { // 检查是否跟数字
                     try {
                         batchSize = Integer.parseInt(args[i]);
                         validateBatchSize(batchSize);
                         i++; // 消耗数字
                     } catch (NumberFormatException | CommandException e) {
                          // 使用 LangUtil 获取错误信息
                         throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_batch_size"));
                     }
                 }
                 // 如果后面不是数字或没有参数，使用默认大小 100
             } else if (flag.equals("confirm")) {
                 confirmed = true;
                 i++; // 消耗 confirm
             } else {
                 // 未知参数
                  // 使用 LangUtil 获取 clear block 命令的用法
                 throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage.block"));
             }
        }

        // --- 执行逻辑 ---
        WorldClient worldClient = (WorldClient) world;
        String autoFileName = getAutoClearFileName(worldClient);
        List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
        File autoFile = GhostBlockData.getDataFile(world, autoFileName); // 获取文件对象，用于后续删除

        if (entries.isEmpty()) {
             // 使用 formatMessage 发送带翻译的消息
            sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.clear.block.no_blocks"));
            return; // 没有可清除的方块
        }

        // 如果未确认，发送确认请求
        if (!confirmed) {
            sendConfirmationMessage(sender, batchMode, batchSize); // 发送确认消息
        } else {
            // --- 已确认 ---
             // 创建撤销记录 (在清除或启动任务之前)，使用当前的条目作为备份
             List<GhostBlockData.GhostBlockEntry> backupEntries = new ArrayList<>(entries);
             createClearUndoRecord(world, backupEntries, autoFileName);

            if (batchMode) {
                // 启动批量清除任务
                int taskId = taskIdCounter.incrementAndGet();
                activeClearTasks.add(new ClearTask(worldClient, entries, batchSize, sender, taskId, autoFile)); // 传递文件对象
                 // 使用 formatMessage 发送带翻译的消息
                sender.addChatMessage(formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.clear.batch_started", taskId, entries.size(), batchSize));
            } else {
                // 同步清除
                clearAllGhostBlocksSync(sender, worldClient, entries);

                // 同步清除完成后删除自动文件
                if (autoFile.exists()) {
                    if (!autoFile.delete()) {
                         System.err.println("[GhostBlock] 同步清除后未能删除自动清除文件: " + autoFile.getPath());
                          // 使用 formatMessage 发送带翻译的消息
                         sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "ghostblock.commands.clear.block.delete_failed", autoFile.getName()));
                    } else {
                        System.out.println("[GhostBlock] 同步清除后已删除自动清除文件: " + autoFile.getPath());
                    }
                }
            }
        }
    }

    // 为 clear block 操作创建撤销记录
    private void createClearUndoRecord(World world, List<GhostBlockData.GhostBlockEntry> clearedEntries, String autoFileName) {
        // clearedEntries = 清除前的状态 (幽灵 -> 原始 映射)
        Map<String, List<GhostBlockData.GhostBlockEntry>> backupMap = new HashMap<>(); // clear block 不涉及用户文件备份
        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_clear_block_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis();
        GhostBlockData.saveData(world, clearedEntries, undoFileName, true); // 对唯一的撤销文件使用覆盖模式
        undoHistory.push(new UndoRecord(
            undoFileName,
            backupMap, // 空的用户文件备份
            UndoRecord.OperationType.CLEAR_BLOCK // 标记为 CLEAR_BLOCK 操作
        ));
         System.out.println("[GhostBlock] 已创建 clear block 的撤销记录: " + undoFileName);
    }

    // 验证批次大小
    private void validateBatchSize(int batchSize) throws CommandException {
        if (batchSize <= 0) {
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.batch_size_too_small"));
        }
    }

    // 发送 clear block 的确认消息
    private void sendConfirmationMessage(ICommandSender sender, boolean batchMode, int batchSize) {
        StringBuilder confirmCommand = new StringBuilder("/cgb clear block");
        if (batchMode) {
            confirmCommand.append(" -b ").append(batchSize); // 带上批次大小
        }
        confirmCommand.append(" confirm"); // 添加 confirm 标志

        IChatComponent message = new ChatComponentText("")
            .appendSibling(new ChatComponentText(EnumChatFormatting.RED +
                 // 使用 LangUtil 获取翻译
                LangUtil.translate("ghostblock.commands.clear.block.confirm.question") + "\n")) // 确认问题
            .appendSibling(
                new ChatComponentText(LangUtil.translate("ghostblock.commands.clear.block.confirm.button")) // 确认按钮文字
                    .setChatStyle(new ChatStyle()
                        .setColor(EnumChatFormatting.RED) // 按钮颜色
                        .setBold(true) // 加粗
                        .setChatClickEvent(new ClickEvent(
                            Action.RUN_COMMAND, confirmCommand.toString() // 点击执行构建好的确认命令
                        ))
                    ));
        sender.addChatMessage(message); // 发送确认消息
    }


    // 创建带格式的进度消息 (保持原样，因为它手动构建组件，与 lang 文件中的 {n} 格式配合)
    private static IChatComponent createProgressMessage(String key, int percent, String progressBar) {
        String rawMessage = LangUtil.translate(key, "{0}", "{1}");
        String[] parts = rawMessage.split("\\{(\\d)\\}", -1);

        IChatComponent message = new ChatComponentText("");
        message.appendSibling(new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"))
            .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY)));

        if (parts.length > 0) {
            message.appendSibling(
                new ChatComponentText(parts[0])
                    .setChatStyle(new ChatStyle().setColor(LABEL_COLOR))
            );
        }

        message.appendSibling(
            new ChatComponentText(percent + "%")
                .setChatStyle(new ChatStyle().setColor(VALUE_COLOR))
        );

        if (parts.length > 1) {
             message.appendSibling(
                new ChatComponentText(parts[1])
                    .setChatStyle(new ChatStyle().setColor(LABEL_COLOR))
            );
        }

        message.appendSibling(new ChatComponentText(progressBar));

         if (parts.length > 2) {
             message.appendSibling(
                new ChatComponentText(parts[2])
                    .setChatStyle(new ChatStyle().setColor(LABEL_COLOR))
             );
         }

        return message;
    }


    // ================ 其他必需的静态方法 ================

    // 检查方块ID是否有效
    private static boolean isValidBlock(String blockId) {
        return Block.getBlockFromName(blockId) != null;
    }

    // 格式化消息（默认灰色）
    public static ChatComponentText formatMessage(String messageKey, Object... args) {
        return formatMessage(EnumChatFormatting.GRAY, messageKey, args);
    }

    // 格式化消息（带指定颜色） - 这个方法是核心，用于统一添加前缀和调用 LangUtil
    public static ChatComponentText formatMessage(
        EnumChatFormatting contentColor, // 参数1: 内容文本颜色
        String messageKey,              // 参数2: 语言文件中的翻译键
        Object... args                  // 参数3: 动态替换语言文件中占位符的值
    ) {
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        // 调用 LangUtil 进行翻译和格式化
        ChatComponentText content = new ChatComponentText(LangUtil.translate(messageKey, args));
        content.setChatStyle(new ChatStyle().setColor(contentColor));
        prefix.appendSibling(content);
        return prefix;
    }

    // 解析方块状态字符串 (例如 "minecraft:stone:1" 或 "log") (静态)
    private static BlockStateProxy parseBlockState(String input) throws CommandException {
        try {
            Block block;
            int meta = 0;

            // 尝试使用 CommandBase 的解析器（可能支持数字ID）
            try {
                 block = CommandBase.getBlockByText(Minecraft.getMinecraft().thePlayer, input);
            } catch (NumberFormatException nfe) {
                 // 如果输入是纯数字但无法解析为方块名，尝试作为 ID 解析
                 try {
                     int blockId = Integer.parseInt(input);
                     block = Block.getBlockById(blockId);
                     if (block == null) {
                           // 使用 LangUtil 获取错误信息
                          throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
                     }
                 } catch (NumberFormatException nfe2) {
                      // 既不是有效名称也不是纯数字 ID
                      // 使用 LangUtil 获取错误信息
                     throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
                 }
            } catch (CommandException ce) {
                 // CommandBase 抛出方块未找到异常
                 throw ce; // 直接抛出，通常已有翻译
            }


            // 检查输入字符串是否包含元数据部分
            if (input.contains(":")) {
                String[] parts = input.split(":");
                String potentialMetaStr = parts[parts.length - 1];
                if (isNumber(potentialMetaStr)) { // 使用静态 isNumber
                     String nameWithoutMeta = input.substring(0, input.lastIndexOf(':'));
                     try {
                          Block blockFromNameOnly = CommandBase.getBlockByText(Minecraft.getMinecraft().thePlayer, nameWithoutMeta);
                          if (blockFromNameOnly.equals(block)) {
                                try {
                                     int parsedMeta = Integer.parseInt(potentialMetaStr);
                                     block.getStateFromMeta(parsedMeta); // 验证
                                     meta = parsedMeta;
                                } catch (IllegalArgumentException e) {
                                      System.out.println("[GhostBlock WARN] 方块 '" + block.getRegistryName() + "' 的元数据 '" + potentialMetaStr + "' 无效，使用 0。");
                                     meta = 0;
                                }
                          }
                     } catch (CommandException e) { /* 名称部分无效 */ }
                }
            }

            if (block == Blocks.air) {
                meta = 0;
            }

             System.out.println("[GhostBlock 解析成功] 结果: " + block.getRegistryName() + " (ID=" + Block.getIdFromBlock(block) + "), meta=" + meta);
            return new BlockStateProxy(Block.getIdFromBlock(block), meta);

        } catch (CommandException ce) {
            throw ce; // 重新抛出命令异常
        } catch (Exception e) {
             System.err.println("[GhostBlock 解析错误] 解析方块状态时发生意外错误: " + input);
             e.printStackTrace();
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block")); // 通用错误
        }
    }


    // 静态方法：设置幽灵方块
    private static void setGhostBlock(WorldClient world, BlockPos pos, BlockStateProxy state) throws CommandException {
        checkLoaded(world, pos); // 检查区块是否加载
        if (world.isRemote) { // 确认是客户端
            Block block = Block.getBlockById(state.blockId);
            if (block != null) { // 确保方块有效
                world.setBlockState(pos, block.getStateFromMeta(state.metadata), 3);
                world.checkLightFor(EnumSkyBlock.BLOCK, pos);
                world.checkLightFor(EnumSkyBlock.SKY, pos);
            } else {
                System.out.println("[GhostBlock 错误] 无效方块 ID: " + state.blockId + "，无法设置幽灵方块。");
                  // 使用 LangUtil 获取错误信息
                 throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
            }
        }
    }

    // 静态方法：同步填充区域
    private static int fillGhostBlocks(WorldClient world, BlockPos from, BlockPos to, BlockStateProxy state) throws CommandException {
        Block block = Block.getBlockById(state.blockId);
        if (block == null) { // 再次检查方块有效性
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
        }

        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        int count = 0;
        int skipped = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    try {
                         setGhostBlock(world, pos, state); // 调用静态的 setGhostBlock 方法
                         count++;
                    } catch (CommandException e) {
                         skipped++;
                    }
                }
            }
        }
        if (skipped > 0) {
             // 这个警告可以考虑也用 LangUtil，但作为日志输出可能还好
            System.out.println("[GhostBlock 填充警告] 跳过了 " + skipped + " 个无效或未加载的位置。");
        }
        return count; // 返回成功设置的数量
    }

    // 检查坐标是否有效且已加载 (静态)
    private static void checkLoaded(WorldClient world, BlockPos pos) throws CommandException {
        if (!world.isBlockLoaded(pos)) { // 使用 isBlockLoaded 检查方块所在区块是否加载
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.unloaded"));
        }
        if (pos.getY() < 0 || pos.getY() >= 256) {
             // 使用 LangUtil 获取错误信息
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.out_of_world"));
        }
    }

    // 静态内部类：方块状态代理，只存储 ID 和元数据
    private static class BlockStateProxy {
        public final int blockId;
        public final int metadata;

        public BlockStateProxy(int blockId, int metadata) {
            this.blockId = blockId;
            this.metadata = metadata;
        }
    }

        // 静态内部类：批量填充任务 (修正自动继续逻辑并移除调试日志)
        private static class FillTask {
        final WorldClient world;
        final BlockStateProxy state;
        final List<BlockPos> remainingBlocks;
        final int batchSize;
        final boolean saveToFile;
        final String saveFileName;
        final ICommandSender sender; // 用于获取玩家位置
        private final int totalBlocks;
        private int processedCount = 0;
        private long lastUpdateTime = 0;
        private float lastReportedPercent = -1;
        private volatile boolean cancelled = false;
        private final int taskId;
        private final List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile;
        // 跟踪哪些位置之前是因为 (isBlockLoaded=false 或 距离远) 而未处理的
        private final Set<BlockPos> previouslyWaitingForLoadOrProximity = new HashSet<>();
        private static final double TASK_PLACEMENT_PROXIMITY_SQ = 32.0 * 32.0; // 任务执行时，玩家需要在此距离平方内才放置 (32格)

        public FillTask(WorldClient world, BlockStateProxy state, List<BlockPos> allBlocks, int totalBlocks_unused,
                        int batchSize, boolean saveToFile, String saveFileName, ICommandSender sender, int taskId,
                        List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile) {
            this.world = world;
            this.state = state;
            this.remainingBlocks = new ArrayList<>(allBlocks);
            this.totalBlocks = this.remainingBlocks.size();
            this.batchSize = Math.max(1, batchSize);
            this.saveToFile = saveToFile;
            this.saveFileName = saveFileName;
            this.sender = sender; // 保存 sender
            this.taskId = taskId;
            this.entriesToSaveForUserFile = entriesToSaveForUserFile != null ? new ArrayList<>(entriesToSaveForUserFile) : new ArrayList<>();
            this.processedCount = 0;
            System.out.println("[GhostBlock FillTask #" + taskId + "] 初始化 (带距离检查策略): total=" + this.totalBlocks + ", batch=" + this.batchSize);
        }


        boolean processBatch() {
            if (cancelled) {
                return true;
            }

            Block block = Block.getBlockById(state.blockId);
            if (block == null || block == Blocks.air) {
                if (processedCount == 0 && !cancelled) {
                    sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "ghostblock.commands.error.invalid_block"));
                    System.err.println("[GhostBlock FillTask #" + taskId + "] 错误：无效方块 ID " + state.blockId + " 或尝试填充空气。任务取消。");
                    this.cancel();
                }
                return true;
            }

            int attemptsThisTick = 0;
            int successfullyProcessedThisTick = 0;
            Iterator<BlockPos> iterator = remainingBlocks.iterator();

            // 获取当前玩家实例 (如果 sender 是玩家)
            EntityPlayer currentPlayer = null;
            if (sender instanceof EntityPlayer) {
                EntityPlayer cmdSenderPlayer = (EntityPlayer) sender;
                // 确保玩家仍然有效且在当前世界
                if (cmdSenderPlayer.isEntityAlive() && cmdSenderPlayer.worldObj == this.world) {
                    currentPlayer = cmdSenderPlayer;
                } else if (!cancelled) { // 如果玩家已不在，且任务未被取消，则取消任务
                    System.out.println("[GhostBlock FillTask #" + taskId + "] 命令发送者玩家已失效或不在当前世界，取消任务。");
                    this.cancel();
                    return true;
                }
            }


            while (iterator.hasNext() && attemptsThisTick < batchSize) {
                 if (cancelled) { // 在循环内部也检查取消状态
                     break;
                 }
                BlockPos pos = iterator.next();
                attemptsThisTick++;

                boolean canPlaceNow = false;
                String waitReason = "";

                if (pos.getY() >= 0 && pos.getY() < 256) {
                    if (world.isBlockLoaded(pos)) {
                        // 区块已加载，检查距离
                        if (currentPlayer != null) {
                            if (currentPlayer.getDistanceSqToCenter(pos) <= TASK_PLACEMENT_PROXIMITY_SQ) {
                                canPlaceNow = true;
                            } else {
                                waitReason = "玩家距离远 (" + String.format("%.1f", Math.sqrt(currentPlayer.getDistanceSqToCenter(pos))) + "m > " + String.format("%.1f", Math.sqrt(TASK_PLACEMENT_PROXIMITY_SQ)) + "m)";
                            }
                        } else {
                            // 没有有效的玩家上下文 (例如命令由控制台发起，或玩家已退出但任务未及时取消)
                            // 在这种情况下，如果区块已加载，我们允许放置，避免任务卡死。
                            canPlaceNow = true;
                            waitReason = "无玩家进行距离检查 (isBlockLoaded=true)";
                        }
                    } else {
                        waitReason = "isBlockLoaded=false";
                    }
                } else {
                    waitReason = "Y轴无效 (" + pos.getY() + ")";
                    // 对于Y轴无效的，我们会在下面直接移除
                }


                if (canPlaceNow) {
                    if (previouslyWaitingForLoadOrProximity.contains(pos)) {
                        System.out.println("[GhostBlock FillTask #" + taskId + "] 位置现已就绪: " + pos + ". 尝试放置...");
                        previouslyWaitingForLoadOrProximity.remove(pos);
                    }

                    try {
                        if (world.isRemote) {
                            IBlockState blockStateToSet = block.getStateFromMeta(state.metadata);
                            world.setBlockState(pos, blockStateToSet, 3);
                        } else {
                            System.err.println("[GhostBlock FillTask #" + taskId + " WARN] 尝试在非客户端世界执行任务！Pos: " + pos);
                        }

                        iterator.remove();
                        successfullyProcessedThisTick++;
                        processedCount++;

                    } catch (Exception e) {
                        System.err.println("[GhostBlock FillTask #" + taskId + " WARN] 在位置就绪后设置方块时出错 " + pos + ": " + e.getMessage());
                        e.printStackTrace();
                        iterator.remove(); // 即使失败也移除
                    }
                } else {
                    // 未能放置 (Y无效，或未加载，或加载了但距离远)
                    if (pos.getY() >= 0 && pos.getY() < 256) { // Y有效，但因其他原因等待
                        if (!previouslyWaitingForLoadOrProximity.contains(pos)) {
                            System.out.println("[GhostBlock FillTask #" + taskId + "] 等待放置: " + pos + " (原因: " + waitReason + ")");
                            previouslyWaitingForLoadOrProximity.add(pos);
                        }
                        // 中断当前批次，让其他任务有机会执行或等待玩家移动
                        // 如果不break，这个批次可能会因为大量远距离方块而空耗ticks
                        if (attemptsThisTick > 0) break; // 如果本次tick至少尝试了一个，就退出让下一tick重试
                    } else { // Y轴无效
                        System.out.println("[GhostBlock FillTask #" + taskId + "] 无效Y坐标 ("+pos.getY()+")，从任务移除: " + pos);
                        iterator.remove(); // 无效Y坐标，直接从任务中移除
                        // totalBlocks 保持不变，但这个方块不会被计入 processedCount
                    }
                }
            }

            if (cancelled) {
                return true;
            }

            boolean finished = remainingBlocks.isEmpty();

            if (finished) {
                System.out.println("[GhostBlock FillTask #" + taskId + "] 任务完成。成功放置: " + processedCount + " / 初始总数: " + totalBlocks);
                if (!previouslyWaitingForLoadOrProximity.isEmpty()) {
                    System.out.println("[GhostBlock FillTask #" + taskId + "] 注意: 任务完成时仍有 " + previouslyWaitingForLoadOrProximity.size() + " 个方块在等待列表中 (可能已在最后批次处理)。");
                }
                sendFinalProgress();
            } else {
                float currentPercent = (totalBlocks == 0) ? 100.0f : (processedCount * 100.0f) / totalBlocks;
                boolean forceUpdate = successfullyProcessedThisTick > 0;
                sendProgressIfNeeded(currentPercent, forceUpdate);
            }
            return finished;
        }

        private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
             if (totalBlocks == 0) {
                 currentPercent = 100.0f;
             } else {
                currentPercent = (processedCount * 100.0f) / totalBlocks;
             }
             currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent));
             currentPercent = Math.round(currentPercent * 10) / 10.0f;

            boolean progressChanged = Math.abs(currentPercent - lastReportedPercent) >= 0.1f;
            boolean timeout = System.currentTimeMillis() - lastUpdateTime > (totalBlocks > 1 ? 1000 : 100);
            boolean shouldSend = forceSend || progressChanged || timeout;

            if (shouldSend && currentPercent <= 100.0f) {
                String progressBar = createProgressBar(currentPercent, 10);
                IChatComponent message = GhostBlockCommand.createProgressMessage(
                    "ghostblock.commands.fill.progress",
                    (int) Math.floor(currentPercent),
                    progressBar
                );
                if (forceSend || currentPercent != lastReportedPercent) {
                     if (sender instanceof EntityPlayer) {
                         EntityPlayer player = (EntityPlayer) sender;
                         // 确保玩家仍然有效且在当前世界
                         if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                             if(!cancelled) System.out.println("[GhostBlock FillTask #" + taskId + "] 玩家已离开或无效，停止发送进度消息，并取消任务。");
                             this.cancel(); // 取消任务
                             return;
                         }
                     }
                     try {
                        sender.addChatMessage(message);
                     } catch (Exception e) {
                        System.err.println("[GhostBlock FillTask #" + taskId + "] 发送进度消息失败: " + e.getMessage());
                     }
                    lastReportedPercent = currentPercent;
                }
                lastUpdateTime = System.currentTimeMillis();
            }
        }

        private void sendFinalProgress() {
             if (lastReportedPercent < 100.0f && !cancelled) {
                sendProgressIfNeeded(100.0f, true);
             }

            // 保存文件逻辑 (仅当任务未取消时)
            if (saveToFile && !cancelled) {
                String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                 if (this.entriesToSaveForUserFile != null && !this.entriesToSaveForUserFile.isEmpty()) {
                    System.out.println("[GhostBlock FillTask #" + taskId + "] 任务完成，尝试保存 " + this.entriesToSaveForUserFile.size() + " 个条目到用户文件: " + actualSaveFileName);
                    GhostBlockData.saveData(world, this.entriesToSaveForUserFile, actualSaveFileName, false);
                    String displayName = (saveFileName == null) ?
                        LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world))
                        : saveFileName;

                    // 检查发送者是否仍然有效
                     boolean senderStillValid = true;
                     if (sender instanceof EntityPlayer) {
                         EntityPlayer player = (EntityPlayer) sender;
                         if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                             senderStillValid = false;
                             if(!cancelled) System.out.println("[GhostBlock FillTask #" + taskId + "] 玩家已离开或无效，跳过发送保存成功消息。");
                         }
                     }
                     if(senderStillValid){
                         try {
                            sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                                "ghostblock.commands.save.success", displayName));
                         } catch (Exception e) {
                             System.err.println("[GhostBlock FillTask #" + taskId + "] 发送保存成功消息失败: " + e.getMessage());
                         }
                     }
                 } else {
                     System.out.println("[GhostBlock FillTask #" + taskId + "] WARN: 任务标记为保存，但没有提供或生成用户文件条目。");
                 }
            }

            // 发送完成消息 (仅当任务未取消时)
            if (!cancelled) {
                 boolean senderStillValid = true;
                 if (sender instanceof EntityPlayer) {
                     EntityPlayer player = (EntityPlayer) sender;
                     if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                         senderStillValid = false;
                         if(!cancelled) System.out.println("[GhostBlock FillTask #" + taskId + "] 玩家已离开或无效，跳过发送完成消息。");
                     }
                 }
                 if(senderStillValid){
                     try {
                        String finishKey = (totalBlocks == 1 && processedCount <= 1) ? "ghostblock.commands.fill.finish_single" : "ghostblock.commands.fill.finish";
                        sender.addChatMessage(formatMessage(FINISH_COLOR, finishKey, processedCount));
                     } catch (Exception e) {
                         System.err.println("[GhostBlock FillTask #" + taskId + "] 发送完成消息失败: " + e.getMessage());
                     }
                 }
            } else {
                 System.out.println("[GhostBlock FillTask #" + taskId + "] 任务已被取消，不发送完成或保存消息。");
            }
        }

        public void cancel() {
            if (!this.cancelled) {
                 System.out.println("[GhostBlock FillTask #" + taskId + "] 标记为取消。");
                this.cancelled = true;
                 previouslyWaitingForLoadOrProximity.clear();
            }
        }
        public int getTaskId() {
            return taskId;
        }
    }

    // 静态内部类：批量加载任务
    private static class LoadTask {
        private volatile boolean cancelled = false;
        private final WorldClient world;
        private final List<GhostBlockData.GhostBlockEntry> entries;
        private int currentIndex; // 下一个要尝试处理的条目的索引
        private final int batchSize;
        private final ICommandSender sender; // 用于获取玩家位置
        private long lastUpdateTime = 0;
        private float lastReportedPercent = -1;
        private final int taskId;
        // 跟踪哪些索引之前是因为 (isBlockLoaded=false 或 距离远) 而未处理的
        private final Set<Integer> previouslyWaitingForLoadOrProximityIndices = new HashSet<>();
        private static final double TASK_PLACEMENT_PROXIMITY_SQ = 32.0 * 32.0; // 任务执行时，玩家需要在此距离平方内才放置 (32格)


        public LoadTask(WorldClient world, List<GhostBlockData.GhostBlockEntry> entriesToLoad, int batchSize,
                    ICommandSender sender, int taskId) {
            this.world = world;
            this.entries = entriesToLoad != null ? new ArrayList<>(entriesToLoad) : new ArrayList<>();
            this.batchSize = Math.max(1, batchSize);
            this.sender = sender; // 保存 sender
            this.currentIndex = 0;
            this.taskId = taskId;
            System.out.println("[GhostBlock LoadTask #" + taskId + "] 初始化 (带距离检查策略): total=" + this.entries.size() + ", batch=" + this.batchSize);
        }

        public void cancel() {
             if (!this.cancelled) {
                 System.out.println("[GhostBlock LoadTask #" + taskId + "] 标记为取消。");
                 this.cancelled = true;
                 previouslyWaitingForLoadOrProximityIndices.clear();
             }
        }

        boolean processBatch() {
            if (cancelled) {
                return true;
            }
            if (entries.isEmpty() || currentIndex >= entries.size()) {
                 if (entries.isEmpty() || (lastReportedPercent >= 100.0f || (lastReportedPercent == -1 && entries.isEmpty()) )) {
                     // 任务开始就为空，或已报告100%
                 } else if (!cancelled){
                     System.out.println("[GhostBlock Load Task #" + taskId + "] 所有条目处理完毕或列表为空，发送最终进度。");
                     sendFinalProgress(); // 确保发送最终消息
                 }
                 return true;
            }

            int successfullyProcessedThisTick = 0;

            EntityPlayer currentPlayer = null;
            if (sender instanceof EntityPlayer) {
                EntityPlayer cmdSenderPlayer = (EntityPlayer) sender;
                if (cmdSenderPlayer.isEntityAlive() && cmdSenderPlayer.worldObj == this.world) {
                    currentPlayer = cmdSenderPlayer;
                } else if(!cancelled) {
                    System.out.println("[GhostBlock LoadTask #" + taskId + "] 命令发送者玩家已失效或不在当前世界，取消任务。");
                    this.cancel();
                    return true;
                }
            }

            // 批次内迭代
            for (int i = 0; i < batchSize && currentIndex < entries.size(); /* no direct increment for i here */) {
                if (cancelled) {
                    break;
                }

                GhostBlockData.GhostBlockEntry entry = entries.get(currentIndex);
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                
                boolean canPlaceNow = false;
                String waitReason = "";

                if (pos.getY() >= 0 && pos.getY() < 256) {
                    if (world.isBlockLoaded(pos)) {
                        if (currentPlayer != null) {
                            if (currentPlayer.getDistanceSqToCenter(pos) <= TASK_PLACEMENT_PROXIMITY_SQ) {
                                canPlaceNow = true;
                            } else {
                                waitReason = "玩家距离远 (" + String.format("%.1f", Math.sqrt(currentPlayer.getDistanceSqToCenter(pos))) + "m > " + String.format("%.1f", Math.sqrt(TASK_PLACEMENT_PROXIMITY_SQ)) + "m)";
                            }
                        } else {
                            canPlaceNow = true;
                            waitReason = "无玩家进行距离检查 (isBlockLoaded=true)";
                        }
                    } else {
                        waitReason = "isBlockLoaded=false";
                    }
                } else {
                    waitReason = "Y轴无效 (" + pos.getY() + ")";
                }

                if (canPlaceNow) {
                    if (previouslyWaitingForLoadOrProximityIndices.contains(currentIndex)) {
                        System.out.println("[GhostBlock LoadTask #" + taskId + "] 位置现已就绪: " + pos + " (Index: " + currentIndex + "). 尝试放置...");
                        previouslyWaitingForLoadOrProximityIndices.remove(currentIndex);
                    }

                    Block block = Block.getBlockFromName(entry.blockId);
                    if (block != null && block != Blocks.air) {
                        try {
                            if (world.isRemote) {
                                IBlockState blockStateToSet = block.getStateFromMeta(entry.metadata);
                                world.setBlockState(pos, blockStateToSet, 3);
                            } else {
                                System.err.println("[GhostBlock LoadTask #" + taskId + " WARN] 尝试在非客户端世界执行任务！Pos: " + pos);
                            }
                            successfullyProcessedThisTick++;
                        } catch (Exception e) {
                            System.err.println("[GhostBlock LoadTask #" + taskId + " WARN] 在位置就绪后设置方块时出错 " + pos + " (Index: " + currentIndex + "): " + e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        System.out.println("[GhostBlock LoadTask #" + taskId + " WARN] 加载时发现无效方块ID '" + entry.blockId + "' 或空气方块，位于 " + pos + " (Index: " + currentIndex + "). 跳过.");
                    }
                    currentIndex++; // 无论成功、失败还是跳过无效方块，都前进到下一个条目
                    i++; // 增加批次内处理计数
                } else {
                    // 未能放置 (Y无效，或未加载，或加载了但距离远)
                    if (pos.getY() >= 0 && pos.getY() < 256) { // Y有效，但因其他原因等待
                        if (!previouslyWaitingForLoadOrProximityIndices.contains(currentIndex)) {
                            System.out.println("[GhostBlock LoadTask #" + taskId + "] 等待放置: " + pos + " (Index: " + currentIndex + ", 原因: " + waitReason + ")");
                            previouslyWaitingForLoadOrProximityIndices.add(currentIndex);
                        }
                         // 中断当前批次，让其他任务有机会执行或等待玩家移动
                        if (i > 0 || currentIndex < entries.size()-1 ) break; // 如果本次tick至少尝试了一个，或者后面还有条目，就退出让下一tick重试
                    } else { // Y轴无效
                        System.out.println("[GhostBlock LoadTask #" + taskId + "] 无效Y坐标 ("+pos.getY()+")，从任务跳过: " + pos + " (Index: " + currentIndex + ")");
                        currentIndex++; // 对于无效Y，我们跳过这个条目
                        i++; // 也计入当前批次，因为它被“处理”了（通过跳过）
                    }
                     // 如果是因为等待（Y有效），则中断此批次
                    if (pos.getY() >= 0 && pos.getY() < 256) {
                        break; 
                    }
                }
            }

            if (cancelled) {
                return true;
            }

            boolean finished = currentIndex >= entries.size();

            if (finished) {
                 if (lastReportedPercent < 100.0f && !cancelled) {
                     System.out.println("[GhostBlock Load Task #" + taskId + "] 所有条目处理完毕 (循环结束)，发送最终进度。");
                     sendFinalProgress();
                 } else if(!cancelled) {
                      System.out.println("[GhostBlock Load Task #" + taskId + "] 任务已完成 (循环结束)。");
                 }
            } else {
                float currentPercent = entries.isEmpty() ? 100.0f : (currentIndex * 100.0f) / entries.size();
                boolean forceUpdate = successfullyProcessedThisTick > 0;
                sendProgressIfNeeded(currentPercent, forceUpdate);
            }
            return finished;
        }

        private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
             if (entries.isEmpty()) {
                 currentPercent = 100.0f;
             } else {
                currentPercent = (currentIndex * 100.0f) / entries.size();
             }
             currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent));
             currentPercent = Math.round(currentPercent * 10) / 10.0f;


            boolean progressChanged = Math.abs(currentPercent - lastReportedPercent) >= 0.1f;
            boolean timeout = System.currentTimeMillis() - lastUpdateTime > 1000;
            boolean shouldSend = forceSend || progressChanged || timeout;


            if (shouldSend && currentPercent <= 100.0f) {
                String progressBar = createProgressBar(currentPercent, 10);
                IChatComponent message = GhostBlockCommand.createProgressMessage(
                    "ghostblock.commands.load.progress",
                    (int) Math.floor(currentPercent),
                    progressBar
                );
                if (forceSend || currentPercent != lastReportedPercent) {
                     if (sender instanceof EntityPlayer) {
                         EntityPlayer player = (EntityPlayer) sender;
                         if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                              if(!cancelled) System.out.println("[GhostBlock LoadTask #" + taskId + "] 玩家已离开或无效，停止发送进度消息，并取消任务。");
                              this.cancel(); // 取消任务
                              return;
                         }
                     }
                    try {
                         sender.addChatMessage(message);
                    } catch (Exception e) {
                         System.err.println("[GhostBlock LoadTask #" + taskId + "] 发送进度消息失败: " + e.getMessage());
                    }
                    lastReportedPercent = currentPercent;
                }
                lastUpdateTime = System.currentTimeMillis();
            }
        }

        private void sendFinalProgress() {
             if (lastReportedPercent < 100.0f && !cancelled) {
                sendProgressIfNeeded(100.0f, true);
             }

            if (!cancelled) {
                 boolean senderStillValid = true;
                 if (sender instanceof EntityPlayer) {
                     EntityPlayer player = (EntityPlayer) sender;
                     if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                         senderStillValid = false;
                         if(!cancelled) System.out.println("[GhostBlock LoadTask #" + taskId + "] 玩家已离开或无效，跳过发送完成消息。");
                     }
                 }
                 if (senderStillValid) {
                     try {
                         sender.addChatMessage(formatMessage(FINISH_COLOR, "ghostblock.commands.load.finish", entries.size()));
                     } catch (Exception e) {
                          System.err.println("[GhostBlock LoadTask #" + taskId + "] 发送完成消息失败: " + e.getMessage());
                     }
                 }

                 if (!previouslyWaitingForLoadOrProximityIndices.isEmpty()) {
                    System.out.println("[GhostBlock LoadTask #" + taskId + "] 注意: 任务完成时仍有 " + previouslyWaitingForLoadOrProximityIndices.size() + " 个索引在等待列表中。");
                 }
            } else {
                  System.out.println("[GhostBlock LoadTask #" + taskId + "] 任务已被取消，不发送完成消息。");
            }
        }
        public int getTaskId() {
            return taskId;
        }
    }



    // 非静态事件处理器，处理世界加载事件
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.world.isRemote || !(event.world instanceof WorldClient)) {
            return; // 只处理客户端世界加载
        }
        WorldClient world = (WorldClient) event.world;

        // 如果启用了自动放置，则 onEntityJoinWorld 会处理恢复逻辑，这里就不处理了
        // 避免重复恢复或恢复原始方块覆盖掉自动放置的幽灵方块
        if (GhostConfig.enableAutoPlaceOnJoin) {
            System.out.println("[GhostBlock] onWorldLoad: 自动放置已启用，跳过此处的 cleanupAndRestoreOnLoad。");
            return;
        }

        // 调用清理和恢复逻辑 (仅当自动放置禁用时)
        cleanupAndRestoreOnLoad(world);
    }
    
    // 在 GhostBlockCommand.java 类中，作为外部类的一个静态方法
    private static void cleanupPendingAutoPlaceStatic(boolean deleteFile) {
        String fileNameForDimCheck = null; // 用于获取维度信息
        if (GhostBlockCommand.pendingAutoPlaceFileRef != null) {
            fileNameForDimCheck = GhostBlockCommand.pendingAutoPlaceFileRef.getName();
        }

        if (deleteFile && GhostBlockCommand.pendingAutoPlaceFileRef != null && GhostBlockCommand.pendingAutoPlaceFileRef.exists()) {
            if (GhostBlockCommand.pendingAutoPlaceFileRef.delete()) {
                System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 已成功删除自动放置文件: " + fileNameForDimCheck);
            } else {
                System.err.println("[GhostBlock-ERROR AutoPlaceCleanup] 未能删除自动放置文件: " + fileNameForDimCheck);
            }
        }
        GhostBlockCommand.pendingAutoPlaceEntry = null;
        GhostBlockCommand.pendingAutoPlaceTargetPos = null;
        GhostBlockCommand.pendingAutoPlaceFileRef = null;
        GhostBlockCommand.autoPlaceTickDelayCounter = 0;
        boolean wasInProgress = GhostBlockCommand.autoPlaceInProgress;
        GhostBlockCommand.autoPlaceInProgress = false;
        System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 已清理待处理的自动放置状态。autoPlaceInProgress 设置为 false。");

        if (wasInProgress && GhostBlockCommand.isFirstJoin) {
            WorldClient world = Minecraft.getMinecraft().theWorld;
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (world != null && player != null) {
                int fileDim = Integer.MIN_VALUE;
                if (fileNameForDimCheck != null) { // 使用之前保存的文件名来获取维度
                    fileDim = GhostBlockData.getDimensionFromFileName(fileNameForDimCheck);
                } else {
                    fileDim = player.dimension; // Fallback，如果文件名未知
                    System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 用于维度检查的文件名未知，将使用当前玩家维度。");
                }

                if (player.dimension == fileDim || fileDim == Integer.MIN_VALUE) {
                    System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 自动放置处理结束，且仍是 isFirstJoin。处理首次加入逻辑...");
                    GhostBlockCommand.isFirstJoin = false;
                    GhostBlockCommand.lastTrackedDimension = player.dimension;
                    cleanupAndRestoreOnLoad(world); // 调用本类的静态方法
                } else {
                     System.out.println("[GhostBlock-DEBUG AutoPlaceCleanup] 自动放置处理结束，但玩家维度 ("+player.dimension+") 与文件先前维度 ("+fileDim+") 不符，不执行 isFirstJoin 的 cleanupAndRestoreOnLoad。");
                }
            }
        }
    }

    // 封装世界加载时的清理和恢复逻辑
    private static void cleanupAndRestoreOnLoad(WorldClient world) {
         if (world == null) {
             return;
         }
         System.out.println("[GhostBlock] 世界加载/切换，检查并恢复维度 " + world.provider.getDimensionId());
         String autoFileName = getAutoClearFileName(world); // "clear_..."
         File autoFile = GhostBlockData.getDataFile(world, autoFileName);

         if (autoFile.exists()) {
              System.out.println("[GhostBlock] 发现自动清除文件 " + autoFileName + "，尝试恢复原始方块...");
              List<GhostBlockData.GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
              int restored = 0;
              int failed = 0;
              if (!entries.isEmpty()) {
                  for (GhostBlockData.GhostBlockEntry entry : entries) {
                      BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                      Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
                      if (originalBlock != null) { // 允许恢复空气
                           try {
                               world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                               world.checkLightFor(EnumSkyBlock.BLOCK, pos); // 确保光照更新
                               world.checkLightFor(EnumSkyBlock.SKY, pos);
                               restored++;
                           } catch (Exception e) {
                               System.err.println("[GhostBlock 恢复错误] " + pos + ": " + e.getMessage());
                               failed++;
                           }
                      } else {
                           System.err.println("[GhostBlock 恢复警告] 无法找到原始方块 ID: " + entry.originalBlockId + " at " + pos);
                           failed++;
                      }
                  }
                  System.out.println("[GhostBlock] 恢复完成: " + restored + " 个成功, " + failed + " 个失败。");
              } else {
                   System.out.println("[GhostBlock] 自动清除文件为空。");
              }

              // 删除自动保存文件
              if (!autoFile.delete()) {
                   System.err.println("[GhostBlock] 未能删除自动清除文件: " + autoFile.getPath());
              } else {
                   System.out.println("[GhostBlock] 已删除自动清除文件: " + autoFile.getPath());
              }
         } else {
              System.out.println("[GhostBlock] 未发现自动清除文件: " + autoFileName);
         }
    }


    // 非静态事件处理器，处理世界卸载事件
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
    if (!event.world.isRemote) return;
    World unloadedWorld = event.world;
    if (unloadedWorld == null || !(unloadedWorld instanceof WorldClient)) return;
    WorldClient clientWorld = (WorldClient) unloadedWorld;

    System.out.println("[GhostBlock onWorldUnload] 世界卸载事件触发。当前 autoPlaceInProgress: " + autoPlaceInProgress);

    // 首先，如果存在任何待处理的自动放置任务，立即清理它
    if (pendingAutoPlaceEntry != null || autoPlaceInProgress) {
        System.out.println("[GhostBlock onWorldUnload] 检测到有待处理的自动放置任务，世界正在卸载，将进行清理。");
        cleanupPendingAutoPlaceStatic(true); // 调用静态清理方法
        System.out.println("[GhostBlock onWorldUnload] 已调用静态方法清理待处理的自动放置。");
        // 清理后，autoPlaceInProgress 应该为 false
    }

    // --- 保存脚下幽灵方块信息到新的 autoplace_*.json 文件 (如果适用) ---
    if (GhostConfig.enableAutoPlaceOnJoin) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != null && player.worldObj == clientWorld) { // 确保玩家仍在当前卸载的世界

            // 使用与 parseBlockPosLegacy 相同的逻辑来计算玩家“逻辑上”的脚下坐标
            // 即，如果此时执行 /cgb set ~ ~-1 ~，幽灵方块会放置在哪个坐标
            BlockPos logicalPlayerFeetPos;
            double playerExactX = player.posX;
            double playerExactY = player.posY;
            double playerExactZ = player.posZ;

            // 计算的是如果执行 set ~ ~-1 ~，方块会放置的位置
            logicalPlayerFeetPos = new BlockPos(
                Math.floor(playerExactX),        // ~ 的 X
                Math.floor(playerExactY - 1.0),  // ~-1 的 Y
                Math.floor(playerExactZ)         // ~ 的 Z
            );

            System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 玩家精确坐标: (" + playerExactX + ", " + playerExactY + ", " + playerExactZ + ")");
            System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 计算出的用于查找的“逻辑脚下”坐标: " + logicalPlayerFeetPos);

            String tempClearFileName = getAutoClearFileName(clientWorld); // 调用静态方法
            List<GhostBlockData.GhostBlockEntry> clearEntries = GhostBlockData.loadData(clientWorld, Collections.singletonList(tempClearFileName));
            System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 从 " + tempClearFileName + ".json 加载了 " + clearEntries.size() + " 个条目。");

            // 在 clear_*.json 中查找是否存在一个幽灵方块，其坐标与我们计算出的 logicalPlayerFeetPos 匹配
            final BlockPos finalLogicalPlayerFeetPos = logicalPlayerFeetPos; // lambda需要final或effectively final
            Optional<GhostBlockData.GhostBlockEntry> ghostEntryAtLogicalFeet = clearEntries.stream()
                .filter(entry -> entry.x == finalLogicalPlayerFeetPos.getX() &&
                                 entry.y == finalLogicalPlayerFeetPos.getY() &&
                                 entry.z == finalLogicalPlayerFeetPos.getZ())
                .findFirst();

            String autoPlaceSaveFileName = getAutoPlaceSaveFileName(clientWorld); // 调用静态方法
            File autoPlaceFileToSaveTo = GhostBlockData.getDataFile(clientWorld, autoPlaceSaveFileName);

            if (ghostEntryAtLogicalFeet.isPresent()) {
                // 如果玩家的“逻辑脚下”确实是一个已记录的幽灵方块
                GhostBlockData.GhostBlockEntry entryToSave = ghostEntryAtLogicalFeet.get();
                // entryToSave 的 x,y,z 就是 logicalPlayerFeetPos
                // 我们将这个完整的 GhostBlockEntry 保存到 autoplace 文件
                GhostBlockData.saveData(clientWorld, Collections.singletonList(entryToSave), autoPlaceSaveFileName, true); // 覆盖模式
                System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 玩家逻辑脚下位置 (" + logicalPlayerFeetPos + ") 是已记录的幽灵方块 (方块: " + entryToSave.blockId + ")。信息已保存到 " + autoPlaceSaveFileName + ".json。");
            } else {
                // 如果玩家的“逻辑脚下”不是已知的幽灵方块，则删除任何旧的 autoplace 文件
                System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 玩家逻辑脚下位置 (" + logicalPlayerFeetPos + ") 不是已记录的幽灵方块 (或clear文件中无记录)。删除旧的 " + autoPlaceSaveFileName + ".json (如果存在)。");
                if (autoPlaceFileToSaveTo.exists()) {
                    if (!autoPlaceFileToSaveTo.delete()) {
                        System.err.println("[GhostBlock onWorldUnload ERROR] (保存逻辑) 未能删除旧的自动放置文件: " + autoPlaceFileToSaveTo.getName());
                    } else {
                        System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 成功删除旧的自动放置文件: " + autoPlaceFileToSaveTo.getName());
                    }
                }
            }
        } else {
            System.out.println("[GhostBlock onWorldUnload] (保存逻辑) 玩家为空或不在卸载的世界中。尝试清理可能存在的自动放置文件。");
            String autoPlaceSaveFileName = getAutoPlaceSaveFileName(clientWorld); // 调用静态方法
            File autoPlaceFileToClean = GhostBlockData.getDataFile(clientWorld, autoPlaceSaveFileName);
            if (autoPlaceFileToClean.exists()) {
                if (autoPlaceFileToClean.delete()) {
                    System.out.println("[GhostBlock onWorldUnload] (保存逻辑，玩家不在) 删除了可能残留的自动放置文件: " + autoPlaceFileToClean.getName());
                } else {
                     System.err.println("[GhostBlock onWorldUnload ERROR] (保存逻辑，玩家不在) 未能删除残留的自动放置文件: " + autoPlaceFileToClean.getName());
                }
            }
        }
    }
    // --- 自动放置保存逻辑结束 ---

    // --- 原有的清理与卸载的世界相关的文件 ---
    String baseId = GhostBlockData.getWorldBaseIdentifier(clientWorld);
    int unloadedDim = clientWorld.provider.getDimensionId();
    System.out.println("[GhostBlock onWorldUnload] (标准清理) 正在卸载世界: " + baseId + " Dim: " + unloadedDim);

    File tempClearFileObject = GhostBlockData.getDataFile(clientWorld, getAutoClearFileName(clientWorld)); // 调用静态方法
    if (tempClearFileObject.exists()) {
        if (tempClearFileObject.delete()) {
            System.out.println("[GhostBlock onWorldUnload] (标准清理) 删除 clear 文件 (" + tempClearFileObject.getName() + ") 结果: true");
        } else {
            System.err.println("[GhostBlock onWorldUnload ERROR] (标准清理) 删除 clear 文件 (" + tempClearFileObject.getName() + ") 失败!");
        }
    }

    File savesDir = new File(GhostBlockData.SAVES_DIR);
    final String undoPrefix = "undo_" + baseId + "_dim_" + unloadedDim + "_";
    File[] undoFiles = savesDir.listFiles((dir, name) -> name.startsWith(undoPrefix) && name.endsWith(".json"));
    if (undoFiles != null) {
        for (File file : undoFiles) {
            if (file.delete()) {
                System.out.println("[GhostBlock onWorldUnload] (标准清理) 尝试删除 undo 文件: " + file.getName() + " 结果: true");
            } else {
                System.out.println("[GhostBlock onWorldUnload] (标准清理) 尝试删除 undo 文件: " + file.getName() + " 结果: false");
            }
        }
    }

    System.out.println("[GhostBlock onWorldUnload] (标准清理) 世界卸载，取消所有活动任务...");
    ICommandSender feedbackSender = Minecraft.getMinecraft().thePlayer;
    cancelAllTasks(feedbackSender); // 调用静态方法

    isFirstJoin = true;
    autoPlaceInProgress = false; // 再次确保重置，以防万一
    System.out.println("[GhostBlock onWorldUnload] (标准清理) 重置 isFirstJoin 和 autoPlaceInProgress。");
}

    // 辅助方法：取消所有类型的活动任务
    private void cancelAllTasks(ICommandSender feedbackSender) {
         int cancelledCount = 0;
         synchronized (activeTasks) {
             cancelledCount += activeTasks.size();
             activeTasks.forEach(FillTask::cancel); // 标记任务为取消
             activeTasks.clear(); // 清空列表
         }
          synchronized (activeLoadTasks) {
             cancelledCount += activeLoadTasks.size();
             activeLoadTasks.forEach(LoadTask::cancel);
             activeLoadTasks.clear();
         }
           synchronized (activeClearTasks) {
             cancelledCount += activeClearTasks.size();
             activeClearTasks.forEach(ClearTask::cancel);
             activeClearTasks.clear();
         }
         int pausedCount = pausedTasks.size();
         pausedTasks.clear(); // 在世界切换或卸载时，也清除所有暂停的任务
         cancelledCount += pausedCount;

         System.out.println("[GhostBlock] 取消了 " + cancelledCount + " 个活动/暂停任务。");
          // 如果有发送者且确实取消了任务，发送反馈
          if (feedbackSender != null && cancelledCount > 0) {
               // 使用 formatMessage 发送带翻译的消息
             feedbackSender.addChatMessage(
                 formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.task.cancelled_world_change")
             );
          }
    }


}
