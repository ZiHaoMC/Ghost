package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.ghostblock.data.GhostBlockData;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理 /gconfig 命令，用于在游戏中动态修改 Mod 的配置。
 */
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
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.gconfig.success.key_cleared"));
                return;
            }

            if (args.length < 2) {
                throw new WrongUsageException(getCommandUsage(sender));
            }

            String valueStr = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

            try {
                GhostConfig.settingUpdaters.get(settingKey).accept(settingName, valueStr);

                if (settingKey.equals("niutransapikey")) {
                    sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.gconfig.success.key_set"));
                } else if (settingKey.equals("enablebedrockminer") && "true".equalsIgnoreCase(valueStr)) {
                    sendSuccessMessage(sender, settingName, valueStr);
                    sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.gconfig.fastpiston_autogen"));
                } else {
                    sendSuccessMessage(sender, settingName, valueStr);
                }
            } catch (RuntimeException e) {
                if (e.getCause() instanceof CommandException) {
                    throw (CommandException) e.getCause();
                }
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
                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA)));

        sender.addChatMessage(formatSettingLine("alwaysBatchFill", GhostConfig.FillCommand.alwaysBatchFill));
        sender.addChatMessage(formatSettingLine("forcedBatchSize", GhostConfig.FillCommand.forcedBatchSize));
        sender.addChatMessage(formatSettingLine("enableAutoSave", GhostConfig.SaveOptions.enableAutoSave));
        
        String displayFileName = GhostConfig.SaveOptions.defaultSaveFileName;
        if (displayFileName == null || displayFileName.trim().isEmpty()) {
            displayFileName = "(" + LangUtil.translate("ghostblock.commands.gconfig.current_settings.default_filename_placeholder") + ")";
        }
        sender.addChatMessage(formatSettingLine("defaultSaveName", displayFileName));

        sender.addChatMessage(formatSettingLine("enableChatSuggestions", GhostConfig.ChatFeatures.enableChatSuggestions));
        sender.addChatMessage(formatSettingLine("enableAutoPlaceOnJoin", GhostConfig.AutoPlace.enableAutoPlaceOnJoin));
        sender.addChatMessage(formatSettingLine("enableAutoSneakAtEdge", GhostConfig.AutoSneak.enableAutoSneakAtEdge));
        sender.addChatMessage(formatSettingLine("enablePlayerESP", GhostConfig.PlayerESP.enablePlayerESP));
        sender.addChatMessage(formatSettingLine("enableBedrockMiner", GhostConfig.BedrockMiner.enableBedrockMiner));
        sender.addChatMessage(formatSettingLine("fastPistonBreaking", GhostConfig.GameplayTweaks.fastPistonBreaking));

        sender.addChatMessage(formatSettingLine("enableAutomaticTranslation", GhostConfig.Translation.enableAutomaticTranslation));
        sender.addChatMessage(formatSettingLine("translationProvider", GhostConfig.Translation.translationProvider));
        sender.addChatMessage(formatSettingLine("translationTargetLang", GhostConfig.Translation.translationTargetLang));

        sender.addChatMessage(formatSettingLine("autoMineMiningMode", GhostConfig.AutoMine.miningMode));
        sender.addChatMessage(formatSettingLine("autoMineRotationSpeed", String.format("%.1f", GhostConfig.AutoMine.rotationSpeed)));
        sender.addChatMessage(formatSettingLine("autoMineMithrilOptimization", GhostConfig.AutoMine.enableMithrilOptimization));
        sender.addChatMessage(formatSettingLine("autoMineStopOnTimeout", GhostConfig.AutoMine.stopOnTimeout));
        sender.addChatMessage(formatSettingLine("enableDungeonProfit", GhostConfig.Skyblock.enableDungeonProfit));

        sender.addChatMessage(new ChatComponentText(" "));
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.hint_toggle_suggest")
                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_AQUA)));
    }

    private ChatComponentText formatSettingLine(String name, Object value) {
        String valueStr = String.valueOf(value);
        ChatComponentText line = new ChatComponentText("  " + name + ": ");
        line.getChatStyle().setColor(EnumChatFormatting.GRAY);
        ChatComponentText valueComp = new ChatComponentText(valueStr);
        valueComp.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        line.appendSibling(valueComp);
        return line;
    }

    private void sendSuccessMessage(ICommandSender sender, String setting, Object value) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.success", setting, value)
                .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN)));
    }

    private void displayHelp(ICommandSender sender) {
        EnumChatFormatting hl = EnumChatFormatting.GOLD;
        EnumChatFormatting tx = EnumChatFormatting.GRAY;
        EnumChatFormatting us = EnumChatFormatting.YELLOW;
        EnumChatFormatting op = EnumChatFormatting.AQUA;

        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.menu")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.description")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.usage.main")));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig <设置项> <值>"));
        
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.available_settings")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.tab_hint")));

        sender.addChatMessage(new ChatComponentText(op + "  enable... " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " (true/false)"));
        sender.addChatMessage(new ChatComponentText(op + "  autoMine... " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.numeric_or_mode")));
        sender.addChatMessage(new ChatComponentText(op + "  translation... " + tx + LangUtil.translate("ghostblock.commands.gconfig.help.type.lang_code")));

        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.examples.header")));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig enableAutoSave true"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig forcedBatchSize 500"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig translationTargetLang en"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMineMiningMode PACKET_NORMAL"));

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
                case "translationprovider":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "NIUTRANS", "GOOGLE", "BING", "MYMEMORY");
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
                    } catch (Exception ignored) {}
                    nameSuggestions = nameSuggestions.stream().distinct().sorted().collect(Collectors.toList());
                    return CommandBase.getListOfStringsMatchingLastWord(args, nameSuggestions);
                case "automineminingmode":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "SIMULATE", "PACKET_NORMAL", "PACKET_INSTANT");
                case "autominerotationspeed":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "10.0", "45.0", "90.0");
                case "autominetimeout":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "10", "20");
            }
        }
        return Collections.emptyList();
    }

    private boolean isBooleanCommand(String key) {
        return key.startsWith("enable") || key.startsWith("always") || key.startsWith("disable") ||
               key.startsWith("show") || key.startsWith("hide") ||
               key.equals("autoShowCachedTranslation") ||
               key.equals("headlesspistonmode") || key.equals("blinkduringtaskstick") ||
               key.equals("fastpistonbreaking") || key.equals("fixguistatelossonresize") ||
               key.equals("automineserverrotation") || key.equals("automineinstantrotation") ||
               key.equals("autominesneak") || key.equals("automineenablerandommove") ||
               key.equals("automineenablerandomspeed") || key.equals("autominepreventdiggingdown") ||
               key.equals("automineenableveinmining") ||
               key.equals("automineanticheatcheck") ||
               key.equals("autominevoidsafetycheck") ||
               key.equals("autominemithriloptimization") ||
               key.equals("automineenabletoolswitching") ||
               key.equals("autominestopontimeout") ||
               key.equals("enabledungeonprofit");
    }
}