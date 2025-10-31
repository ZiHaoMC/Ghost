package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.commands.utils.CommandHelper;
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
        return LangUtil.translate("ghostblock.commands.gconfig.usage.extended");
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
        if (args.length == 0) {
            displayCurrentSettings(sender);
            return;
        }

        String command = args[0].toLowerCase();
        
        if ("help".equalsIgnoreCase(command)) {
            displayHelp(sender);
            return;
        }

        if ("togglesuggest".equalsIgnoreCase(command)) {
            toggleChatSuggestions(sender);
            return;
        }

        String settingName = args[0];
        String settingKey = settingName.toLowerCase();

        if (GhostConfig.settingUpdaters.containsKey(settingKey)) {
            if (settingKey.equals("niutransapikey") && args.length == 1) {
                GhostConfig.setNiuTransApiKey("");
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.gconfig.success.key_cleared"));
                return;
            }

            if (args.length < 2) {
                throw new WrongUsageException(getCommandUsage(sender));
            }
            
            String valueStr = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

            try {
                // 执行更新
                GhostConfig.settingUpdaters.get(settingKey).accept(settingName, valueStr);
                
                // 特殊消息反馈
                if (settingKey.equals("niutransapikey")) {
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.gconfig.success.key_set"));
                } else if (settingKey.equals("enablebedrockminer") && "true".equalsIgnoreCase(valueStr)) {
                    sendSuccessMessage(sender, settingName, valueStr);
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.gconfig.fastpiston_autogen"));
                } else {
                    sendSuccessMessage(sender, settingName, valueStr);
                }
            } catch (RuntimeException e) {
                // 从 Lambda 表达式中捕获包装的 CommandException 并重新抛出
                if (e.getCause() instanceof CommandException) {
                    throw (CommandException) e.getCause();
                }
                // 捕获其他运行时异常，例如 NumberFormatException
                throw new CommandException("commands.generic.exception");
            }
        } else {
            throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_setting.all_options", settingName));
        }
    }

    private void toggleChatSuggestions(ICommandSender sender) {
        boolean newState = !GhostConfig.ChatFeatures.enableChatSuggestions;
        GhostConfig.setEnableChatSuggestions(newState);

        String feedbackKey = newState ? "ghostblock.commands.gconfig.togglesuggest.enabled" : "ghostblock.commands.gconfig.togglesuggest.disabled";
        ChatComponentTranslation feedback = new ChatComponentTranslation(feedbackKey);
        feedback.getChatStyle().setColor(newState ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
        sender.addChatMessage(feedback);
    }
    
    private void displayCurrentSettings(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.current_settings.header")
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.AQUA)));
        
        sender.addChatMessage(formatSettingLine("alwaysBatchFill", GhostConfig.FillCommand.alwaysBatchFill));
        sender.addChatMessage(formatSettingLine("forcedBatchSize", GhostConfig.FillCommand.forcedBatchSize));
        
        sender.addChatMessage(formatSettingLine("enableAutoSave", GhostConfig.SaveOptions.enableAutoSave));
        String displayFileName = GhostConfig.SaveOptions.defaultSaveFileName;
        if (displayFileName == null || displayFileName.trim().isEmpty()) {
            displayFileName = "(" + LangUtil.translate("ghostblock.commands.gconfig.current_settings.default_filename_placeholder") + ")";
        }
        sender.addChatMessage(formatSettingLine("defaultSaveName", displayFileName));

        sender.addChatMessage(formatSettingLine("enableChatSuggestions", GhostConfig.ChatFeatures.enableChatSuggestions));
        sender.addChatMessage(formatSettingLine("enableCommandHistoryScroll", GhostConfig.ChatFeatures.enableCommandHistoryScroll));
        sender.addChatMessage(formatSettingLine("disableTwitchAtKey", GhostConfig.ChatFeatures.disableTwitchAtKey));

        sender.addChatMessage(formatSettingLine("enableAutoPlaceOnJoin", GhostConfig.AutoPlace.enableAutoPlaceOnJoin));

        sender.addChatMessage(formatSettingLine("enableAutoSneakAtEdge", GhostConfig.AutoSneak.enableAutoSneakAtEdge));
        sender.addChatMessage(formatSettingLine("autoSneakForwardOffset", String.format("%.2f", GhostConfig.AutoSneak.autoSneakForwardOffset)));
        sender.addChatMessage(formatSettingLine("autoSneakVerticalCheckDepth", String.format("%.2f", GhostConfig.AutoSneak.autoSneakVerticalCheckDepth)));

        sender.addChatMessage(formatSettingLine("enablePlayerESP", GhostConfig.PlayerESP.enablePlayerESP));
        
        sender.addChatMessage(formatSettingLine("enableBedrockMiner", GhostConfig.BedrockMiner.enableBedrockMiner));

        sender.addChatMessage(formatSettingLine("fastPistonBreaking", GhostConfig.GameplayTweaks.fastPistonBreaking));
        sender.addChatMessage(formatSettingLine("hideArrowsOnPlayers", GhostConfig.GameplayTweaks.hideArrowsOnPlayers));

        sender.addChatMessage(formatSettingLine("enableChatTranslation", GhostConfig.Translation.enableChatTranslation));
        sender.addChatMessage(formatSettingLine("enableSignTranslation", GhostConfig.Translation.enableSignTranslation));
        sender.addChatMessage(formatSettingLine("enableItemTranslation", GhostConfig.Translation.enableItemTranslation));
        sender.addChatMessage(formatSettingLine("enableAutomaticTranslation", GhostConfig.Translation.enableAutomaticTranslation));
        sender.addChatMessage(formatSettingLine("autoShowCachedTranslation", GhostConfig.Translation.autoShowCachedTranslation));
        sender.addChatMessage(formatSettingLine("showTranslationOnly", GhostConfig.Translation.showTranslationOnly));
        sender.addChatMessage(formatSettingLine("hideTranslationKeybindTooltip", GhostConfig.Translation.hideTranslationKeybindTooltip));
        String apiKeyDisplay = (GhostConfig.Translation.niuTransApiKey != null && !GhostConfig.Translation.niuTransApiKey.isEmpty()) ?
                "******" + GhostConfig.Translation.niuTransApiKey.substring(Math.max(0, GhostConfig.Translation.niuTransApiKey.length() - 4)) :
                LangUtil.translate("ghostblock.commands.gconfig.not_set");
        sender.addChatMessage(formatSettingLine("niuTransApiKey", apiKeyDisplay));
        sender.addChatMessage(formatSettingLine("translationSourceLang", GhostConfig.Translation.translationSourceLang));
        sender.addChatMessage(formatSettingLine("translationTargetLang", GhostConfig.Translation.translationTargetLang));

        sender.addChatMessage(formatSettingLine("enableNoteFeature", GhostConfig.NoteTaking.enableNoteFeature));
        sender.addChatMessage(formatSettingLine("enableAdvancedEditing", GhostConfig.NoteTaking.enableAdvancedEditing));
        sender.addChatMessage(formatSettingLine("enableMarkdownRendering", GhostConfig.NoteTaking.enableMarkdownRendering));
        sender.addChatMessage(formatSettingLine("enableColorRendering", GhostConfig.NoteTaking.enableColorRendering));
        sender.addChatMessage(formatSettingLine("enableAmpersandColorCodes", GhostConfig.NoteTaking.enableAmpersandColorCodes));

        sender.addChatMessage(formatSettingLine("fixGuiStateLossOnResize", GhostConfig.GuiTweaks.fixGuiStateLossOnResize));

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

    private void sendSuccessMessage(ICommandSender sender, String setting, Object value) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.success", setting, value)
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.GREEN)));
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
        sender.addChatMessage(new ChatComponentText(op + "  disableTwitchAtKey " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.disableTwitchAtKey")));
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
        sender.addChatMessage(new ChatComponentText(op + "  enableColorRendering " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableColorRendering")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAmpersandColorCodes " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableAmpersandColorCodes")));
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

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            List<String> settingNames = new ArrayList<>(GhostConfig.settingUpdaters.keySet());
            settingNames.add("help");
            settingNames.add("toggleSuggest");
            Collections.sort(settingNames);
            return CommandBase.getListOfStringsMatchingLastWord(args, settingNames);
        } else if (args.length == 2) {
            String settingName = args[0].toLowerCase();
            
            if (isBooleanCommand(settingName)) {
                return CommandBase.getListOfStringsMatchingLastWord(args, "true", "false");
            }
            
            switch (settingName) {
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
            }
        }
        return Collections.emptyList();
    }
    
    // --- 类型检查辅助方法 ---
    private boolean isBooleanCommand(String key) {
        return key.startsWith("enable") || key.startsWith("always") || key.startsWith("disable") ||
               key.startsWith("auto") || key.startsWith("show") || key.startsWith("hide") ||
               key.equals("headlesspistonmode") || key.equals("blinkduringtaskstick") ||
               key.equals("fastpistonbreaking") || key.equals("fixguistatelossonresize");
    }
}