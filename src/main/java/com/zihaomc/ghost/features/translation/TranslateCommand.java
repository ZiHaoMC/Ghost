package com.zihaomc.ghost.features.translation;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;
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

        String providerOverride = null;
        String sourceText;
        
        if (args.length >= 3 && (args[0].equalsIgnoreCase("-p") || args[0].equalsIgnoreCase("--provider"))) {
            providerOverride = args[1];
            sourceText = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        } else {
            sourceText = String.join(" ", args);
        }
        
        final String finalProvider = (providerOverride != null) ? providerOverride.toUpperCase() : GhostConfig.Translation.translationProvider.toUpperCase();
        final String finalSourceText = sourceText;

        ChatComponentText translatingMessage = new ChatComponentText("");
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        ChatComponentText content = new ChatComponentText(LangUtil.translate("ghostblock.commands.gtranslate.translating", sourceText));
        content.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
        translatingMessage.appendSibling(prefix);
        translatingMessage.appendSibling(content);
        sender.addChatMessage(translatingMessage);

        TranslationUtil.runAsynchronously(() -> {
            final String result = TranslationUtil.translate(finalSourceText, finalProvider);
            
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().thePlayer == null) {
                    return;
                }
                
                ChatComponentText resultMessage = new ChatComponentText("");
                
                if (result.startsWith(TranslationUtil.ERROR_PREFIX)) {
                    String errorContent = result.substring(TranslationUtil.ERROR_PREFIX.length());
                    ChatComponentText errorPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
                    
                    // --- 统一颜色为深灰色 ---
                    errorPrefix.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
                    
                    ChatComponentText errorText = new ChatComponentText(errorContent);
                    errorText.getChatStyle().setColor(EnumChatFormatting.RED);
                    resultMessage.appendSibling(errorPrefix).appendSibling(errorText);
                } else {
                    ChatComponentText resultPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.translation") + " ");
                    resultPrefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
                    
                    ChatComponentText providerTag = new ChatComponentText(EnumChatFormatting.YELLOW + "[" + finalProvider + "] " + EnumChatFormatting.RESET);
                    
                    ChatComponentText resultContent = new ChatComponentText(result);
                    
                    resultMessage.appendSibling(resultPrefix)
                                 .appendSibling(providerTag)
                                 .appendSibling(resultContent);
                                 
                    // --- 修改点：使用 GhostBlockHelper ---
                    resultMessage.appendSibling(GhostBlockHelper.createProviderSwitchButtons(finalSourceText, finalProvider));
                }
                
                sender.addChatMessage(resultMessage);
            });
        });
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            String placeholder = LangUtil.translate("ghostblock.commands.gtranslate.placeholder");
            return getListOfStringsMatchingLastWord(args, "-p", "--provider", placeholder);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("-p") || args[0].equalsIgnoreCase("--provider"))) {
            return getListOfStringsMatchingLastWord(args, "GOOGLE", "BING", "MYMEMORY", "NIUTRANS");
        }
        return null;
    }
}