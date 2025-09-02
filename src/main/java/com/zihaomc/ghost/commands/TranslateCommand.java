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

        // 最终修正：使用标准的 Java String.join 方法。
        // Minecraft的命令解析器已经为我们处理好了引号，
        // args 数组已经是分割好的参数。我们只需要把它们用空格拼回来即可。
        // 这个方法不依赖任何Minecraft内部代码，因此100%兼容。
        final String sourceText = String.join(" ", args);
        
        // 自己构建带前缀的消息
        ChatComponentText translatingMessage = new ChatComponentText("");
        ChatComponentText prefix = new ChatComponentText("[Ghost] ");
        prefix.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        ChatComponentText content = new ChatComponentText(LangUtil.translate("ghostblock.commands.gtranslate.translating", sourceText));
        content.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
        translatingMessage.appendSibling(prefix);
        translatingMessage.appendSibling(content);
        sender.addChatMessage(translatingMessage);

        // 网络请求在新线程中执行
        new Thread(() -> {
            final String result = NiuTransUtil.translate(sourceText);
            // 将结果发送回主线程进行处理
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ChatComponentText resultMessage = new ChatComponentText("§b[翻译]§r " + result);
                sender.addChatMessage(resultMessage);
            });
        }).start();
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        // 当准备输入第一个参数时，提供占位符提示
        if (args.length == 1) {
            String placeholder = LangUtil.translate("ghostblock.commands.gtranslate.placeholder");
            // 注意：这里我们调用的 getListOfStringsMatchingLastWord 是继承自 CommandBase 的，
            // 它在运行时是可用的，不会导致编译错误。
            return getListOfStringsMatchingLastWord(args, Collections.singletonList(placeholder));
        }
        return null;
    }
}