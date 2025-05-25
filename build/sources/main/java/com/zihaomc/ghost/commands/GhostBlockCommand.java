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

    // ================ 新增的撤销记录类 ================
    private static class UndoRecord {
        public enum OperationType { SET, CLEAR_BLOCK } // 操作类型枚举

        public final String undoFileName;
        public final Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups;
        public final OperationType operationType; // 新增操作类型标记

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
            System.out.println("[GhostBlock-DEBUG] CommandEventHandler 已初始化");
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            // ---- START: 删除或注释掉以下用于注册命令的代码块 ----
            /*
            if (event.phase == TickEvent.Phase.END && !registered) {
                // 注册命令只需一次 (现在移到 Ghost.java 的 init 方法了)
                ClientCommandHandler.instance.registerCommand(new GhostBlockCommand());
                registered = true;
                System.out.println("[GhostBlock-DEBUG] 命令已注册 (通过 Tick)"); // 可以修改或删除日志
            }
            */
            // ---- END: 删除或注释掉的代码块 ----

            // 保留 Tick 事件的其他逻辑 (处理任务、确认等)
            if (event.phase == TickEvent.Phase.END) {
                // 清除过期的确认请求
                Iterator<Map.Entry<String, ClearConfirmation>> iter = pendingConfirmations.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<String, ClearConfirmation> entry = iter.next();
                    if (System.currentTimeMillis() - entry.getValue().timestamp > CONFIRMATION_TIMEOUT) {
                        iter.remove();
                    }
                }

                // 处理批量填充任务
                synchronized (activeTasks) { // 同步访问
                    Iterator<FillTask> iterator = activeTasks.iterator();
                    while (iterator.hasNext()) {
                        FillTask task = iterator.next();
                        if (task.processBatch()) { // 如果任务完成或取消
                            iterator.remove(); // 从活动列表移除
                            if (!task.cancelled) { // 只有在未取消时才打印完成
                                System.out.println("[GhostBlock] 填充任务 #" + task.getTaskId() + " 完成");
                            }
                        }
                    }
                }

                // 处理批量加载任务
                synchronized (activeLoadTasks) { // 同步访问
                    Iterator<LoadTask> loadIterator = activeLoadTasks.iterator();
                    while (loadIterator.hasNext()) {
                        LoadTask task = loadIterator.next();
                        if (task.processBatch()) { // 如果任务完成或取消
                            loadIterator.remove(); // 从活动列表移除
                             if (!task.cancelled) { // 只有在未取消时才打印完成
                                System.out.println("[GhostBlock] 加载任务 #" + task.getTaskId() + " 完成");
                             }
                        }
                    }
                }

                // 处理批量清除任务
                synchronized (activeClearTasks) { // 同步访问
                    Iterator<ClearTask> clearIter = activeClearTasks.iterator();
                    while (clearIter.hasNext()) {
                        ClearTask task = clearIter.next();
                        if (task.processBatch()) { // 如果任务完成或取消
                           clearIter.remove(); // 从活动列表移除
                            if (!task.cancelled) { // 只有在未取消时才打印完成
                                System.out.println("[GhostBlock] 清除任务 #" + task.getTaskId() + " 完成");
                            }
                        }
                    }
                }
            }
        }
    }

    // 非静态事件处理器，需要注册 GhostBlockCommand 的实例
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        // 仅在客户端处理
        if (!event.world.isRemote) {
            return;
        }
        // 仅处理玩家实体
        if (!(event.entity instanceof EntityPlayer)) {
            return;
        }
        // 确保是本地玩家
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null || !event.entity.equals(player)) {
            return;
        }

        WorldClient world = (WorldClient) event.world; // 事件中的世界是正确的

        if (world == null) {
            System.out.println("[GhostBlock-DEBUG] onEntityJoinWorld: 世界为 null");
            return;
        }

        int currentDim = player.dimension;
        System.out.println("[GhostBlock-DEBUG] 玩家加入世界/切换维度 - 当前维度: " + currentDim + ", 上次跟踪维度: " + lastTrackedDimension);

        // --- 新增：自动放置脚下幽灵方块逻辑 ---
        if (GhostConfig.enableAutoPlaceOnJoin) {
            System.out.println("[GhostBlock-DEBUG AutoPlace] 自动放置功能已启用，检查玩家脚下..."); // Log 1
            BlockPos playerPos = player.getPosition(); // 使用整数坐标
            BlockPos posBelow = playerPos.down();
            // 或者尝试更精确的坐标（如果上面那个不行可以试试这个）
            // BlockPos posBelow = new BlockPos(MathHelper.floor_double(player.posX), MathHelper.floor_double(player.posY - 0.01), MathHelper.floor_double(player.posZ));
            System.out.println("[GhostBlock-DEBUG AutoPlace] Player Pos: " + playerPos + ", Pos Below: " + posBelow); // Log 2

            String autoClearFileName = getAutoClearFileName(world);
            System.out.println("[GhostBlock-DEBUG AutoPlace] Auto Clear File Name: " + autoClearFileName); // Log 3

            List<GhostBlockData.GhostBlockEntry> autoClearEntries = GhostBlockData.loadData(world, Collections.singletonList(autoClearFileName));
            System.out.println("[GhostBlock-DEBUG AutoPlace] Loaded " + autoClearEntries.size() + " entries from auto clear file."); // Log 4

            // 在自动清除记录中查找脚下方块的记录
            Optional<GhostBlockData.GhostBlockEntry> entryBelowOpt = autoClearEntries.stream()
                    .filter(entry -> entry.x == posBelow.getX() && entry.y == posBelow.getY() && entry.z == posBelow.getZ())
                    .findFirst();

            if (entryBelowOpt.isPresent()) {
                GhostBlockData.GhostBlockEntry entryBelow = entryBelowOpt.get();
                System.out.println("[GhostBlock-DEBUG AutoPlace] Found entry below: " + // Log 5
                        "X=" + entryBelow.x + ", Y=" + entryBelow.y + ", Z=" + entryBelow.z +
                        ", Block=" + entryBelow.blockId + ":" + entryBelow.metadata +
                        ", Original=" + entryBelow.originalBlockId + ":" + entryBelow.originalMetadata);

                // 检查当前世界中该位置是否为空气
                IBlockState currentState = world.getBlockState(posBelow);
                System.out.println("[GhostBlock-DEBUG AutoPlace] Current state at posBelow: " + currentState); // Log 6

                if (currentState.getBlock() == Blocks.air) {
                    System.out.println("[GhostBlock-DEBUG AutoPlace] Pos below is Air. Attempting to restore ghost block."); // Log 7

                    Block ghostBlock = Block.getBlockFromName(entryBelow.blockId);
                    System.out.println("[GhostBlock-DEBUG AutoPlace] Ghost block lookup result: " + ghostBlock); // Log 8

                    if (ghostBlock != null && ghostBlock != Blocks.air) {
                        try {
                            System.out.println("[GhostBlock-DEBUG AutoPlace] Placing ghost block: " + ghostBlock.getRegistryName() + ":" + entryBelow.metadata); // Log 9
                            // 使用标记 2 (BLOCK_UPDATE_FLAG) 尝试仅通知客户端，避免不必要的服务器交互或光照更新? (可以试试, 3更保险)
                            world.setBlockState(posBelow, ghostBlock.getStateFromMeta(entryBelow.metadata), 3); // 先用 3
                            // 可能需要手动触发渲染更新或光照更新
                            world.markBlockForUpdate(posBelow);
                            world.checkLightFor(EnumSkyBlock.BLOCK, posBelow);
                            world.checkLightFor(EnumSkyBlock.SKY, posBelow);
                            // world.markBlockRangeForRenderUpdate(posBelow, posBelow); // 强制重绘

                            System.out.println("[GhostBlock-DEBUG AutoPlace] Successfully placed ghost block at " + posBelow + ". Returning."); // Log 10
                            lastTrackedDimension = currentDim; // 更新跟踪维度
                            return; // <<<--- 跳过后续处理

                        } catch (Exception e) {
                            System.err.println("[GhostBlock-ERROR AutoPlace] Exception during ghost block placement: " + posBelow); // Log 11
                            e.printStackTrace();
                        }
                    } else {
                        System.out.println("[GhostBlock-DEBUG AutoPlace] Ghost block from entry is invalid or air. Won't place."); // Log 12
                    }
                } else {
                    System.out.println("[GhostBlock-DEBUG AutoPlace] Pos below is not Air (" + currentState.getBlock().getRegistryName() + "). Won't place."); // Log 13
                }
            } else {
                System.out.println("[GhostBlock-DEBUG AutoPlace] No entry found for posBelow in auto clear file."); // Log 14
            }
            System.out.println("[GhostBlock-DEBUG AutoPlace] AutoPlace logic finished, continuing to normal join logic (if any)."); // Log 15
        }
        // --- 自动放置逻辑结束 ---


        // --- 原有的维度切换和首次加入逻辑 ---
        // (如果上面的自动放置逻辑执行成功并 return，则不会执行到这里)
        if (isFirstJoin) {
            System.out.println("[GhostBlock-DEBUG] 首次进入世界，初始化维度为 " + currentDim);
            lastTrackedDimension = currentDim;
            isFirstJoin = false;
            // 首次加入时，加载对应维度的自动清除文件并恢复（如果存在）
             System.out.println("[GhostBlock-DEBUG] Executing cleanupAndRestoreOnLoad for first join."); // Log 16
             cleanupAndRestoreOnLoad(world); // <- 正常执行恢复/清理
            return; // <- 首次加入后直接返回
        }

        if (lastTrackedDimension != currentDim) {
            System.out.println("[GhostBlock-DEBUG] 检测到维度变化: " + lastTrackedDimension + " → " + currentDim);

            // --- 清理旧维度的文件 ---
            String baseId = GhostBlockData.getWorldBaseIdentifier(world); // 使用当前世界获取基础ID
            // ... (删除旧文件逻辑) ...

            // 3. 取消所有正在运行的任务（因为维度变了）
            System.out.println("[GhostBlock-DEBUG] 维度切换，取消所有活动任务...");
            cancelAllTasks(player); // 传递玩家以便发送反馈

             // 4. 加载新维度的自动清除文件并恢复（如果存在）
             System.out.println("[GhostBlock-DEBUG] Executing cleanupAndRestoreOnLoad after dimension change."); // Log 17
             cleanupAndRestoreOnLoad(world); // <- 正常执行恢复/清理

        } else {
            // 维度未变化，但可能是重进服务器/单人游戏
            System.out.println("[GhostBlock-DEBUG] 重新加入相同维度 (" + currentDim + ")，检查并恢复...");
            System.out.println("[GhostBlock-DEBUG] Executing cleanupAndRestoreOnLoad after rejoin same dimension."); // Log 18
            cleanupAndRestoreOnLoad(world); // 同样需要清理恢复
        }

        lastTrackedDimension = currentDim; // 更新跟踪的维度
    }

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

        // --- 新增：处理 help 子命令 ---
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
            BlockPos pos = CommandBase.parseBlockPos(sender, args, 1, false);
            BlockStateProxy state = parseBlockState(args[4]);
            Block block = Block.getBlockById(state.blockId);
            if (block == null || block == Blocks.air) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
            }

            // 解析可选参数
            String saveFileName = null;
            boolean saveToFile = false;
            boolean userProvidedSave = false; // 新增：标记用户是否输入了 -s
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

            // 新增：检查自动保存配置 (仅当用户未指定 -s 时)
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
            BlockPos from = CommandBase.parseBlockPos(sender, args, 1, false);
            BlockPos to = CommandBase.parseBlockPos(sender, args, 4, false);
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

            // 新增：检查自动保存配置 (仅当用户未指定 -s 时)
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
                        sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "内部错误：同步放置方块时出错，请检查日志。 Pos: " + pos));
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

            // --- 参数解析 (保持不变) ---
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

            // --- 文件名决策逻辑 (保持不变) ---
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


            // --- 新增: 检查是否需要隐式批处理 ---
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
                         sender.addChatMessage(formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.implicit_batch_notice")); // 新增语言键
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

            // === 新增：备份要删除的文件内容 ===
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
        sender.addChatMessage(new ChatComponentText(hl + "--- GhostBlock 命令帮助 (/cgb) ---"));

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
    private String getAutoClearFileName(WorldClient world) {
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
            // 使用 LangUtil 获取翻译
            new ChatComponentText("[" + LangUtil.translate("ghostblock.commands.clear.confirm.button") + "]")
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
                 // 使用 LangUtil 获取翻译
                new ChatComponentText("[" + LangUtil.translate("ghostblock.commands.clear.block.confirm.button") + "]") // 确认按钮文字
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
        message.appendSibling(new ChatComponentText("[GhostBlock] ")
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
        ChatComponentText prefix = new ChatComponentText("[GhostBlock] ");
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
        final ICommandSender sender;
        private final int totalBlocks;
        private int processedCount = 0;
        private long lastUpdateTime = 0;
        private float lastReportedPercent = -1;
        private volatile boolean cancelled = false;
        private final int taskId;
        private final List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile;
        private final ChunkProviderClient chunkProvider;
        // 新增: 跟踪哪些位置之前是未加载的
        private final Set<BlockPos> previouslyUnloaded = new HashSet<>();

        // ... (构造函数保持不变)
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
            this.sender = sender;
            this.taskId = taskId;
            this.entriesToSaveForUserFile = entriesToSaveForUserFile != null ? new ArrayList<>(entriesToSaveForUserFile) : new ArrayList<>();
            this.processedCount = 0;
            this.chunkProvider = (ChunkProviderClient) world.getChunkProvider();
            System.out.println("[GhostBlock FillTask #" + taskId + "] 初始化: total=" + this.totalBlocks + ", batch=" + this.batchSize + ", save=" + saveToFile + ", userEntries=" + this.entriesToSaveForUserFile.size());
        }


        boolean processBatch() {
            if (cancelled) {
                // System.out.println("[GhostBlock FillTask #" + taskId + "] 已取消，停止处理。"); // 可选日志
                return true; // 任务结束（已取消）
            }

            Block block = Block.getBlockById(state.blockId);
            if (block == null || block == Blocks.air) { // 也阻止填充空气
                if (processedCount == 0 && !cancelled) { // 避免重复报错或取消后报错
                    sender.addChatMessage(formatMessage(EnumChatFormatting.RED, "ghostblock.commands.error.invalid_block"));
                    System.err.println("[GhostBlock FillTask #" + taskId + "] 错误：无效的方块 ID " + state.blockId + " 或尝试填充空气。任务取消。");
                    this.cancel(); // 取消任务
                }
                return true;   // 任务结束
            }

            int attemptsThisTick = 0;
            int successfullyProcessedThisTick = 0;
            Iterator<BlockPos> iterator = remainingBlocks.iterator();

            while (iterator.hasNext() && attemptsThisTick < batchSize) {
                 if (cancelled) {
                     break;
                 }
                BlockPos pos = iterator.next();
                attemptsThisTick++;

                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;
                boolean sectionIsReady = false;
                boolean chunkExists = this.chunkProvider.chunkExists(chunkX, chunkZ);

                if (chunkExists) {
                    Chunk chunk = this.chunkProvider.provideChunk(chunkX, chunkZ);
                    int storageY = pos.getY() >> 4;
                    if (pos.getY() >= 0 && pos.getY() < 256 && storageY >= 0 && storageY < chunk.getBlockStorageArray().length) {
                        if (chunk.getBlockStorageArray()[storageY] != null) {
                            sectionIsReady = true;
                        }
                    }
                }

                if (sectionIsReady) {
                    // *** 新增日志：如果之前未加载，现在加载了 ***
                    if (previouslyUnloaded.contains(pos)) {
                        System.out.println("[GhostBlock FillTask #" + taskId + "] 区块已就绪 (之前未就绪): " + pos + ". 尝试放置...");
                        previouslyUnloaded.remove(pos); // 不再跟踪
                    }

                    try {
                        if (world.isRemote) {
                            IBlockState blockStateToSet = block.getStateFromMeta(state.metadata);
                            // 使用标志 3: 更新方块，通知邻居，并尝试重新计算光照
                            world.setBlockState(pos, blockStateToSet, 3);
                            // 可选：强制渲染更新（通常不需要）
                            // world.markBlockRangeForRenderUpdate(pos, pos);
                        } else {
                            System.err.println("[GhostBlock FillTask #" + taskId + " WARN] 尝试在非客户端世界执行任务！Pos: " + pos);
                        }

                        iterator.remove(); // 处理成功，从列表移除
                        successfullyProcessedThisTick++;
                        processedCount++;

                    } catch (Exception e) {
                        System.err.println("[GhostBlock FillTask #" + taskId + " WARN] 在已加载区块设置方块时出错 " + pos + ": " + e.getMessage());
                        e.printStackTrace();
                        iterator.remove(); // 即使失败也移除，避免死循环
                        // 失败的不计入 processedCount
                    }
                } else {
                    // 区块部分未就绪
                    // *** 新增日志：记录未加载状态 (只记录一次) ***
                    if (!previouslyUnloaded.contains(pos)) {
                        System.out.println("[GhostBlock FillTask #" + taskId + "] 区块未就绪，稍后重试: " + pos + " (ChunkExists=" + chunkExists + ")");
                        previouslyUnloaded.add(pos);
                    }
                    // 不移除，不增加 processedCount，等待下次 tick
                }
            } // End of while loop

            if (cancelled) {
                 // System.out.println("[GhostBlock FillTask #" + taskId + "] 在批处理中检测到取消。"); // 可选日志
                return true;
            }

            boolean finished = remainingBlocks.isEmpty();

            if (finished) {
                System.out.println("[GhostBlock FillTask #" + taskId + "] 任务完成。成功放置: " + processedCount + " / 初始总数: " + totalBlocks);
                if (!previouslyUnloaded.isEmpty()) {
                    System.out.println("[GhostBlock FillTask #" + taskId + "] 注意: 任务完成时仍有 " + previouslyUnloaded.size() + " 个方块记录为从未加载过。");
                }
                sendFinalProgress();
            } else {
                float currentPercent = (totalBlocks == 0) ? 100.0f : (processedCount * 100.0f) / totalBlocks;
                boolean forceUpdate = successfullyProcessedThisTick > 0;
                sendProgressIfNeeded(currentPercent, forceUpdate);
                 // 可选调试日志：显示剩余数量
                 // if (attemptsThisTick > 0 && successfullyProcessedThisTick == 0) { // 如果尝试了但没成功放置
                 //     System.out.println("[GhostBlock FillTask #" + taskId + "] Tick: 尝试 " + attemptsThisTick + ", 成功 " + successfullyProcessedThisTick + ", 剩余 " + remainingBlocks.size());
                 // }
            }
            return finished;
        }

        // ... (sendProgressIfNeeded, sendFinalProgress, cancel, getTaskId 方法保持不变)
        // 根据需要发送进度更新消息
        private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
            // 避免 totalBlocks 为 0 导致 NaN 或 Infinity
             if (totalBlocks == 0) {
                 currentPercent = 100.0f;
             } else {
                currentPercent = (processedCount * 100.0f) / totalBlocks;
             }
             currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent)); // 保证在 0-100 之间
             currentPercent = Math.round(currentPercent * 10) / 10.0f; // 保留一位小数

            boolean progressChanged = Math.abs(currentPercent - lastReportedPercent) >= 0.1f;
            // 缩短超时时间，让单方块任务也能快速显示进度
            boolean timeout = System.currentTimeMillis() - lastUpdateTime > (totalBlocks > 1 ? 1000 : 100); // 多方块1秒，单方块0.1秒
            boolean shouldSend = forceSend || progressChanged || timeout;

            if (shouldSend && currentPercent <= 100.0f) {
                 //currentPercent = Math.min(currentPercent, 100.0f); // 已在前面处理
                String progressBar = createProgressBar(currentPercent, 10); // 10 段进度条
                IChatComponent message = GhostBlockCommand.createProgressMessage(
                    "ghostblock.commands.fill.progress", // 使用填充进度翻译键
                    (int) Math.floor(currentPercent), // 传递整数百分比（向下取整）
                    progressBar
                );
                // 只有在强制或百分比确实变化时才发送，避免超时重复发送相同进度
                if (forceSend || currentPercent != lastReportedPercent) {
                     // 确保 sender 仍然有效 (例如玩家未退出)
                     if (sender instanceof EntityPlayer) {
                         // 检查玩家实体是否存在于当前世界
                         EntityPlayer player = (EntityPlayer) sender;
                         if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                             System.out.println("[GhostBlock FillTask #" + taskId + "] 玩家已离开或无效，停止发送进度消息。");
                             this.cancel(); // 如果玩家不在了，可以考虑取消任务
                             return;
                         }
                     }
                     try {
                        sender.addChatMessage(message); // 发送消息
                     } catch (Exception e) {
                        System.err.println("[GhostBlock FillTask #" + taskId + "] 发送进度消息失败: " + e.getMessage());
                     }
                    lastReportedPercent = currentPercent; // 更新上次报告的百分比
                }
                lastUpdateTime = System.currentTimeMillis(); // 总是更新上次尝试发送的时间
            }
        }

        // 发送最终进度、执行保存并发送完成消息 (使用 processedCount)
        private void sendFinalProgress() {
             // 确保最终进度显示为 100%，即使计算有误差
             if (lastReportedPercent < 100.0f && !cancelled) {
                sendProgressIfNeeded(100.0f, true); // 强制发送 100%
             }

            // 保存到用户文件逻辑 (保持不变)
            if (saveToFile && !cancelled) {
                String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                 if (this.entriesToSaveForUserFile != null && !this.entriesToSaveForUserFile.isEmpty()) {
                    System.out.println("[GhostBlock FillTask #" + taskId + "] 任务完成，尝试保存 " + this.entriesToSaveForUserFile.size() + " 个条目到用户文件: " + actualSaveFileName);
                    GhostBlockData.saveData(world, this.entriesToSaveForUserFile, actualSaveFileName, false); // 合并模式
                    String displayName = (saveFileName == null) ?
                        LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world))
                        : saveFileName;
                     // 只有在实际保存了内容后才发送成功消息
                     // 确保 sender 仍然有效
                     if (sender instanceof EntityPlayer) {
                         EntityPlayer player = (EntityPlayer) sender;
                         if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                              System.out.println("[GhostBlock FillTask #" + taskId + "] 玩家已离开或无效，跳过发送保存成功消息。");
                              return; // 玩家退出则不发
                         }
                     }
                     try {
                        sender.addChatMessage(formatMessage(EnumChatFormatting.GREEN,
                            "ghostblock.commands.save.success", displayName));
                     } catch (Exception e) {
                         System.err.println("[GhostBlock FillTask #" + taskId + "] 发送保存成功消息失败: " + e.getMessage());
                     }
                 } else {
                     System.out.println("[GhostBlock FillTask #" + taskId + "] WARN: 任务标记为保存，但没有提供或生成用户文件条目。");
                 }
            }

            // 发送任务完成消息，仅在未取消时
            if (!cancelled) {
                 // 使用实际成功放置的数量 (processedCount)
                 // 确保 sender 仍然有效
                 if (sender instanceof EntityPlayer) {
                     EntityPlayer player = (EntityPlayer) sender;
                     if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                          System.out.println("[GhostBlock FillTask #" + taskId + "] 玩家已离开或无效，跳过发送完成消息。");
                          return; // 玩家退出则不发
                     }
                 }
                 try {
                    // 根据 totalBlocks 判断是 set 还是 fill 的完成消息
                    String finishKey = (totalBlocks == 1) ? "ghostblock.commands.fill.finish_single" : "ghostblock.commands.fill.finish";
                    sender.addChatMessage(formatMessage(FINISH_COLOR, finishKey, processedCount));

                 } catch (Exception e) {
                     System.err.println("[GhostBlock FillTask #" + taskId + "] 发送完成消息失败: " + e.getMessage());
                 }
            } else {
                 System.out.println("[GhostBlock FillTask #" + taskId + "] 任务已被取消，不发送完成消息。");
            }
        }

        // 标记任务为取消
        public void cancel() {
            if (!this.cancelled) { // 避免重复打印日志
                 System.out.println("[GhostBlock FillTask #" + taskId + "] 标记为取消。");
                this.cancelled = true;
                 previouslyUnloaded.clear(); // 取消时清空跟踪集合
            }
        }
        // 获取任务 ID
        public int getTaskId() {
            return taskId;
        }

    }

    // 静态内部类：批量加载任务 (添加日志和确认逻辑)
    private static class LoadTask {
        private volatile boolean cancelled = false;
        private final WorldClient world;
        private final List<GhostBlockData.GhostBlockEntry> entries;
        private int currentIndex;
        private final int batchSize;
        private final ICommandSender sender;
        private long lastUpdateTime = 0;
        private float lastReportedPercent = -1;
        private final int taskId;
        private final ChunkProviderClient chunkProvider;
        // 新增: 跟踪哪些索引之前是未加载的
        private final Set<Integer> previouslyUnloadedIndices = new HashSet<>();


        public LoadTask(WorldClient world, List<GhostBlockData.GhostBlockEntry> entriesToLoad, int batchSize,
                    ICommandSender sender, int taskId) {
            this.world = world;
            // 防御性拷贝，确保列表可修改且独立
            this.entries = entriesToLoad != null ? new ArrayList<>(entriesToLoad) : new ArrayList<>();
            this.batchSize = Math.max(1, batchSize); // 保证 batchSize >= 1
            this.sender = sender;
            this.currentIndex = 0;
            this.taskId = taskId;
            this.chunkProvider = (ChunkProviderClient) world.getChunkProvider();
            System.out.println("[GhostBlock LoadTask #" + taskId + "] 初始化: total=" + this.entries.size() + ", batch=" + this.batchSize);
        }

        // 标记任务为取消
        public void cancel() {
             if (!this.cancelled) {
                 System.out.println("[GhostBlock LoadTask #" + taskId + "] 标记为取消。");
                 this.cancelled = true;
                 previouslyUnloadedIndices.clear(); // 清空跟踪
             }
        }

        boolean processBatch() {
            if (cancelled) {
                return true;
            }
            if (entries.isEmpty()) {
                return true; // 空任务直接完成
            }
            if (currentIndex >= entries.size()) { // 检查是否已处理完所有条目
                 // 确保在返回 true 前发送最终消息 (如果尚未发送)
                 if (lastReportedPercent < 100.0f && !cancelled) {
                      System.out.println("[GhostBlock Load Task #" + taskId + "] 所有条目处理完毕，发送最终进度。");
                      sendFinalProgress();
                 } else if (!cancelled) {
                      System.out.println("[GhostBlock Load Task #" + taskId + "] 任务已完成。");
                 }
                 return true;
            }

            int attemptsThisTick = 0;
            int successfullyProcessedThisTick = 0;

            // 使用一个临时的当前索引，避免在循环中直接修改 currentIndex 导致跳过检查
            int tempCurrentIndex = currentIndex;

            while (tempCurrentIndex < entries.size() && attemptsThisTick < batchSize) {
                 if (cancelled) {
                     break;
                 }
                GhostBlockData.GhostBlockEntry entry = entries.get(tempCurrentIndex);
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                attemptsThisTick++;

                int chunkX = pos.getX() >> 4;
                int chunkZ = pos.getZ() >> 4;
                boolean sectionIsReady = false;
                boolean chunkExists = this.chunkProvider.chunkExists(chunkX, chunkZ);

                if (chunkExists) {
                    Chunk chunk = this.chunkProvider.provideChunk(chunkX, chunkZ);
                    int storageY = pos.getY() >> 4;
                    if (pos.getY() >= 0 && pos.getY() < 256 && storageY >= 0 && storageY < chunk.getBlockStorageArray().length) {
                        if (chunk.getBlockStorageArray()[storageY] != null) {
                            sectionIsReady = true;
                        }
                    }
                }

                if (sectionIsReady) {
                    // *** 新增日志：如果之前未加载，现在加载了 ***
                    if (previouslyUnloadedIndices.contains(tempCurrentIndex)) {
                        System.out.println("[GhostBlock LoadTask #" + taskId + "] 区块已就绪 (之前未就绪): " + pos + " (Index: " + tempCurrentIndex + "). 尝试放置...");
                        previouslyUnloadedIndices.remove(tempCurrentIndex); // 不再跟踪
                    }

                    Block block = Block.getBlockFromName(entry.blockId);
                    boolean processedThisEntry = false; // 标记此索引是否应前进

                    if (block != null && block != Blocks.air) {
                        try {
                            if (world.isRemote) {
                                IBlockState blockStateToSet = block.getStateFromMeta(entry.metadata);
                                // 使用标志 3
                                world.setBlockState(pos, blockStateToSet, 3);
                                // 可选: 强制渲染更新
                                // world.markBlockRangeForRenderUpdate(pos, pos);
                            } else {
                                System.err.println("[GhostBlock LoadTask #" + taskId + " WARN] 尝试在非客户端世界执行任务！Pos: " + pos);
                            }

                            successfullyProcessedThisTick++;
                            processedThisEntry = true; // 标记成功处理
                        } catch (Exception e) {
                            System.err.println("[GhostBlock LoadTask #" + taskId + " WARN] 在已加载区块设置方块时出错 " + pos + " (Index: " + tempCurrentIndex + "): " + e.getMessage());
                            e.printStackTrace();
                            processedThisEntry = true; // 出错也标记处理，避免重试循环
                        }
                    } else {
                        System.out.println("[GhostBlock LoadTask #" + taskId + " WARN] 加载时发现无效方块ID '" + entry.blockId + "' 或空气方块，位于 " + pos + " (Index: " + tempCurrentIndex + "). 跳过.");
                        processedThisEntry = true; // 跳过无效方块，标记处理
                    }

                    // 如果此条目被处理（成功、失败或跳过），则前进到下一个索引
                    if (processedThisEntry) {
                        currentIndex = tempCurrentIndex + 1; // *** 关键: 更新真实的 currentIndex ***
                    }
                     tempCurrentIndex++; // 无论如何都尝试检查下一个 (因为我们只增加了 currentIndex)

                } else {
                    // 区块部分未就绪 - 跳过，不前进真实 currentIndex
                     // *** 新增日志：记录未加载状态 (只记录一次) ***
                     if (!previouslyUnloadedIndices.contains(tempCurrentIndex)) {
                         System.out.println("[GhostBlock LoadTask #" + taskId + "] 区块未就绪，稍后重试: " + pos + " (Index: " + tempCurrentIndex + ", ChunkExists=" + chunkExists + ")");
                         previouslyUnloadedIndices.add(tempCurrentIndex);
                     }
                      tempCurrentIndex++; // 尝试检查下一个位置
                }
            } // End of while loop

            if (cancelled) {
                return true;
            }

            // 再次检查是否处理完所有条目
             boolean finished = currentIndex >= entries.size();

            if (finished) {
                 // 确保发送最终进度
                 if (lastReportedPercent < 100.0f) {
                     System.out.println("[GhostBlock Load Task #" + taskId + "] 所有条目处理完毕 (循环结束)，发送最终进度。");
                     sendFinalProgress();
                 } else {
                      System.out.println("[GhostBlock Load Task #" + taskId + "] 任务已完成 (循环结束)。");
                 }

            } else {
                // 更新进度 (基于 currentIndex)
                float currentPercent = entries.isEmpty() ? 100.0f : (currentIndex * 100.0f) / entries.size();
                boolean forceUpdate = successfullyProcessedThisTick > 0;
                sendProgressIfNeeded(currentPercent, forceUpdate);
                 // 可选调试日志
                 // if (attemptsThisTick > 0 && successfullyProcessedThisTick == 0) {
                 //    System.out.println("[GhostBlock LoadTask #" + taskId + "] Tick: 尝试 " + attemptsThisTick + ", 成功 " + successfullyProcessedThisTick + ", 下次索引 " + currentIndex + "/" + entries.size());
                 // }
            }
            return finished;
        }


        // ... (sendProgressIfNeeded, sendFinalProgress 方法保持不变)
        // 根据需要发送进度更新消息
        private void sendProgressIfNeeded(float currentPercent, boolean forceSend) {
            // 使用 currentIndex 计算进度
             if (entries.isEmpty()) {
                 currentPercent = 100.0f;
             } else {
                currentPercent = (currentIndex * 100.0f) / entries.size();
             }
             currentPercent = Math.min(100.0f, Math.max(0.0f, currentPercent)); // 保证 0-100
             currentPercent = Math.round(currentPercent * 10) / 10.0f; // 保留一位小数


            boolean progressChanged = Math.abs(currentPercent - lastReportedPercent) >= 0.1f;
            boolean timeout = System.currentTimeMillis() - lastUpdateTime > 1000; // 1秒超时
            boolean shouldSend = forceSend || progressChanged || timeout;


            if (shouldSend && currentPercent <= 100.0f) {
                String progressBar = createProgressBar(currentPercent, 10); // 10 段进度条
                IChatComponent message = GhostBlockCommand.createProgressMessage(
                    "ghostblock.commands.load.progress", // 使用加载进度翻译键
                    (int) Math.floor(currentPercent), // 整数百分比 (向下取整)
                    progressBar
                );
                // 避免在进度没有实际变化时因为超时而重复发送相同的百分比
                // 仅当强制发送或百分比确实变化时才发送
                if (forceSend || currentPercent != lastReportedPercent) {
                      // 确保 sender 仍然有效
                     if (sender instanceof EntityPlayer) {
                         EntityPlayer player = (EntityPlayer) sender;
                         if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                              System.out.println("[GhostBlock LoadTask #" + taskId + "] 玩家已离开或无效，停止发送进度消息。");
                              this.cancel(); // 取消任务
                              return;
                         }
                     }
                    try {
                         sender.addChatMessage(message); // 发送消息
                    } catch (Exception e) {
                         System.err.println("[GhostBlock LoadTask #" + taskId + "] 发送进度消息失败: " + e.getMessage());
                    }
                    lastReportedPercent = currentPercent; // 更新上次报告的百分比
                }
                lastUpdateTime = System.currentTimeMillis(); // 总是更新上次尝试发送的时间
            }
        }

        // 发送最终进度和完成消息
        private void sendFinalProgress() {
             // 确保最终发送 100%
             if (lastReportedPercent < 100.0f && !cancelled) {
                sendProgressIfNeeded(100.0f, true); // 强制发送 100%
             }

            // 发送完成消息，仅在未取消时
            if (!cancelled) {
                 // 确保 sender 仍然有效
                 if (sender instanceof EntityPlayer) {
                     EntityPlayer player = (EntityPlayer) sender;
                     if (Minecraft.getMinecraft().theWorld == null || !player.isEntityAlive() || player.worldObj != Minecraft.getMinecraft().theWorld) {
                          System.out.println("[GhostBlock LoadTask #" + taskId + "] 玩家已离开或无效，跳过发送完成消息。");
                          return; // 玩家退出则不发
                     }
                 }
                 try {
                      // 使用总条目数
                     sender.addChatMessage(formatMessage(FINISH_COLOR, "ghostblock.commands.load.finish", entries.size()));
                 } catch (Exception e) {
                      System.err.println("[GhostBlock LoadTask #" + taskId + "] 发送完成消息失败: " + e.getMessage());
                 }

                 if (!previouslyUnloadedIndices.isEmpty()) {
                    System.out.println("[GhostBlock LoadTask #" + taskId + "] 注意: 任务完成时仍有 " + previouslyUnloadedIndices.size() + " 个索引记录为从未加载过。");
                 }
            } else {
                  System.out.println("[GhostBlock LoadTask #" + taskId + "] 任务已被取消，不发送完成消息。");
            }
        }
        // 获取任务 ID
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

    // 封装世界加载时的清理和恢复逻辑
    private void cleanupAndRestoreOnLoad(WorldClient world) {
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
        if (!event.world.isRemote) {
            return; // 只关心客户端世界卸载
        }
        World unloadedWorld = event.world;
        if (unloadedWorld == null) {
            return;
        }
        // --- 清理与卸载的世界相关的文件 ---
         String baseId = GhostBlockData.getWorldBaseIdentifier(unloadedWorld); // 获取基础ID
         int unloadedDim = unloadedWorld.provider.getDimensionId(); // 获取维度ID
         System.out.println("[GhostBlock] 正在卸载世界: " + baseId + " Dim: " + unloadedDim);

         // 1. 删除该维度的 clear 文件
         File autoFile = new File( GhostBlockData.SAVES_DIR, "clear_" + baseId + "_dim_" + unloadedDim + ".json");
         if (autoFile.exists()) {
             boolean deleted = autoFile.delete();
             System.out.println("卸载世界时删除 clear 文件 (" + autoFile.getName() + ") 结果: " + deleted);
         }

         // 2. 删除该维度的 undo 文件
         File[] undoFiles = new File(GhostBlockData.SAVES_DIR).listFiles((dir, name) -> name.startsWith("undo_" + baseId + "_dim_" + unloadedDim + "_") && name.endsWith(".json"));
         if (undoFiles != null) {
             for (File file : undoFiles) {
                 boolean deleted = file.delete();
                 System.out.println("卸载世界时删除 undo 文件: " + file.getName() + " 结果: " + deleted);
             }
         }

        // --- 取消所有活动任务 ---
        System.out.println("[GhostBlock] 世界卸载，取消所有活动任务...");
        ICommandSender feedbackSender = Minecraft.getMinecraft().thePlayer;
        cancelAllTasks(feedbackSender); // 调用辅助方法取消所有任务

        // 重置首次加入标志，以便下次进入新世界时正确处理
        isFirstJoin = true;
        System.out.println("[GhostBlock] 重置 isFirstJoin 标志。");
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