package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.ClearConfirmation;
import com.zihaomc.ghost.commands.data.CommandState.UndoRecord;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.data.GhostBlockData.GhostBlockEntry;
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

/**
 * 处理 /cgb confirm_clear 子命令的逻辑。
 */
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
        ClearConfirmation confirmation = CommandState.pendingConfirmations.get(sender.getName());

        if (confirmation == null || System.currentTimeMillis() - confirmation.timestamp > CommandState.CONFIRMATION_TIMEOUT) {
            CommandState.pendingConfirmations.remove(sender.getName());
            throw new CommandException(LangUtil.translate("ghostblock.commands.clear.confirm_expired"));
        }

        // 验证待确认的文件列表是否与命令中的匹配
        Set<String> confirmationBaseFileNames = confirmation.targetFiles.stream()
                .map(f -> f.getName().replace(".json", ""))
                .collect(Collectors.toSet());
        Set<String> commandBaseFileNames = filesToConfirm.stream()
                .map(s -> s.replace(".json", ""))
                .collect(Collectors.toSet());

        if (!confirmationBaseFileNames.equals(commandBaseFileNames)) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.clear.confirm_expired"));
        }
        
        // 拼接一个描述性的命令字符串
        String descriptiveCommand = "/cgb clear file " + String.join(" ", commandBaseFileNames);

        // 创建详细描述字符串, 格式: "filename1, filename2"
        String details = String.join(", ", commandBaseFileNames);

        // 备份要删除的文件内容 (用于 undo)
        Map<String, List<GhostBlockEntry>> fileBackups = new HashMap<>();
        for (String baseFileName : commandBaseFileNames) {
            List<GhostBlockEntry> entries = GhostBlockData.loadData(world, Collections.singletonList(baseFileName));
            fileBackups.put(baseFileName, entries);
        }

        // 创建撤销记录
        String baseId = GhostBlockData.getWorldBaseIdentifier(world);
        String undoFileName = "undo_clear_file_" + baseId + "_dim_" + world.provider.getDimensionId() + "_" + System.currentTimeMillis();
        CommandState.undoHistory.add(0, new UndoRecord(undoFileName, fileBackups, UndoRecord.OperationType.CLEAR_FILE, null, descriptiveCommand, details));
        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY,"ghostblock.commands.undo.record_created_clear"));

        // 执行实际删除
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

        CommandState.pendingConfirmations.remove(sender.getName());

        // 发送结果
        if (!deletedFiles.isEmpty()) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN,
                    "ghostblock.commands.clear.success", String.join(", ", deletedFiles)));
        }
        if (!failedFiles.isEmpty()) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.RED,
                    "ghostblock.commands.clear.failed", String.join(", ", failedFiles)));
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        // 在 confirm_clear 之后，补全的应该是文件名，但这通常只在点击链接时使用，手动输入时不需要建议
        return Collections.emptyList();
    }
}