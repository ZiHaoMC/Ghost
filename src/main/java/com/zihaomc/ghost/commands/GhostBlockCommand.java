package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.handlers.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;

import java.util.*;

/**
 * GhostBlock 命令的主类 (重构后)。
 * 它现在作为一个调度器（Dispatcher），将子命令分发给各自的处理器类。
 * 本身不再处理事件、维护状态或执行具体的命令逻辑。
 */
public class GhostBlockCommand extends CommandBase {

    /**
     * 一个映射，存储子命令名称与其对应的处理器实例。
     */
    private final Map<String, ICommandHandler> commandHandlers = new HashMap<>();

    // **** 修正点: 创建一个有序的、用于 Tab 补全的列表 ****
    // 这个列表定义了子命令的显示顺序，并且排除了不应被用户直接输入的 "confirm_clear"。
    private static final List<String> SUB_COMMANDS_FOR_TAB = Arrays.asList(
            "set",
            "fill",
            "load",
            "clear",
            "undo",
            "cancel",
            "resume",
            "help"
    );

    /**
     * 构造函数，在创建命令实例时初始化并注册所有子命令的处理器。
     */
    public GhostBlockCommand() {
        commandHandlers.put("set", new SetHandler());
        commandHandlers.put("fill", new FillHandler());
        commandHandlers.put("load", new LoadHandler());
        commandHandlers.put("clear", new ClearHandler());
        commandHandlers.put("cancel", new CancelHandler());
        commandHandlers.put("resume", new ResumeHandler());
        commandHandlers.put("undo", new UndoHandler());
        commandHandlers.put("help", new HelpHandler());
        // confirm_clear 仍然是一个有效的命令处理器，但不会出现在 Tab 补全中
        commandHandlers.put("confirm_clear", new ConfirmClearHandler());
    }

    @Override
    public String getCommandName() {
        return "cghostblock";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("cgb");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return LangUtil.translate("ghostblock.commands.usage");
    }
    
    @Override
    public int getRequiredPermissionLevel() {
        return 0; // 客户端命令
    }
    
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 对所有客户端玩家可用
    }

    /**
     * 处理命令执行的核心方法。
     * 它解析子命令并将其委托给相应的处理器。
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        // 如果没有参数，或者第一个参数是 "help"，则显示帮助信息
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            commandHandlers.get("help").processCommand(sender, null, args);
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        ICommandHandler handler = commandHandlers.get(subCommand);

        if (handler != null) {
            WorldClient world = Minecraft.getMinecraft().theWorld;
            // 检查子命令是否需要一个有效的世界实例
            boolean worldRequired = !Arrays.asList("help", "cancel", "resume").contains(subCommand);
            
            if (worldRequired && world == null) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.not_in_world"));
            }
            // 将处理逻辑委托给找到的处理器
            handler.processCommand(sender, world, args);
        } else {
            // 如果找不到对应的处理器，抛出用法错误
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    /**
     * 提供 Tab 补全的逻辑。
     * 它同样将补全请求委托给相应的处理器。
     */
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        // 如果只输入了第一个参数（子命令），则提供所有子命令的补全
        if (args.length == 1) {
            // 使用有序列表进行补全
            return getListOfStringsMatchingLastWord(args, SUB_COMMANDS_FOR_TAB);
        }

        String subCommand = args[0].toLowerCase();
        ICommandHandler handler = commandHandlers.get(subCommand);

        // 如果找到了处理器，则调用其 Tab 补全方法
        if (handler != null) {
            return handler.addTabCompletionOptions(sender, args, pos);
        }

        return Collections.emptyList();
    }
}