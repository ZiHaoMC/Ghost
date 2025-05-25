package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import com.zihaomc.ghost.data.GhostBlockData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class GhostConfigCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "ghostconfig";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("gconfig");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        // 更新用法提示以包含新选项
        return LangUtil.translate("ghostblock.commands.gconfig.usage", "/gconfig [setting_name] [value] or /gconfig");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // 客户端命令通常不需要权限
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 客户端命令通常对所有玩家可用
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {

        // --- 新增：处理 help 子命令 ---
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            displayHelp(sender); // 调用帮助显示方法
            return; // 显示帮助后返回
        }
        // --- 帮助处理结束 ---

        // --- 现有逻辑 ---
        if (args.length == 0) {
            displayCurrentSettings(sender);
            return;
        }

        // 保留 toggleSuggest 功能
        if (args.length == 1 && args[0].equalsIgnoreCase("toggleSuggest")) {
            toggleChatSuggestions(sender);
            return;
        }

        if (args.length != 2) {
            // 添加 toggleSuggest 到用法错误提示
            throw new WrongUsageException(getCommandUsage(sender) + " - " + LangUtil.translate("ghostblock.commands.gconfig.extra_usage_toggle", "Use '/gconfig toggleSuggest' to quickly toggle suggestions."));
        }

        String settingName = args[0].toLowerCase();
        String valueStr = args[1];

        switch (settingName) {
            case "alwaysbatchfill":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setAlwaysBatchFill(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "forcedbatchsize":
                try {
                    int value = CommandBase.parseInt(valueStr, 1);
                    if (GhostConfig.setForcedBatchSize(value)) {
                         sendSuccessMessage(sender, settingName, value);
                    } else {
                         sendGenericSetError(sender, settingName);
                    }
                } catch (NumberFormatException e) {
                    sendIntegerError(sender, valueStr);
                } catch (CommandException e) {
                    sendPositiveIntegerError(sender, valueStr);
                }
                break;

            case "enableautosave":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableAutoSave(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "defaultsavename":
                 if (GhostConfig.setDefaultSaveFileName(valueStr)) {
                    sendSuccessMessage(sender, settingName, "'" + valueStr + "'");
                 } else {
                     sendGenericSetError(sender, settingName);
                 }
                break;

            case "enablechatsuggestions":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableChatSuggestions(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            // --- 新增：处理 enableAutoPlaceOnJoin ---
            case "enableautoplaceonjoin":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableAutoPlaceOnJoin(value); // 调用新的设置器
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            default:
                // 更新错误消息以包含新选项
                throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_setting.extended", settingName));
        }
    }

    /**
     * 切换聊天建议功能的开关。
     * @param sender 命令发送者。
     */
    private void toggleChatSuggestions(ICommandSender sender) {
        boolean currentState = GhostConfig.enableChatSuggestions;
        boolean newState = !currentState;
        GhostConfig.setEnableChatSuggestions(newState);

        String feedbackKey = newState ? "ghostblock.commands.togglesuggest.enabled" : "ghostblock.commands.togglesuggest.disabled";
        String defaultFeedback = newState ? "命令建议已启用。" : "命令建议已禁用。";
        ChatComponentTranslation feedback = new ChatComponentTranslation(feedbackKey, defaultFeedback);
        feedback.getChatStyle().setColor(newState ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
        sender.addChatMessage(feedback);
    }

    /**
     * 显示当前的配置设置。
     * @param sender 命令发送者。
     */
    private void displayCurrentSettings(ICommandSender sender) {
         sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.current_settings.header")
                 .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.AQUA)));

         sender.addChatMessage(formatSettingLine("alwaysBatchFill", GhostConfig.alwaysBatchFill));
         sender.addChatMessage(formatSettingLine("forcedBatchSize", GhostConfig.forcedBatchSize));
         sender.addChatMessage(formatSettingLine("enableAutoSave", GhostConfig.enableAutoSave));

         String displayFileName = GhostConfig.defaultSaveFileName;
         if (displayFileName == null || displayFileName.trim().isEmpty() || displayFileName.equalsIgnoreCase("default")) {
             displayFileName = "(" + LangUtil.translate("ghostblock.commands.gconfig.current_settings.default_filename_placeholder","使用世界标识符") + ")";
         } else {
             displayFileName = "'" + displayFileName + "'";
         }
         sender.addChatMessage(formatSettingLine("defaultSaveName", displayFileName));

         sender.addChatMessage(formatSettingLine("enableChatSuggestions", GhostConfig.enableChatSuggestions));

         // --- 新增：显示 enableAutoPlaceOnJoin ---
         sender.addChatMessage(formatSettingLine("enableAutoPlaceOnJoin", GhostConfig.enableAutoPlaceOnJoin));

         sender.addChatMessage(new ChatComponentText(" ") ); // 添加空行
         sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.hint_toggle_suggest")
                 .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.DARK_AQUA)));
    }

    /**
     * 格式化配置设置的显示行。
     * @param name 设置名称。
     * @param value 设置值。
     * @return 格式化后的 ChatComponentText。
     */
    private ChatComponentText formatSettingLine(String name, Object value) {
        ChatComponentText line = new ChatComponentText("  " + name + ": ");
        line.getChatStyle().setColor(EnumChatFormatting.GRAY);
        ChatComponentText valueComp = new ChatComponentText(String.valueOf(value));
        valueComp.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        line.appendSibling(valueComp);
        return line;
    }

     /**
      * 显示 /gconfig 命令的帮助信息。
      * @param sender 命令发送者。
      */
     private void displayHelp(ICommandSender sender) {
         EnumChatFormatting hl = EnumChatFormatting.GOLD; // 高亮颜色
         EnumChatFormatting tx = EnumChatFormatting.GRAY;  // 文本颜色
         EnumChatFormatting us = EnumChatFormatting.YELLOW;// 用法颜色
         EnumChatFormatting op = EnumChatFormatting.AQUA; // 选项颜色

         sender.addChatMessage(new ChatComponentText(hl + "--- Ghost Config 命令帮助 (/gconfig) ---"));
         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.description")));
         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.usage.main")));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig"));
         sender.addChatMessage(new ChatComponentText(tx + "    " + LangUtil.translate("ghostblock.commands.gconfig.help.usage.display")));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig <setting_name> <value>"));
         sender.addChatMessage(new ChatComponentText(tx + "    " + LangUtil.translate("ghostblock.commands.gconfig.help.usage.set")));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig help"));
         sender.addChatMessage(new ChatComponentText(tx + "    " + LangUtil.translate("ghostblock.commands.gconfig.help.usage.help")));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig toggleSuggest"));
         sender.addChatMessage(new ChatComponentText(tx + "    " + LangUtil.translate("ghostblock.commands.gconfig.help.usage.toggle")));

         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.available_settings")));
         sender.addChatMessage(new ChatComponentText(op + "  alwaysBatchFill " + tx + "(true/false)"));
         sender.addChatMessage(new ChatComponentText(op + "  forcedBatchSize " + tx + "(数字 > 0)"));
         sender.addChatMessage(new ChatComponentText(op + "  enableAutoSave " + tx + "(true/false)"));
         sender.addChatMessage(new ChatComponentText(op + "  defaultSaveName " + tx + "(文本)"));
         sender.addChatMessage(new ChatComponentText(op + "  enableChatSuggestions " + tx + "(true/false)"));
         // --- 新增：显示 enableAutoPlaceOnJoin ---
         sender.addChatMessage(new ChatComponentText(op + "  enableAutoPlaceOnJoin " + tx + "(true/false) - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoPlaceOnJoin")));

         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.examples.header")));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableAutoSave true"));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig defaultSaveName my_server_ghosts"));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig forcedBatchSize 500"));
         // --- 新增：示例 ---
         sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableAutoPlaceOnJoin")));

         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.aliases") + ": " + hl + String.join(", ", getCommandAliases())));
     }

    // 辅助方法：发送成功消息
    private void sendSuccessMessage(ICommandSender sender, String setting, Object value) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.success", setting, value)
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.GREEN)));
    }

    // 辅助方法：发送布尔值错误消息
    private void sendBooleanError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.boolean", invalidValue));
    }
    // 辅助方法：发送整数值错误消息
    private void sendIntegerError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.integer", invalidValue));
    }
    // 辅助方法：发送正整数值错误消息
     private void sendPositiveIntegerError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.integer.positive", invalidValue));
     }
    // 辅助方法：发送通用设置错误消息
     private void sendGenericSetError(ICommandSender sender, String setting) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.generic_set_failed", setting));
     }


    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            // --- 修改这里，加入新选项 ---
            return CommandBase.getListOfStringsMatchingLastWord(args,
                    "help", "alwaysBatchFill", "forcedBatchSize", "enableAutoSave", "defaultSaveName",
                    "enableChatSuggestions", "enableAutoPlaceOnJoin", "toggleSuggest"); // 添加 enableAutoPlaceOnJoin
            // --- 修改结束 ---
        }
        else if (args.length == 2) {
            String settingName = args[0].toLowerCase();
            if (settingName.equals("help")) {
                return Collections.emptyList();
            }
            switch (settingName) {
                case "alwaysbatchfill":
                case "enableautosave":
                case "enablechatsuggestions":
                case "enableautoplaceonjoin": // --- 新增：为新选项提供补全 ---
                    return CommandBase.getListOfStringsMatchingLastWord(args, "true", "false");

                case "forcedbatchsize":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "100", "500", "1000");

                case "defaultsavename":
                    List<String> nameSuggestions = new ArrayList<>();
                    nameSuggestions.add("default");
                    try {
                        if (Minecraft.getMinecraft().theWorld != null) {
                            String worldId = GhostBlockData.getWorldIdentifier(Minecraft.getMinecraft().theWorld);
                            String baseWorldId = GhostBlockData.getWorldBaseIdentifier(Minecraft.getMinecraft().theWorld);
                            if (!worldId.equals(baseWorldId)) { // 只添加基础ID（如果不同）和完整ID
                                nameSuggestions.add(baseWorldId);
                            }
                             nameSuggestions.add(worldId);
                        }
                    } catch (Exception e) {
                        // 忽略获取世界标识符时的异常
                    }
                    // 移除重复并排序
                    nameSuggestions = nameSuggestions.stream().distinct().sorted().collect(Collectors.toList());
                    return CommandBase.getListOfStringsMatchingLastWord(args, nameSuggestions);

                case "togglesuggest":
                    return Collections.emptyList();

                default:
                    return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }
}