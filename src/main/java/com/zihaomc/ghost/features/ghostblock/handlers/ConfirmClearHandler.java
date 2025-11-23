package com.zihaomc.ghost.features.ghostblock.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.ClearConfirmation;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.UndoRecord;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData.GhostBlockEntry;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class ConfirmClearHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayer)) {
            throw new CommandException("commands.generic.player.unsupported");
        }
        if (args.length < 2) {
            throw new WrongUsageException(LangUtil.translate("ghostblock.commands.clear.usage"));
        }

        List<String> filesToConfirm = Arrays.asList(args).subList(1, args.length);
        ClearConfirmation confirmation = GhostBlockState.pendingConfirmations.get(sender.getName());

        if (confirmation == null || System.currentTimeMillis() - confirmation.timestamp > GhostBlockState.CONFIRMATION_TIMEOUT) {
            GhostBlockState.pendingConfirmations.remove(sender.getName());
            throw new CommandException(LangUtil.translate("ghostblock.commands.clear.confirm_expired"));
        }

        Set<String> confirmationBaseFileNames = confirmation.targetFiles.stream()
                .map(f -> f.getName().replace(".json", ""))
                .collect(Collectors.toSet());
        Set<String> commandBaseFileNames = filesToConfirm.stream()
                .map(s -> s.replace(".json", ""))
                .collect(Collectors.toSet());

        if (!confirmationBaseFileNames.equals(commandBaseFileNames)) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.clear.confirm_expired"));
        }
        
        String descriptiveCommand = "/cgb clear file " + String.join(" ", commandBaseFileNames);
        String details = String.join(", ", commandBaseFileNames);

        Map<String, List<GhostBlockEntry>> fileBackups = new HashMap<>();
        for (String baseFileName : commandBaseFileNames) {
            List<GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(baseFileName));
            fileBackups.put(baseFileName, entries);
        }

        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_clear_file_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis();
        GhostBlockState.undoHistory.add(0, new UndoRecord(undoFileName, fileBackups, UndoRecord.OperationType.CLEAR_FILE, null, descriptiveCommand, details));
        sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.record_created_clear"));

        List<String> deletedFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        for (File file : confirmation.targetFiles) {
            if (file.exists()) {
                if (file.delete()) {
                    deletedFiles.add(file.getName());
                } else {
                    failedFiles.add(file.getName());
                }
            } else {
                LogUtil.info("log.info.clear.file.alreadyDeleted", file.getName());
            }
        }

        GhostBlockState.pendingConfirmations.remove(sender.getName());

        if (!deletedFiles.isEmpty()) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN,
                    "ghostblock.commands.clear.success", String.join(", ", deletedFiles)));
        }
        if (!failedFiles.isEmpty()) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.RED,
                    "ghostblock.commands.clear.failed", String.join(", ", failedFiles)));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return Collections.emptyList();
    }
}
