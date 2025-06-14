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
// import net.minecraftforge.client.ClientCommandHandler; // 已在主类导入
import com.zihaomc.ghost.data.GhostBlockData; // 保留此导入，以防未来用到

import java.util.ArrayList;
// import java.util.Arrays; // 不需要 Arrays
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
        // 使用最新的翻译键，它应该包含所有选项
        return LangUtil.translate("ghostblock.commands.gconfig.usage.extended", "/gconfig [setting_name] [value] or /gconfig or /gconfig help");
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
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            displayHelp(sender);
            return;
        }
        if (args.length == 0) {
            displayCurrentSettings(sender);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("toggleSuggest")) {
            toggleChatSuggestions(sender);
            return;
        }

        if (args.length != 2) {
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
                    int value = CommandBase.parseInt(valueStr, 1); // 最小值为1
                    if (GhostConfig.setForcedBatchSize(value)) {
                         sendSuccessMessage(sender, settingName, value);
                    } else {
                         sendGenericSetError(sender, settingName); // 如果setForcedBatchSize内部返回false
                    }
                } catch (NumberFormatException e) { // parseInt 抛出
                    sendIntegerError(sender, valueStr);
                } catch (CommandException e) { // parseInt 范围检查抛出
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

            case "enableautoplaceonjoin":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableAutoPlaceOnJoin(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "enableautosneakatedge":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableAutoSneakAtEdge(value);
                    sendSuccessMessage(sender, "enableAutoSneakAtEdge", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "autosneakforwardoffset":
                try {
                    double value = CommandBase.parseDouble(valueStr, 0.05, 1.0); // 使用CommandBase解析并进行范围检查
                    GhostConfig.setAutoSneakForwardOffset(value);
                    sendSuccessMessage(sender, "autoSneakForwardOffset", String.format("%.2f", value));
                } catch (NumberFormatException e) { // parseDouble 内部可能抛出
                    sendDoubleError(sender, valueStr);
                } catch (CommandException e) { // parseDouble 范围检查抛出
                    throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.double.range", valueStr, 0.05, 1.0));
                }
                break;

            case "autosneakverticalcheckdepth":
                try {
                    double value = CommandBase.parseDouble(valueStr, 0.1, 3.0);
                    GhostConfig.setAutoSneakVerticalCheckDepth(value);
                    sendSuccessMessage(sender, "autoSneakVerticalCheckDepth", String.format("%.2f", value));
                } catch (NumberFormatException e) {
                    sendDoubleError(sender, valueStr);
                } catch (CommandException e) {
                    throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.double.range", valueStr, 0.1, 3.0));
                }
                break;
            case "enableplayeresp": // 新增 case
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnablePlayerESP(value);
                    sendSuccessMessage(sender, "enablePlayerESP", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
            default:
                // 使用包含所有当前选项的错误消息键
                throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_setting.all_options", settingName));
        }
    }

    private void toggleChatSuggestions(ICommandSender sender) {
        boolean currentState = GhostConfig.enableChatSuggestions;
        boolean newState = !currentState;
        GhostConfig.setEnableChatSuggestions(newState);

        String feedbackKey = newState ? "ghostblock.commands.togglesuggest.enabled" : "ghostblock.commands.togglesuggest.disabled";
        ChatComponentTranslation feedback = new ChatComponentTranslation(feedbackKey); // LangUtil 会处理默认值
        feedback.getChatStyle().setColor(newState ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
        sender.addChatMessage(feedback);
    }

    private void displayCurrentSettings(ICommandSender sender) {
         // 确保LangUtil.translate不会返回null，如果键不存在，它应该返回键本身或一个默认值
         sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.current_settings.header")
                 .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.AQUA)));

         sender.addChatMessage(formatSettingLine("alwaysBatchFill", GhostConfig.alwaysBatchFill));
         sender.addChatMessage(formatSettingLine("forcedBatchSize", GhostConfig.forcedBatchSize));
         sender.addChatMessage(formatSettingLine("enableAutoSave", GhostConfig.enableAutoSave));

         String displayFileName = GhostConfig.defaultSaveFileName;
         if (displayFileName == null || displayFileName.trim().isEmpty() || "default".equalsIgnoreCase(displayFileName.trim())) {
             displayFileName = "(" + LangUtil.translate("ghostblock.commands.gconfig.current_settings.default_filename_placeholder") + ")";
         } else {
             displayFileName = "'" + displayFileName + "'";
         }
         sender.addChatMessage(formatSettingLine("defaultSaveName", displayFileName));

         sender.addChatMessage(formatSettingLine("enableChatSuggestions", GhostConfig.enableChatSuggestions));
         sender.addChatMessage(formatSettingLine("enableAutoPlaceOnJoin", GhostConfig.enableAutoPlaceOnJoin));
         sender.addChatMessage(formatSettingLine("enableAutoSneakAtEdge", GhostConfig.enableAutoSneakAtEdge));

         // 对double值进行格式化，并确保GhostConfig中的值是有效的
         sender.addChatMessage(formatSettingLine("autoSneakForwardOffset", String.format("%.2f", GhostConfig.autoSneakForwardOffset)));
         sender.addChatMessage(formatSettingLine("autoSneakVerticalCheckDepth", String.format("%.2f", GhostConfig.autoSneakVerticalCheckDepth)));
         sender.addChatMessage(formatSettingLine("enablePlayerESP", GhostConfig.enablePlayerESP)); // 新增

         sender.addChatMessage(new ChatComponentText(" ") ); // 添加空行
         sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.hint_toggle_suggest")
                 .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.DARK_AQUA)));
    }

    private ChatComponentText formatSettingLine(String name, Object value) {
        // 确保 name 和 value 都不是 null
        String valueStr = (value == null) ? "null" : String.valueOf(value);
        String nameStr = (name == null) ? "unknown_setting" : name;

        ChatComponentText line = new ChatComponentText("  " + nameStr + ": ");
        line.getChatStyle().setColor(EnumChatFormatting.GRAY);
        ChatComponentText valueComp = new ChatComponentText(valueStr);
        valueComp.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        line.appendSibling(valueComp);
        return line;
    }

     private void displayHelp(ICommandSender sender) {
         EnumChatFormatting hl = EnumChatFormatting.GOLD;
         EnumChatFormatting tx = EnumChatFormatting.GRAY;
         EnumChatFormatting us = EnumChatFormatting.YELLOW;
         EnumChatFormatting op = EnumChatFormatting.AQUA;

         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.menu")));
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
         sender.addChatMessage(new ChatComponentText(op + "  alwaysBatchFill " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean")));
         sender.addChatMessage(new ChatComponentText(op + "  forcedBatchSize " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.positive_integer")));
         sender.addChatMessage(new ChatComponentText(op + "  enableAutoSave " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean")));
         sender.addChatMessage(new ChatComponentText(op + "  defaultSaveName " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.text")));
         sender.addChatMessage(new ChatComponentText(op + "  enableChatSuggestions " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean")));
         sender.addChatMessage(new ChatComponentText(op + "  enableAutoPlaceOnJoin " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoPlaceOnJoin")));
         sender.addChatMessage(new ChatComponentText(op + "  enableAutoSneakAtEdge " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoSneakAtEdge")));
         sender.addChatMessage(new ChatComponentText(op + "  autoSneakForwardOffset " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.double_range", "0.05-1.0") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.autoSneakForwardOffset")));
         sender.addChatMessage(new ChatComponentText(op + "  autoSneakVerticalCheckDepth " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.double_range", "0.1-3.0") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.autoSneakVerticalCheckDepth")));
         sender.addChatMessage(new ChatComponentText(op + "  enablePlayerESP " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enablePlayerESP"))); // 新增

         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.examples.header")));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableAutoSave true"));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig defaultSaveName my_server_ghosts"));
         sender.addChatMessage(new ChatComponentText(us + "  /gconfig forcedBatchSize 500"));
         sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableAutoPlaceOnJoin")));
         sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableAutoSneakAtEdge")));
         sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.autoSneakForwardOffset")));
         sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.autoSneakVerticalCheckDepth")));
         sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enablePlayerESP"))); // 新增


         sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.aliases") + ": " + hl + String.join(", ", getCommandAliases())));
     }

    private void sendSuccessMessage(ICommandSender sender, String setting, Object value) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.success", setting, value)
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.GREEN)));
    }

    private void sendBooleanError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.boolean", invalidValue));
    }
    private void sendIntegerError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.integer", invalidValue));
    }
     private void sendPositiveIntegerError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.integer.positive", invalidValue));
     }
     private void sendDoubleError(ICommandSender sender, String invalidValue) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_value.double", invalidValue));
     }
     private void sendGenericSetError(ICommandSender sender, String setting) throws CommandException {
         throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.generic_set_failed", setting));
     }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return CommandBase.getListOfStringsMatchingLastWord(args,
                    "help", "alwaysBatchFill", "forcedBatchSize", "enableAutoSave", "defaultSaveName",
                    "enableChatSuggestions", "enableAutoPlaceOnJoin", "enableAutoSneakAtEdge",
                    "autoSneakForwardOffset", "autoSneakVerticalCheckDepth", "enablePlayerESP", // 新增
                    "toggleSuggest");
        }
        else if (args.length == 2) {
            String settingName = args[0].toLowerCase();
            if (settingName.equals("help")) { return Collections.emptyList(); }
            switch (settingName) {
                case "alwaysbatchfill": case "enableautosave": case "enablechatsuggestions":
                case "enableautoplaceonjoin": case "enableautosneakatedge": case "enableplayeresp": // 新增
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
                            if (!worldId.equals(baseWorldId)) {
                                nameSuggestions.add(baseWorldId);
                            }
                             nameSuggestions.add(worldId);
                        }
                    } catch (Exception e) { /*忽略*/ }
                    nameSuggestions = nameSuggestions.stream().distinct().sorted().collect(Collectors.toList());
                    return CommandBase.getListOfStringsMatchingLastWord(args, nameSuggestions);

                case "autosneakforwardoffset":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "0.25", "0.35", "0.5", "0.75", "1.0"); // 添加更多建议
                case "autosneakverticalcheckdepth":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "0.5", "1.0", "1.5", "2.0", "2.5", "3.0"); // 添加更多建议

                case "togglesuggest": return Collections.emptyList();
                default: return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }
}