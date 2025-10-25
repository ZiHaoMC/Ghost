package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.data.GhostBlockData;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

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

        if (args.length < 2 && !args[0].equalsIgnoreCase("niutransapikey")) {
            throw new WrongUsageException(getCommandUsage(sender) + " - " + LangUtil.translate("ghostblock.commands.gconfig.extra_usage_toggle", "Use '/gconfig toggleSuggest' to quickly toggle suggestions."));
        }

        String settingName = args[0].toLowerCase();
        String valueStr = (args.length > 1) ? args[1] : "";

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
            
            case "enablecommandhistoryscroll":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableCommandHistoryScroll(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
            
            case "enablechattranslation":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableChatTranslation(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "enablesigntranslation":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableSignTranslation(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "enableitemtranslation":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableItemTranslation(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
            
            case "enableautomatictranslation":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableAutomaticTranslation(value);
                    sendSuccessMessage(sender, "enableAutomaticTranslation", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "autoshowcachedtranslation":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setAutoShowCachedTranslation(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "showtranslationonly":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setShowTranslationOnly(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "hidetranslationkeybindtooltip":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setHideTranslationKeybindTooltip(value);
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
            
            case "enablenotefeature":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableNoteFeature(value);
                    sendSuccessMessage(sender, "enableNoteFeature", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "enableadvancedediting":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableAdvancedEditing(value);
                    sendSuccessMessage(sender, "enableAdvancedEditing", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "enablemarkdownrendering":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableMarkdownRendering(value);
                    sendSuccessMessage(sender, "enableMarkdownRendering", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
            
            // 为新增的配置项添加指令处理
            case "enablecolorrendering":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableColorRendering(value);
                    sendSuccessMessage(sender, "enableColorRendering", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "autosneakforwardoffset":
                try {
                    double value = CommandBase.parseDouble(valueStr, 0.05, 1.0);
                    GhostConfig.setAutoSneakForwardOffset(value);
                    sendSuccessMessage(sender, "autoSneakForwardOffset", String.format("%.2f", value));
                } catch (NumberFormatException e) {
                    sendDoubleError(sender, valueStr);
                } catch (CommandException e) {
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

            case "enableplayeresp":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnablePlayerESP(value);
                    sendSuccessMessage(sender, "enablePlayerESP", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
            
            case "enablebedrockminer":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setEnableBedrockMiner(value);
                    sendSuccessMessage(sender, "enableBedrockMiner", value);
                    if (value) {
                        sender.addChatMessage(GhostBlockCommand.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.gconfig.fastpiston_autogen"));
                    }
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
                
            case "fastpistonbreaking":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setFastPistonBreaking(value, true);
                    sendSuccessMessage(sender, "fastPistonBreaking", value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;
            
            case "hidearrowsonplayers":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setHideArrowsOnPlayers(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "fixguistatelossonresize":
                try {
                    boolean value = CommandBase.parseBoolean(valueStr);
                    GhostConfig.setFixGuiStateLossOnResize(value);
                    sendSuccessMessage(sender, settingName, value);
                } catch (CommandException e) {
                    sendBooleanError(sender, valueStr);
                }
                break;

            case "niutransapikey":
                String keyToSet = (args.length > 1) ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
                GhostConfig.setNiuTransApiKey(keyToSet);
                if (keyToSet.trim().isEmpty()) {
                    sender.addChatMessage(GhostBlockCommand.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.gconfig.success.key_cleared"));
                } else {
                    sender.addChatMessage(GhostBlockCommand.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.gconfig.success.key_set"));
                }
                break;

            case "translationsourcelang":
                GhostConfig.setTranslationSourceLang(valueStr);
                sendSuccessMessage(sender, settingName, valueStr);
                break;

            case "translationtargetlang":
                GhostConfig.setTranslationTargetLang(valueStr);
                sendSuccessMessage(sender, settingName, valueStr);
                break;

            default:
                throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_setting.all_options", settingName));
        }
    }

    private void toggleChatSuggestions(ICommandSender sender) {
        boolean currentState = GhostConfig.enableChatSuggestions;
        boolean newState = !currentState;
        GhostConfig.setEnableChatSuggestions(newState);

        String feedbackKey = newState ? "ghostblock.commands.gconfig.togglesuggest.enabled" : "ghostblock.commands.gconfig.togglesuggest.disabled";
        ChatComponentTranslation feedback = new ChatComponentTranslation(feedbackKey);
        feedback.getChatStyle().setColor(newState ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
        sender.addChatMessage(feedback);
    }

    private void displayCurrentSettings(ICommandSender sender) {
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
        sender.addChatMessage(formatSettingLine("enableCommandHistoryScroll", GhostConfig.enableCommandHistoryScroll));
        sender.addChatMessage(formatSettingLine("enableChatTranslation", GhostConfig.enableChatTranslation));
        sender.addChatMessage(formatSettingLine("enableSignTranslation", GhostConfig.enableSignTranslation));
        sender.addChatMessage(formatSettingLine("enableItemTranslation", GhostConfig.enableItemTranslation));
        sender.addChatMessage(formatSettingLine("enableAutomaticTranslation", GhostConfig.enableAutomaticTranslation));
        sender.addChatMessage(formatSettingLine("autoShowCachedTranslation", GhostConfig.autoShowCachedTranslation));
        sender.addChatMessage(formatSettingLine("showTranslationOnly", GhostConfig.showTranslationOnly));
        sender.addChatMessage(formatSettingLine("hideTranslationKeybindTooltip", GhostConfig.hideTranslationKeybindTooltip));
        sender.addChatMessage(formatSettingLine("enableAutoPlaceOnJoin", GhostConfig.enableAutoPlaceOnJoin));
        sender.addChatMessage(formatSettingLine("enableAutoSneakAtEdge", GhostConfig.enableAutoSneakAtEdge));
        sender.addChatMessage(formatSettingLine("autoSneakForwardOffset", String.format("%.2f", GhostConfig.autoSneakForwardOffset)));
        sender.addChatMessage(formatSettingLine("autoSneakVerticalCheckDepth", String.format("%.2f", GhostConfig.autoSneakVerticalCheckDepth)));
        sender.addChatMessage(formatSettingLine("enablePlayerESP", GhostConfig.enablePlayerESP));
        sender.addChatMessage(formatSettingLine("enableBedrockMiner", GhostConfig.enableBedrockMiner));
        sender.addChatMessage(formatSettingLine("fastPistonBreaking", GhostConfig.fastPistonBreaking));
        sender.addChatMessage(formatSettingLine("hideArrowsOnPlayers", GhostConfig.hideArrowsOnPlayers));
        sender.addChatMessage(formatSettingLine("enableNoteFeature", GhostConfig.enableNoteFeature));
        sender.addChatMessage(formatSettingLine("enableAdvancedEditing", GhostConfig.enableAdvancedEditing));
        sender.addChatMessage(formatSettingLine("enableMarkdownRendering", GhostConfig.enableMarkdownRendering));
        sender.addChatMessage(formatSettingLine("enableColorRendering", GhostConfig.enableColorRendering)); // 显示新增配置项的状态
        sender.addChatMessage(formatSettingLine("fixGuiStateLossOnResize", GhostConfig.fixGuiStateLossOnResize));

        String apiKeyDisplay = (GhostConfig.niuTransApiKey != null && !GhostConfig.niuTransApiKey.isEmpty()) ?
                "******" + GhostConfig.niuTransApiKey.substring(Math.max(0, GhostConfig.niuTransApiKey.length() - 4)) :
                LangUtil.translate("ghostblock.commands.gconfig.not_set");
        sender.addChatMessage(formatSettingLine("niuTransApiKey", apiKeyDisplay));
        sender.addChatMessage(formatSettingLine("translationSourceLang", GhostConfig.translationSourceLang));
        sender.addChatMessage(formatSettingLine("translationTargetLang", GhostConfig.translationTargetLang));

        sender.addChatMessage(new ChatComponentText(" "));
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.hint_toggle_suggest")
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.DARK_AQUA)));
    }

    private ChatComponentText formatSettingLine(String name, Object value) {
        String valueStr = String.valueOf(value);
        String nameStr = name;

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
        sender.addChatMessage(new ChatComponentText(op + "  enableChatSuggestions " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableChatSuggestions")));
        sender.addChatMessage(new ChatComponentText(op + "  enableCommandHistoryScroll " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableCommandHistoryScroll")));
        sender.addChatMessage(new ChatComponentText(op + "  enableChatTranslation " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableChatTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  enableSignTranslation " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableSignTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  enableItemTranslation " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableItemTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutomaticTranslation " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableAutomaticTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  autoShowCachedTranslation " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoShowCachedTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  showTranslationOnly " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.showTranslationOnly")));
        sender.addChatMessage(new ChatComponentText(op + "  hideTranslationKeybindTooltip " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.hideTranslationKeybindTooltip")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutoPlaceOnJoin " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoPlaceOnJoin")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutoSneakAtEdge " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoSneakAtEdge")));
        sender.addChatMessage(new ChatComponentText(op + "  autoSneakForwardOffset " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.double_range", "0.05-1.0") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.autoSneakForwardOffset")));
        sender.addChatMessage(new ChatComponentText(op + "  autoSneakVerticalCheckDepth " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.double_range", "0.1-3.0") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.autoSneakVerticalCheckDepth")));
        sender.addChatMessage(new ChatComponentText(op + "  enablePlayerESP " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enablePlayerESP")));
        sender.addChatMessage(new ChatComponentText(op + "  enableBedrockMiner " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableBedrockMiner")));
        sender.addChatMessage(new ChatComponentText(op + "  fastPistonBreaking " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.fastPistonBreaking")));
        sender.addChatMessage(new ChatComponentText(op + "  hideArrowsOnPlayers " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.hideArrowsOnPlayers")));
        sender.addChatMessage(new ChatComponentText(op + "  enableNoteFeature " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableNoteFeature")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAdvancedEditing " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableAdvancedEditing")));
        sender.addChatMessage(new ChatComponentText(op + "  enableMarkdownRendering " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableMarkdownRendering")));
        sender.addChatMessage(new ChatComponentText(op + "  enableColorRendering " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableColorRendering"))); // 新增帮助条目
        sender.addChatMessage(new ChatComponentText(op + "  fixGuiStateLossOnResize " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.fixGuiStateLossOnResize")));
        sender.addChatMessage(new ChatComponentText(op + "  niuTransApiKey " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.text") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.niuTransApiKey")));
        sender.addChatMessage(new ChatComponentText(op + "  translationSourceLang " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.text") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.translationSourceLang")));
        sender.addChatMessage(new ChatComponentText(op + "  translationTargetLang " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.text") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.translationTargetLang")));

        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.examples.header")));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableAutoSave true"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig defaultSaveName my_server_ghosts"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig forcedBatchSize 500"));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableChatSuggestions")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableCommandHistoryScroll")));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableChatTranslation true"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableSignTranslation true"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableItemTranslation true"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableAutomaticTranslation true"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoShowCachedTranslation false"));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.showTranslationOnly")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.hideTranslationKeybindTooltip")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableAutoPlaceOnJoin")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableAutoSneakAtEdge")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.autoSneakForwardOffset")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.autoSneakVerticalCheckDepth")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enablePlayerESP")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.enableBedrockMiner")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.fastPistonBreaking")));
        sender.addChatMessage(new ChatComponentText(us + "  " + LangUtil.translate("ghostblock.commands.gconfig.help.example.hideArrowsOnPlayers")));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig fixGuiStateLossOnResize false"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig niuTransApiKey your_api_key_here"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig translationTargetLang en"));

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
                    "enableChatSuggestions", "enableCommandHistoryScroll", "enableChatTranslation", "enableSignTranslation", "enableItemTranslation", 
                    "enableAutomaticTranslation", "autoShowCachedTranslation", "showTranslationOnly", "hideTranslationKeybindTooltip", "enableAutoPlaceOnJoin", "enableAutoSneakAtEdge",
                    "autoSneakForwardOffset", "autoSneakVerticalCheckDepth", "enablePlayerESP",
                    "enableBedrockMiner", "fastPistonBreaking", "hideArrowsOnPlayers", "enableNoteFeature",
                    "enableAdvancedEditing", "enableMarkdownRendering", "enableColorRendering", "fixGuiStateLossOnResize", // 新增 Tab 补全
                    "niuTransApiKey", "translationSourceLang", "translationTargetLang",
                    "toggleSuggest");
        } else if (args.length == 2) {
            String settingName = args[0].toLowerCase();
            if (settingName.equals("help")) {
                return Collections.emptyList();
            }
            switch (settingName) {
                case "alwaysbatchfill":
                case "enableautosave":
                case "enablechatsuggestions":
                case "enablecommandhistoryscroll":
                case "enablechattranslation":
                case "enablesigntranslation":
                case "enableitemtranslation":
                case "enableautomatictranslation":
                case "autoshowcachedtranslation":
                case "showtranslationonly":
                case "hidetranslationkeybindtooltip":
                case "enableautoplaceonjoin":
                case "enableautosneakatedge":
                case "enableplayeresp":
                case "enablebedrockminer":
                case "fastpistonbreaking":
                case "hidearrowsonplayers":
                case "enablenotefeature":
                case "enableadvancedediting":
                case "enablemarkdownrendering":
                case "enablecolorrendering": // 新增 Tab 补全
                case "fixguistatelossonresize":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "true", "false");

                case "translationsourcelang":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "auto", "zh", "en", "ja", "ko", "fr", "ru", "de");
                case "translationtargetlang":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "zh", "en", "ja", "ko", "fr", "ru", "de");

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
                    return CommandBase.getListOfStringsMatchingLastWord(args, "0.25", "0.35", "0.5", "0.75", "1.0");
                case "autosneakverticalcheckdepth":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "0.5", "1.0", "1.5", "2.0", "2.5", "3.0");

                case "togglesuggest":
                case "niutransapikey":
                    return Collections.emptyList();
                default:
                    return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }
}