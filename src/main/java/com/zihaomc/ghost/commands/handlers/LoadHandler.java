package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.UndoRecord;
import com.zihaomc.ghost.commands.data.CommandState.BlockStateProxy;
import com.zihaomc.ghost.commands.tasks.LoadTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
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
 * 处理 /cgb load 子命令的逻辑。
 */
public class LoadHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        List<String> fileNames = new ArrayList<>();
        int loadBatchSize = 100;
        boolean useBatch = false;
        boolean explicitFilesProvided = false;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("-b") || arg.equalsIgnoreCase("--batch")) {
                useBatch = true;
                i++;
                if (i < args.length && CommandHelper.isNumber(args[i])) {
                    try {
                        loadBatchSize = Integer.parseInt(args[i]);
                        CommandHelper.validateBatchSize(loadBatchSize);
                    } catch (NumberFormatException | CommandException e) {
                        throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_batch_size"));
                    }
                } else {
                    i--;
                    loadBatchSize = 100;
                }
            } else if (!arg.startsWith("-")) {
                explicitFilesProvided = true;
                if (!arg.toLowerCase().startsWith("clear_") && !arg.toLowerCase().startsWith("undo_")) {
                    fileNames.add(arg);
                } else {
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.ignored_internal_file", arg));
                }
            } else {
                throw new WrongUsageException(LangUtil.translate("ghostblock.commands.cghostblock.load.usage"));
            }
        }

        if (!explicitFilesProvided || fileNames.isEmpty()) {
            String defaultFile = GhostBlockData.getWorldIdentifier(world);
            if (!defaultFile.toLowerCase().startsWith("clear_") && !defaultFile.toLowerCase().startsWith("undo_")) {
                fileNames.clear();
                fileNames.add(null);
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.load.using_default_file"));
            } else {
                throw new CommandException(LangUtil.translate("ghostblock.commands.load.error.default_is_internal"));
            }
        }

        List<GhostBlockEntry> entries = GhostBlockData.loadData(world, fileNames);
        if (entries.isEmpty()) {
            String fileDescription = (fileNames.contains(null)) ? LangUtil.translate("ghostblock.displayname.default_file", GhostBlockData.getWorldIdentifier(world)) : String.join(", ", fileNames.stream().filter(Objects::nonNull).collect(Collectors.toList()));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.empty_or_missing", fileDescription));
            return;
        }

        List<GhostBlockEntry> autoSaveEntries = collectOriginalBlocksForAutoSave(world, entries);
        if (!autoSaveEntries.isEmpty()) {
            GhostBlockData.saveData(world, autoSaveEntries, CommandHelper.getAutoClearFileName(world), false);
        }

        boolean implicitBatchRequired = false;
        if (!useBatch && !entries.isEmpty()) {
            for (GhostBlockEntry entry : entries) {
                if (!CommandHelper.isBlockSectionReady(world, new BlockPos(entry.x, entry.y, entry.z))) {
                    implicitBatchRequired = true;
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.implicit_batch_notice"));
                    break;
                }
            }
        }

        // [新增] 预先生成 TaskId
        Integer taskId = (useBatch || implicitBatchRequired) ? CommandState.taskIdCounter.incrementAndGet() : null;

        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        GhostBlockData.saveData(world, autoSaveEntries, undoFileName, true);
        
        // [修改] 传入 taskId
        CommandState.undoHistory.push(new UndoRecord(undoFileName, new HashMap<>(), UndoRecord.OperationType.SET, taskId));

        if (taskId != null) {
            int actualBatchSize = useBatch ? loadBatchSize : 100;
            CommandState.activeLoadTasks.add(new LoadTask(world, entries, actualBatchSize, sender, taskId));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.load.batch_started", taskId, entries.size(), actualBatchSize));
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.task.chunk_aware_notice"));
        } else {
            int successCount = 0;
            int failCount = 0;
            int skippedCount = 0;
            for (GhostBlockEntry entry : entries) {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                if (world.isBlockLoaded(pos)) {
                    Block block = Block.getBlockFromName(entry.blockId);
                    if (block != null && block != Block.getBlockFromName("minecraft:air")) {
                        try {
                            CommandHelper.setGhostBlock(world, pos, new BlockStateProxy(Block.getIdFromBlock(block), entry.metadata));
                            successCount++;
                        } catch (Exception e) {
                            failCount++;
                        }
                    } else {
                        failCount++;
                    }
                } else {
                    skippedCount++;
                }
            }
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.load.complete", successCount, entries.size()));
            if (failCount > 0) sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED, "ghostblock.commands.load.failed", failCount));
            if (skippedCount > 0) sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.task.sync_skipped", skippedCount));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        int currentArgIndex = args.length - 1;
        String prevArg = (currentArgIndex > 0) ? args[currentArgIndex - 1].toLowerCase() : "";
        String prefix = args[currentArgIndex].toLowerCase();

        if (prevArg.equals("-b") || prevArg.equals("--batch")) {
            if (!CommandHelper.isNumber(prefix)) {
                return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("100", "500", "1000"));
            }
        }

        List<String> suggestions = new ArrayList<>();
        List<String> allFiles = CommandHelper.getAvailableFileNames();
        List<String> enteredFiles = new ArrayList<>();
        for (int i = 1; i < args.length -1 ; i++) {
            String current = args[i];
            String previous = (i>0) ? args[i-1] : "";
            if (!current.startsWith("-") && !( (previous.equalsIgnoreCase("-b") || previous.equalsIgnoreCase("--batch")) && CommandHelper.isNumber(current) )) {
                enteredFiles.add(current);
            }
        }
        allFiles.stream().filter(file -> !CommandHelper.containsIgnoreCase(enteredFiles, file)).forEach(suggestions::add);

        if (!CommandHelper.hasFlag(args, "-b", "--batch") && !(prevArg.equals("-b") || prevArg.equals("--batch"))) {
            suggestions.add("-b");
        }
        return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
    }
    
    private List<GhostBlockEntry> collectOriginalBlocksForAutoSave(WorldClient world, List<GhostBlockEntry> entriesToLoad) {
        List<GhostBlockEntry> validAutoSaveEntries = new ArrayList<>();
        String autoFileName = CommandHelper.getAutoClearFileName(world);
        List<GhostBlockEntry> existingAutoEntries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
        Set<String> existingKeys = existingAutoEntries.stream().map(e -> e.x + "," + e.y + "," + e.z).collect(Collectors.toSet());

        for (GhostBlockEntry loadedEntry : entriesToLoad) {
            BlockPos pos = new BlockPos(loadedEntry.x, loadedEntry.y, loadedEntry.z);
            String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (!existingKeys.contains(key)) {
                IBlockState originalState = world.getBlockState(pos);
                Block originalBlock = originalState.getBlock();
                validAutoSaveEntries.add(new GhostBlockEntry(pos, loadedEntry.blockId, loadedEntry.metadata,
                        originalBlock.getRegistryName().toString(), originalBlock.getMetaFromState(originalState)));
            }
        }
        return validAutoSaveEntries;
    }
}