package com.zihaomc.ghost.commands.handlers;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.GhostBlockCommand;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.Collections;
import java.util.List;

/**
 * 处理 /cgb help 子命令的逻辑。
 */
public class HelpHandler implements ICommandHandler {

    @Override
    public void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException {
        displayHelp(sender);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return Collections.emptyList();
    }

    /**
     * 显示 /cgb 命令的帮助信息。
     * @param sender 命令发送者。
     */
    private void displayHelp(ICommandSender sender) {
        EnumChatFormatting hl = EnumChatFormatting.GOLD;
        EnumChatFormatting tx = EnumChatFormatting.GRAY;
        EnumChatFormatting sc = EnumChatFormatting.AQUA;
        EnumChatFormatting us = EnumChatFormatting.YELLOW;

        String commandName = new GhostBlockCommand().getCommandName();
        String commandAliases = String.join(", ", new GhostBlockCommand().getCommandAliases());

        sender.addChatMessage(new ChatComponentText(hl + LangUtil.translate("ghostblock.commands.cghostblock.help.header")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.description")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.usage.main") + ": " + us + "/" + commandName + " <set|fill|load|clear|...|help> [参数...]"));

        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommands.header")));
        sender.addChatMessage(formatHelpLine(sc + "help", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.help")));
        sender.addChatMessage(formatHelpLine(sc + "set", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.set") + us + " " + LangUtil.translate("ghostblock.commands.cghostblock.set.usage")));
        sender.addChatMessage(formatHelpLine(sc + "fill", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.fill") + us + " " + LangUtil.translate("ghostblock.commands.cghostblock.fill.usage")));
        sender.addChatMessage(formatHelpLine(sc + "load", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.load") + us + " " + LangUtil.translate("ghostblock.commands.cghostblock.load.usage")));
        sender.addChatMessage(formatHelpLine(sc + "clear", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.clear") + us + " " + LangUtil.translate("ghostblock.commands.clear.usage")));
        sender.addChatMessage(formatHelpLine(sc + "cancel", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.cancel") + us + " " + LangUtil.translate("ghostblock.commands.cancel.usage")));
        sender.addChatMessage(formatHelpLine(sc + "resume", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.resume") + us + " " + LangUtil.translate("ghostblock.commands.resume.usage")));
        sender.addChatMessage(formatHelpLine(sc + "undo", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.undo") + us + " " + LangUtil.translate("ghostblock.commands.undo.usage")));
        sender.addChatMessage(formatHelpLine(sc + "history", tx + LangUtil.translate("ghostblock.commands.cghostblock.help.subcommand.history") + us + " " + LangUtil.translate("ghostblock.commands.history.usage")));


        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.cghostblock.help.aliases") + ": " + hl + commandAliases));
    }

    /**
     * 格式化帮助文本中的一行（子命令 + 描述）。
     */
    private IChatComponent formatHelpLine(String command, String description) {
        ChatComponentText line = new ChatComponentText("  ");
        ChatComponentText cmdComp = new ChatComponentText(command);
        ChatComponentText descComp = new ChatComponentText(" - " + description);
        line.appendSibling(cmdComp);
        line.appendSibling(descComp);
        return line;
    }
}