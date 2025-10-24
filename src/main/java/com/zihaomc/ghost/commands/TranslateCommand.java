package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.utils.NiuTransUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;

public class TranslateCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "gtranslate";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("gt");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return LangUtil.translate("ghostblock.commands.gtranslate.usage");
    }
    
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws WrongUsageException {
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        final String sourceText = String.join(" ", args);
        
        ChatComponentText translatingMessage = new ChatComponentText("");
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        ChatComponentText content = new ChatComponentText(LangUtil.translate("ghostblock.commands.gtranslate.translating", sourceText));
        content.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
        translatingMessage.appendSibling(prefix);
        translatingMessage.appendSibling(content);
        sender.addChatMessage(translatingMessage);

        new Thread(() -> {
            final String result = NiuTransUtil.translate(sourceText);
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ChatComponentText resultMessage = new ChatComponentText("");
                
                if (result.startsWith(NiuTransUtil.ERROR_PREFIX)) {
                    // 错误消息：使用通用前缀和红色
                    String errorContent = result.substring(NiuTransUtil.ERROR_PREFIX.length());
                    ChatComponentText errorPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
                    errorPrefix.getChatStyle().setColor(EnumChatFormatting.RED);
                    ChatComponentText errorText = new ChatComponentText(errorContent);
                    errorText.getChatStyle().setColor(EnumChatFormatting.RED);
                    resultMessage.appendSibling(errorPrefix).appendSibling(errorText);
                } else {
                    // 成功消息：使用翻译前缀和蓝色
                    ChatComponentText resultPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.translation") + " ");
                    resultPrefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
                    ChatComponentText resultContent = new ChatComponentText(result);
                    resultMessage.appendSibling(resultPrefix).appendSibling(resultContent);
                }
                
                sender.addChatMessage(resultMessage);
            });
        }).start();
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            String placeholder = LangUtil.translate("ghostblock.commands.gtranslate.placeholder");
            return getListOfStringsMatchingLastWord(args, Collections.singletonList(placeholder));
        }
        return null;
    }
}