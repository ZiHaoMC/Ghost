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
        // 限制最多显示最近的10条历史记录，防止刷屏
        int limit = Math.min(10, CommandState.undoHistory.size());

        for (int i = 0; i < limit; i++) {
            UndoRecord record = CommandState.undoHistory.get(i);
            String typeDescription;
            String cmd = record.commandString.toLowerCase();

            // 根据记录的命令字符串来确定更具体的操作类型描述
            if (cmd.startsWith("/cgb fill")) {
                typeDescription = LangUtil.translate("ghostblock.commands.history.entry.type.fill");
            } else if (cmd.startsWith("/cgb set")) {
                typeDescription = LangUtil.translate("ghostblock.commands.history.entry.type.set");
            } else if (cmd.startsWith("/cgb load")) {
                typeDescription = LangUtil.translate("ghostblock.commands.history.entry.type.load");
            } else if (cmd.startsWith("/cgb clear block")) {
                typeDescription = LangUtil.translate("ghostblock.commands.history.entry.type.clear_block");
            } else if (cmd.contains("clear") && cmd.contains("file")) { // 涵盖 confirm_clear
                typeDescription = LangUtil.translate("ghostblock.commands.history.entry.type.clear_file");
            } else { // 作为备用
                switch (record.operationType) {
                    case SET: typeDescription = "Set/Fill/Load"; break;
                    case CLEAR_BLOCK: typeDescription = "Clear"; break;
                    default: typeDescription = "Unknown"; break;
                }
            }

            // 创建可交互的聊天组件
            ChatComponentText lineComponent = new ChatComponentText(
                LangUtil.translate("ghostblock.commands.history.entry.format", i + 1, typeDescription)
            );
            
            // 创建鼠标悬停时显示的文本
            ChatComponentText hoverText = new ChatComponentText(record.commandString);
            hoverText.getChatStyle().setColor(EnumChatFormatting.GRAY);
            
            // 创建悬停事件
            HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText);
            
            // 将悬停事件应用到聊天行
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
        // history 命令本身不需要参数补全
        return Collections.emptyList();
    }
}