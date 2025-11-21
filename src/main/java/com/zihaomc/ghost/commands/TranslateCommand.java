package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.utils.CommandHelper;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.TranslationUtil;
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
        
        // 解析参数，查找 -p 标志，允许用户临时指定翻译源
        if (args.length >= 3 && (args[0].equalsIgnoreCase("-p") || args[0].equalsIgnoreCase("--provider"))) {
            providerOverride = args[1];
            sourceText = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        } else {
            sourceText = String.join(" ", args);
        }
        
        // 确定的提供商名称 (如果有覆盖则使用覆盖值，否则使用配置值)
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

        new Thread(() -> {
            // 使用指定的提供商进行翻译
            final String result = TranslationUtil.translate(finalSourceText, finalProvider);
            
            Minecraft.getMinecraft().addScheduledTask(() -> {
                // 安全检查：确保玩家仍在游戏中，以防止在切换世界时崩溃
                if (Minecraft.getMinecraft().thePlayer == null) {
                    return;
                }
                
                ChatComponentText resultMessage = new ChatComponentText("");
                
                if (result.startsWith(TranslationUtil.ERROR_PREFIX)) {
                    // 错误消息：使用通用前缀和红色
                    String errorContent = result.substring(TranslationUtil.ERROR_PREFIX.length());
                    ChatComponentText errorPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
                    errorPrefix.getChatStyle().setColor(EnumChatFormatting.RED);
                    ChatComponentText errorText = new ChatComponentText(errorContent);
                    errorText.getChatStyle().setColor(EnumChatFormatting.RED);
                    resultMessage.appendSibling(errorPrefix).appendSibling(errorText);
                } else {
                    // 成功消息：使用翻译前缀和蓝色
                    ChatComponentText resultPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.translation") + " ");
                    resultPrefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
                    
                    // 显示当前使用的提供商标签 (例如 [GOOGLE])
                    ChatComponentText providerTag = new ChatComponentText(EnumChatFormatting.YELLOW + "[" + finalProvider + "] " + EnumChatFormatting.RESET);
                    
                    ChatComponentText resultContent = new ChatComponentText(result);
                    
                    resultMessage.appendSibling(resultPrefix)
                                 .appendSibling(providerTag)
                                 .appendSibling(resultContent);
                                 
                    // --- 核心修改：追加切换按钮 ---
                    // 根据配置和当前提供商，生成其他源的切换按钮
                    resultMessage.appendSibling(CommandHelper.createProviderSwitchButtons(finalSourceText, finalProvider));
                }
                
                sender.addChatMessage(resultMessage);
            });
        }).start();
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            String placeholder = LangUtil.translate("ghostblock.commands.gtranslate.placeholder");
            // 提供 -p 选项的补全
            return getListOfStringsMatchingLastWord(args, "-p", "--provider", placeholder);
        }
        // 如果正在输入提供商参数
        if (args.length == 2 && (args[0].equalsIgnoreCase("-p") || args[0].equalsIgnoreCase("--provider"))) {
            return getListOfStringsMatchingLastWord(args, "GOOGLE", "BING", "MYMEMORY", "NIUTRANS");
        }
        return null;
    }
}