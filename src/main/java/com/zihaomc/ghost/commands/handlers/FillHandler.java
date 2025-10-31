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
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.BlockPos;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 处理 /cgb fill 子命令的逻辑。
 */
public class FillHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (args.length < 8) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.fill.usage"));
        }
        
        BlockPos from = CommandHelper.parseBlockPosLegacy(sender, args, 1);
        BlockPos to = CommandHelper.parseBlockPosLegacy(sender, args, 4);
        BlockStateProxy state = CommandHelper.parseBlockState(args[7]);
        Block block = Block.getBlockById(state.blockId);

        // 允许使用 minecraft:air。
        if (block == null) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
        }

        boolean configForceBatch = GhostConfig.FillCommand.alwaysBatchFill;
        int configForcedSize = GhostConfig.FillCommand.forcedBatchSize;

        boolean useBatch = configForceBatch;
        int batchSize = 100;
        boolean userProvidedBatchFlag = false;
        boolean userProvidedBatchSize = false;

        boolean saveToFile = false;
        String saveFileName = null;
        boolean userProvidedSave = false;

        for (int i = 8; i < args.length; ) {
            String flag = args[i].toLowerCase();
            if (flag.equals("-b") || flag.equals("--batch")) {
                userProvidedBatchFlag = true;
                useBatch = true;
                i++;
                if (i < args.length && CommandHelper.isNumber(args[i])) {
                    try {
                        batchSize = Integer.parseInt(args[i]);
                        CommandHelper.validateBatchSize(batchSize);
                        userProvidedBatchSize = true;
                        i++;
                    } catch (NumberFormatException | CommandException e) {
                        throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_batch_size"));
                    }
                }
            } else if (flag.equals("-s") || flag.equals("--save")) {
                userProvidedSave = true;
                saveToFile = true;
                i++;
                if (i < args.length && !args[i].startsWith("-")) {
                    saveFileName = args[i];
                    if ("filename".equalsIgnoreCase(saveFileName) || saveFileName.trim().isEmpty()) {
                        saveFileName = null;
                    }
                    i++;
                } else {
                    saveFileName = null;
                }
            } else {
                throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.fill.usage"));
            }
        }

        if (useBatch && !userProvidedBatchSize) {
            batchSize = (configForceBatch && configForcedSize > 0) ? configForcedSize : 100;
        }
        if (!userProvidedSave && GhostConfig.SaveOptions.enableAutoSave) {
            saveToFile = true;
            saveFileName = GhostConfig.SaveOptions.defaultSaveFileName;
            if (saveFileName == null || saveFileName.trim().isEmpty() || saveFileName.equalsIgnoreCase("default")) {
                saveFileName = null;
            }
        }

        List<BlockPos> allBlocks = new ArrayList<>();
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > Integer.MAX_VALUE) {
            LogUtil.warn("log.warn.fill.largeVolume", volume);
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    allBlocks.add(new BlockPos(x, y, z));
                }
            }
        }

        if (allBlocks.isEmpty()) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.fill.empty_area"));
            return;
        }

        List<GhostBlockEntry> autoEntries = collectOriginalBlocks(world, allBlocks, state);
        if (!autoEntries.isEmpty()) {
            GhostBlockData.saveData(world, autoEntries, CommandHelper.getAutoClearFileName(world), false);
        }

        boolean implicitBatchRequired = false;
        if (!useBatch && !allBlocks.isEmpty() && volume < 32768) {
            for (BlockPos pos : allBlocks) {
                if (!CommandHelper.isBlockSectionReady(world, pos)) {
                    implicitBatchRequired = true;
                    if (!userProvidedBatchFlag) {
                        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.fill.implicit_batch_notice"));
                    }
                    break;
                }
            }
        } else if (volume >= 32768) {
            implicitBatchRequired = true;
            if (!useBatch) useBatch = true;
        }
        
        Integer taskId = (useBatch || implicitBatchRequired) ? CommandState.taskIdCounter.incrementAndGet() : null;

        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        GhostBlockData.saveData(world, autoEntries, undoFileName, true);
        Map<String, List<GhostBlockEntry>> fileBackups = new HashMap<>();
        if (saveToFile) {
            String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
            List<GhostBlockEntry> existingEntries = GhostBlockData.loadData(world, Collections.singletonList(actualSaveFileName));
            fileBackups.put(actualSaveFileName, existingEntries);
        }
        
        CommandState.undoHistory.push(new UndoRecord(undoFileName, fileBackups, UndoRecord.OperationType.SET, taskId));

        if (taskId != null) {
            FillTask task = new FillTask(world, state, allBlocks, batchSize, saveToFile, saveFileName, sender, taskId, autoEntries);
            CommandState.activeFillTasks.add(task);
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.fill.batch_started", taskId, allBlocks.size(), batchSize));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.task.chunk_aware_notice"));
        } else {
            int count = 0;
            for(BlockPos pos : allBlocks) {
                CommandHelper.setGhostBlock(world, pos, state);
                count++;
            }
            if (saveToFile) {
                String actualSaveFileName = (saveFileName == null) ? GhostBlockData.getWorldIdentifier(world) : saveFileName;
                if (!autoEntries.isEmpty()) {
                    GhostBlockData.saveData(world, autoEntries, actualSaveFileName, false);
                    String displayName = (saveFileName == null) ? LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world)) : saveFileName;
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.save.success", displayName));
                }
            }
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.cghostblock.fill.success", count));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        int currentArgIndex = args.length - 1;
        if (currentArgIndex >= 1 && currentArgIndex <= 3) {
            return CommandBase.getListOfStringsMatchingLastWord(args, CommandHelper.getCoordinateSuggestions(sender, currentArgIndex - 1, Minecraft.getMinecraft().objectMouseOver != null ? Minecraft.getMinecraft().objectMouseOver.getBlockPos() : null));
        } else if (currentArgIndex >= 4 && currentArgIndex <= 6) {
            return CommandBase.getListOfStringsMatchingLastWord(args, CommandHelper.getCoordinateSuggestions(sender, currentArgIndex - 4, Minecraft.getMinecraft().objectMouseOver != null ? Minecraft.getMinecraft().objectMouseOver.getBlockPos() : null));
        } else if (currentArgIndex == 7) {
            return CommandBase.getListOfStringsMatchingLastWord(args, Block.blockRegistry.getKeys());
        } else if (currentArgIndex >= 8) {
            String prevArg = args[currentArgIndex - 1].toLowerCase();
            String prefix = args[currentArgIndex].toLowerCase();
            if (prevArg.equals("-s") || prevArg.equals("--save")) {
                List<String> suggestions = new ArrayList<>(CommandHelper.getAvailableFileNames());
                suggestions.add(0, "filename");
                return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
            }
            if (prevArg.equals("-b") || prevArg.equals("--batch")) {
                if (!CommandHelper.isNumber(prefix)) {
                    return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("100", "500", "1000"));
                }
            }
            List<String> suggestions = new ArrayList<>();
            if (!CommandHelper.hasFlag(args, "-b", "--batch") && !(prevArg.equals("-b") || prevArg.equals("--batch"))) {
                suggestions.add("-b");
            }
            if (!CommandHelper.hasFlag(args, "-s", "--save") && !(prevArg.equals("-s") || prevArg.equals("--save"))) {
                suggestions.add("-s");
            }
            return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
        }
        return Collections.emptyList();
    }
    
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