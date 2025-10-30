package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.ClearConfirmation;
import com.zihaomc.ghost.commands.data.CommandState.UndoRecord;
import com.zihaomc.ghost.commands.tasks.ClearTask;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.ClickEvent.Action;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理 /cgb clear 子命令的逻辑。
 */
public class ClearHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage"));
        }
        String clearType = args[1].toLowerCase();
        if ("file".equals(clearType)) {
            handleClearFile(sender, world, args);
        } else if ("block".equals(clearType)) {
            handleClearBlock(sender, world, args);
        } else {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage"));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        int currentArgIndex = args.length - 1;
        if (currentArgIndex == 1) {
            return CommandBase.getListOfStringsMatchingLastWord(args, Arrays.asList("file", "block"));
        }
        if (currentArgIndex >= 2) {
            String clearType = args[1].toLowerCase();
            if ("file".equals(clearType)) {
                List<String> allFiles = CommandHelper.getAvailableFileNames();
                List<String> enteredFiles = Arrays.asList(args).subList(2, args.length -1);
                List<String> availableForCompletion = allFiles.stream().filter(file -> !CommandHelper.containsIgnoreCase(enteredFiles, file)).collect(Collectors.toList());
                return CommandBase.getListOfStringsMatchingLastWord(args, availableForCompletion);
            } else if ("block".equals(clearType)) {
                List<String> suggestions = new ArrayList<>();
                String lastFullArg = (args.length > 2) ? args[args.length - 2].toLowerCase() : "";
                boolean hasBatchFlag = CommandHelper.hasFlag(args, "-b", "--batch");
                boolean hasConfirmFlag = CommandHelper.hasFlag(args, "confirm");
                if (lastFullArg.equals("-b") || lastFullArg.equals("--batch")) {
                    if (!CommandHelper.isNumber(args[args.length-1])) {
                        suggestions.addAll(Arrays.asList("100", "500", "1000"));
                    }
                    if (!hasConfirmFlag) suggestions.add("confirm");
                } else {
                    if (!hasBatchFlag) suggestions.add("-b");
                    if (!hasConfirmFlag) suggestions.add("confirm");
                }
                return CommandBase.getListOfStringsMatchingLastWord(args, suggestions);
            }
        }
        return Collections.emptyList();
    }

    private void handleClearFile(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage.file_missing_args"));
        }
        List<String> fileNames = Arrays.asList(args).subList(2, args.length);
        List<File> targetFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();
        List<String> validFileNamesForMessage = new ArrayList<>();

        for (String fileName : fileNames) {
            String baseFileName = fileName.toLowerCase().endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
            if (baseFileName.startsWith("clear_") || baseFileName.startsWith("undo_")) {
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.load.ignored_internal_file", fileName));
                continue;
            }
            File file = GhostBlockData.getDataFile(world, baseFileName);
            if (file.exists()) {
                targetFiles.add(file);
                validFileNamesForMessage.add(baseFileName);
            } else {
                missingFiles.add(fileName);
            }
        }

        if (targetFiles.isEmpty()) {
            if (!missingFiles.isEmpty()) {
                 throw new CommandException(LangUtil.translate("ghostblock.commands.clear.missing_files", String.join(", ", missingFiles)));
            } else {
                 throw new CommandException(LangUtil.translate("ghostblock.commands.clear.error.no_valid_files_to_delete"));
            }
        }
        if (!missingFiles.isEmpty()) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, LangUtil.translate("ghostblock.commands.clear.missing_files", String.join(", ", missingFiles))));
        }

        String confirmCommand = "/cgb confirm_clear " + String.join(" ", validFileNamesForMessage);
        IChatComponent message = new ChatComponentText("")
                .appendSibling(new ChatComponentText(EnumChatFormatting.GOLD + LangUtil.translate("ghostblock.commands.clear.confirm.question") + "\n"))
                .appendSibling(new ChatComponentText(EnumChatFormatting.WHITE + String.join(", ", validFileNamesForMessage) + "\n"))
                .appendSibling(new ChatComponentText(LangUtil.translate("ghostblock.commands.clear.confirm.button"))
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED).setBold(true)
                                .setChatClickEvent(new ClickEvent(Action.RUN_COMMAND, confirmCommand))));

        sender.addChatMessage(message);
        CommandState.pendingConfirmations.put(sender.getName(), new ClearConfirmation(targetFiles, System.currentTimeMillis()));
    }

    private void handleClearBlock(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        boolean batchMode = false;
        int batchSize = 100;
        boolean confirmed = false;

        for (int i = 2; i < args.length; ) {
            String flag = args[i].toLowerCase();
            if (flag.equals("-b") || flag.equals("--batch")) {
                batchMode = true;
                i++;
                if (i < args.length && CommandHelper.isNumber(args[i])) {
                    try {
                        batchSize = Integer.parseInt(args[i]);
                        CommandHelper.validateBatchSize(batchSize);
                        i++;
                    } catch (NumberFormatException | CommandException e) {
                         throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_batch_size"));
                    }
                }
            } else if (flag.equals("confirm")) {
                confirmed = true;
                i++;
            } else {
                throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage.block"));
            }
        }

        String autoFileName = CommandHelper.getAutoClearFileName(world);
        List<GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(autoFileName));
        File autoFile = GhostBlockData.getDataFile(world, autoFileName);

        if (entries.isEmpty()) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.clear.block.no_blocks"));
            return;
        }

        if (!confirmed) {
            sendConfirmationMessage(sender, batchMode, batchSize);
        } else {
            // [新增] 预先生成 TaskId
            Integer taskId = batchMode ? CommandState.taskIdCounter.incrementAndGet() : null;
            
            createClearUndoRecord(world, entries, taskId); // [修改] 传入 taskId

            if (taskId != null) {
                CommandState.activeClearTasks.add(new ClearTask(world, entries, batchSize, sender, taskId, autoFile));
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.clear.batch_started", taskId, entries.size(), batchSize));
            } else {
                clearAllGhostBlocksSync(sender, world, entries);
                if (autoFile.exists() && !autoFile.delete()) {
                    LogUtil.error("log.error.worldLoad.clearFile.deleteFailed", autoFile.getPath());
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED, "ghostblock.commands.clear.block.delete_failed", autoFile.getName()));
                }
            }
        }
    }
    
    private void clearAllGhostBlocksSync(ICommandSender sender, WorldClient world, List<GhostBlockEntry> entries) {
        int restored = 0;
        int failed = 0;
        for (GhostBlockEntry entry : entries) {
            try {
                BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                Block originalBlock = Block.getBlockFromName(entry.originalBlockId);
                if (originalBlock == null) {
                    failed++;
                    continue;
                }
                world.setBlockState(pos, originalBlock.getStateFromMeta(entry.originalMetadata), 3);
                world.markBlockForUpdate(pos);
                restored++;
            } catch (Exception e) {
                failed++;
                LogUtil.printStackTrace("log.error.clear.task.restore.failed", e, entry.x + "," + entry.y + "," + entry.z, e.getMessage());
            }
        }
        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.clear.block.success", restored));
        if (failed > 0) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED, "ghostblock.commands.clear.block.partial_fail", restored, failed));
        }
    }
    
    // [修改] 接收 taskId 参数
    private void createClearUndoRecord(WorldClient world, List<GhostBlockEntry> clearedEntries, Integer taskId) {
        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_clear_block_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis();
        GhostBlockData.saveData(world, clearedEntries, undoFileName, true);
        // [修改] 传入 taskId 到 UndoRecord
        CommandState.undoHistory.push(new UndoRecord(undoFileName, new HashMap<>(), UndoRecord.OperationType.CLEAR_BLOCK, taskId));
        LogUtil.info("log.info.undo.created.clearBlock", undoFileName);
    }

    private void sendConfirmationMessage(ICommandSender sender, boolean batchMode, int batchSize) {
        StringBuilder confirmCommand = new StringBuilder("/cgb clear block");
        if (batchMode) confirmCommand.append(" -b ").append(batchSize);
        confirmCommand.append(" confirm");
        IChatComponent message = new ChatComponentText("")
                .appendSibling(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghostblock.commands.clear.block.confirm.question") + "\n"))
                .appendSibling(new ChatComponentText(LangUtil.translate("ghostblock.commands.clear.block.confirm.button"))
                        .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED).setBold(true)
                                .setChatClickEvent(new ClickEvent(Action.RUN_COMMAND, confirmCommand.toString()))));
        sender.addChatMessage(message);
    }
}