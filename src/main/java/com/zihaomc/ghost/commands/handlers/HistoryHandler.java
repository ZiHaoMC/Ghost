package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.data.CommandState;
import com.zihaomc.ghost.commands.data.CommandState.UndoRecord;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;

/**
 * 处理 /cgb history 子命令的逻辑。
 * 向用户显示最近的可撤销操作列表。
 */
public class HistoryHandler implements ICommandHandler {
    
    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (CommandState.undoHistory.isEmpty()) {
            sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.empty"));
            return;
        }

        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GOLD, "ghostblock.commands.history.header"));
        int limit = Math.min(10, CommandState.undoHistory.size());

        for (int i = 0; i < limit; i++) {
            UndoRecord record = CommandState.undoHistory.get(i);
            String description;

            // 根据操作类型和附加信息生成具体的描述文本
            switch (record.operationType) {
                case SET:
                    // record.details 此时应为 "x,y,z block_id"
                    String[] setDetails = record.details.split(" ");
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.set", setDetails[0], setDetails[1]);
                    break;
                case FILL:
                    // record.details 此时应为 "count block_id"
                    String[] fillDetails = record.details.split(" ");
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.fill", fillDetails[0], fillDetails[1]);
                    break;
                case LOAD:
                    // record.details 此时应为 "count filenames"
                    String[] loadDetails = record.details.split(" ", 2);
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.load", loadDetails[0], loadDetails[1]);
                    break;
                case CLEAR_BLOCK:
                    // record.details 此时应为 "count"
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.clear_block", record.details);
                    break;
                case CLEAR_FILE:
                    // record.details 此时应为 "filenames"
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.clear_file", record.details);
                    break;
                default:
                    description = "Unknown Operation";
                    break;
            }

            // 创建可交互的聊天组件
            ChatComponentText lineComponent = new ChatComponentText(
                LangUtil.translate("ghostblock.commands.history.entry.format", i + 1, description)
            );
            
            // 悬停事件保持不变
            ChatComponentText hoverText = new ChatComponentText(record.commandString);
            hoverText.getChatStyle().setColor(EnumChatFormatting.GRAY);
            HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText);
            lineComponent.setChatStyle(new ChatStyle().setChatHoverEvent(hoverEvent));

            sender.addChatMessage(lineComponent);
        }

        if (CommandState.undoHistory.size() > limit) {
             sender.addChatMessage(new ChatComponentText("  " + EnumChatFormatting.GRAY + "..."));
        }
        
        sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.history.usage_hint"));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return Collections.emptyList();
    }
}