package com.zihaomc.ghost.features.ghostblock.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.UndoRecord;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
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

public class HistoryHandler implements ICommandHandler {
    
    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        if (GhostBlockState.undoHistory.isEmpty()) {
            sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.undo.empty"));
            return;
        }

        sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GOLD, "ghostblock.commands.history.header"));
        int limit = Math.min(10, GhostBlockState.undoHistory.size());

        for (int i = 0; i < limit; i++) {
            UndoRecord record = GhostBlockState.undoHistory.get(i);
            String description;

            switch (record.operationType) {
                case SET:
                    String[] setDetails = record.details.split(" ");
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.set", setDetails[0], setDetails[1]);
                    break;
                case FILL:
                    String[] fillDetails = record.details.split(" ");
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.fill", fillDetails[0], fillDetails[1]);
                    break;
                case LOAD:
                    String[] loadDetails = record.details.split(" ", 2);
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.load", loadDetails[0], loadDetails[1]);
                    break;
                case CLEAR_BLOCK:
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.clear_block", record.details);
                    break;
                case CLEAR_FILE:
                    description = LangUtil.translate("ghostblock.commands.history.entry.desc.clear_file", record.details);
                    break;
                default:
                    description = "Unknown Operation";
                    break;
            }

            ChatComponentText lineComponent = new ChatComponentText(
                LangUtil.translate("ghostblock.commands.history.entry.format", i + 1, description)
            );
            
            ChatComponentText hoverText = new ChatComponentText(record.commandString);
            hoverText.getChatStyle().setColor(EnumChatFormatting.GRAY);
            HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText);
            lineComponent.setChatStyle(new ChatStyle().setChatHoverEvent(hoverEvent));

            sender.addChatMessage(lineComponent);
        }

        if (GhostBlockState.undoHistory.size() > limit) {
             sender.addChatMessage(new ChatComponentText("  " + EnumChatFormatting.GRAY + "..."));
        }
        
        sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GRAY, "ghostblock.commands.history.usage_hint"));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return Collections.emptyList();
    }
}