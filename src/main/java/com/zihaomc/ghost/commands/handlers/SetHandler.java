package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.BlockStateProxy;
import com.zihaomc.ghost.commands.data.CommandState.UndoRecord;
import com.zihaomc.ghost.commands.tasks.FillTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 处理 /cgb set 子命令的逻辑。
 */
public class SetHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        // 用法检查: set x y z block [-s [filename]]
        if (args.length < 5) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.set.usage"));
        }
        
        // 1. 解析参数
        BlockPos pos = CommandHelper.parseBlockPosLegacy(sender, args, 1);
        BlockStateProxy state = CommandHelper.parseBlockState(args[4]);
        Block block = Block.getBlockById(state.blockId);
        if (block == null) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
        }

        // 2. 解析可选参数
        String saveFileName = null;
        boolean saveToFile = false;
        boolean userProvidedSave = false;
        if (args.length > 5) {
            if (args[5].equalsIgnoreCase("-s") || args[5].equalsIgnoreCase("--save")) {
                userProvidedSave = true;
                saveToFile = true;
                if (args.length > 6) {
                    saveFileName = args[6];
                    if ("filename".equalsIgnoreCase(saveFileName) || saveFileName.trim().isEmpty()) {
                        saveFileName = null;
                    }
                }
                if (args.length > 7) {
                    throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.set.usage"));
                }
            } else {
                throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.set.usage"));
            }
        }

        // 3. 检查自动保存配置
        if (!userProvidedSave && GhostConfig.enableAutoSave) {
            saveToFile = true;
            saveFileName = GhostConfig.defaultSaveFileName;
            if (saveFileName == null || saveFileName.trim().isEmpty() || saveFileName.equalsIgnoreCase("default")) {
                saveFileName = null;
            }
            LogUtil.debug("log.debug.set.autosave.activated", (saveFileName == null ? LangUtil.translate("log.debug.set.autosave.defaultFile") : saveFileName));
        }

        List<BlockPos> positions = Collections.singletonList(pos);

        // 4. 收集原始方块 & 自动保存 (用于 clear block 和 undo)
        List<GhostBlockEntry> autoEntries = collectOriginalBlocks(world, positions, state);
        if (!autoEntries.isEmpty()) {
            GhostBlockData.saveData(world, autoEntries, CommandHelper.getAutoClearFileName(world), false);
            LogUtil.debug("log.debug.set.autosaved.count", autoEntries.size());
        }

        // 5. 准备撤销记录
        Map<String, List<GhostBlockEntry>> fileBackups = new HashMap<>();
        String actualSaveFileNameForBackup = null;
        if (saveToFile) {
            actualSaveFileNameForBackup = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
            List<GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(actualSaveFileNameForBackup));
            fileBackups.put(actualSaveFileNameForBackup, existingEntries);
        }

        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        GhostBlockData.saveData(world, autoEntries, undoFileName, true);
        CommandState.undoHistory.push(new UndoRecord(undoFileName, fileBackups, UndoRecord.OperationType.SET));
        LogUtil.debug("log.debug.set.undo.pushed");

        // 6. 检查区块加载状态
        boolean sectionIsReady = CommandHelper.isBlockSectionReady(world, pos);

        // 7. 执行设置
        if (sectionIsReady) {
            // 区块已加载：直接放置
            LogUtil.debug("log.debug.set.directSet");

            if (saveToFile) {
                String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                IBlockState currentOriginalState = world.getBlockState(pos);
                Block currentOriginalBlock = currentOriginalState.getBlock();
                List<GhostBlockEntry> userEntryToSave = Collections.singletonList(
                        new GhostBlockEntry(pos, block.getRegistryName().toString(), state.metadata,
                                currentOriginalBlock.getRegistryName().toString(), currentOriginalBlock.getMetaFromState(currentOriginalState)));
                GhostBlockData.saveData(world, userEntryToSave, actualSaveFileName, false);
                String displayName = (saveFileName == null) ? LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world)) : saveFileName;
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.save.success", displayName));
            }
            CommandHelper.setGhostBlock(world, pos, state);
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.cghostblock.set.success", pos.getX(), pos.getY(), pos.getZ()));

        } else {
            // 区块未加载：创建任务
            LogUtil.debug("log.debug.set.creating.task");
            int taskId = CommandState.taskIdCounter.incrementAndGet();

            List<GhostBlockEntry> entryForTaskSave = new ArrayList<>();
            if (saveToFile && !autoEntries.isEmpty()) {
                entryForTaskSave.add(autoEntries.get(0));
            }

            FillTask task = new FillTask(world, state, positions, 1, saveToFile, saveFileName, sender, taskId, entryForTaskSave);
            CommandState.activeFillTasks.add(task);

            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.cghostblock.set.deferred", pos.getX(), pos.getY(), pos.getZ()));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.task.chunk_aware_notice"));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        int currentArgIndex = args.length - 1;

        if (currentArgIndex >= 1 && currentArgIndex <= 3) {
            return CommandBase.getListOfStringsMatchingLastWord(args, CommandHelper.getCoordinateSuggestions(sender, currentArgIndex - 1, Minecraft.getMinecraft().objectMouseOver != null ? Minecraft.getMinecraft().objectMouseOver.getBlockPos() : null));
        } else if (currentArgIndex == 4) {
            return CommandBase.getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
        } else if (currentArgIndex == 5) {
            if (!CommandHelper.hasFlag(args, "-s", "--save")) {
                return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("-s", "--save"));
            }
        } else if (currentArgIndex == 6) {
            String prevArg = args[currentArgIndex - 1];
            if (prevArg.equalsIgnoreCase("-s") || prevArg.equalsIgnoreCase("--save")) {
                List<String> suggestions = new ArrayList<>(CommandHelper.getAvailableFileNames());
                suggestions.add(0, "filename");
                return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
            }
        }
        return Collections.emptyList();
    }
    
    /**
     * 收集指定位置的原始方块信息，用于自动保存和撤销。
     */
    private List<GhostBlockEntry> collectOriginalBlocks(WorldClient world, List<BlockPos> blocks, BlockStateProxy state) {
        List<GhostBlockEntry> entries = new ArrayList<>();
        Block ghostBlock = (state != null) ? Block.getBlockById(state.blockId) : null;
        String ghostBlockId = (ghostBlock != null) ? ghostBlock.getRegistryName().toString() : "minecraft:air";
        int ghostMeta = (state != null) ? state.metadata : 0;

        String autoFileName = CommandHelper.getAutoClearFileName(world);
        List<GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
        Set<String> existingKeys = existingEntries.stream().map(e -> e.x + "," + e.y + "," + e.z).collect(Collectors.toSet());

        for (BlockPos pos : blocks) {
            String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (existingKeys.contains(key)) continue;

            IBlockState originalState = world.getBlockState(pos);
            Block originalBlock = originalState.getBlock();
            entries.add(new GhostBlockEntry(pos, ghostBlockId, ghostMeta,
                    originalBlock.getRegistryName().toString(), originalBlock.getMetaFromState(originalState)));
        }
        return entries;
    }
}