package com.zihaomc.ghost.commands;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
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
        return 0; // 客户端命令，无需权限
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 对所有客户端玩家可用
    }

    /**
     * 命令处理的核心逻辑。
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        // 如果没有参数，则显示当前所有设置
        if (args.length == 0) {
            displayCurrentSettings(sender);
            return;
        }

        String command = args[0].toLowerCase();

        // 处理 /gconfig help
        if ("help".equalsIgnoreCase(command)) {
            displayHelp(sender);
            return;
        }

        // 处理 /gconfig toggleSuggest (快捷开关)
        if ("togglesuggest".equalsIgnoreCase(command)) {
            toggleChatSuggestions(sender);
            return;
        }

        // --- 修改配置项的逻辑 ---
        String settingName = args[0];
        String settingKey = settingName.toLowerCase();

        // 检查配置项是否存在于 GhostConfig 中定义的更新器映射中
        if (GhostConfig.settingUpdaters.containsKey(settingKey)) {
            // 特殊处理：如果只输入了 API Key 的名称，则清空它
            if (settingKey.equals("niutransapikey") && args.length == 1) {
                GhostConfig.setNiuTransApiKey("");
                sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.gconfig.success.key_cleared"));
                return;
            }

            // 修改配置项至少需要提供一个值
            if (args.length < 2) {
                throw new WrongUsageException(getCommandUsage(sender));
            }

            // 拼接值，以支持带空格的字符串值
            String valueStr = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

            try {
                // 从映射中获取并执行对应的 Lambda 更新器
                GhostConfig.settingUpdaters.get(settingKey).accept(settingName, valueStr);

                // 为特定配置项提供特殊的反馈消息
                if (settingKey.equals("niutransapikey")) {
                    sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.gconfig.success.key_set"));
                } else if (settingKey.equals("enablebedrockminer") && "true".equalsIgnoreCase(valueStr)) {
                    sendSuccessMessage(sender, settingName, valueStr);
                    sender.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.gconfig.fastpiston_autogen"));
                } else {
                    sendSuccessMessage(sender, settingName, valueStr);
                }
            } catch (RuntimeException e) {
                // 捕获并处理在 Lambda 中发生的解析异常
                if (e.getCause() instanceof CommandException) {
                    throw (CommandException) e.getCause();
                }
                throw new CommandException("commands.generic.exception");
            }
        } else {
            // 如果配置项不存在
            throw new CommandException(LangUtil.translate("ghostblock.commands.gconfig.error.invalid_setting.all_options", settingName));
        }
    }

    /**
     * 快捷切换聊天建议功能的开关。
     */
    private void toggleChatSuggestions(ICommandSender sender) {
        boolean newState = !GhostConfig.ChatFeatures.enableChatSuggestions;
        GhostConfig.setEnableChatSuggestions(newState);

        String feedbackKey = newState ? "ghostblock.commands.gconfig.togglesuggest.enabled" : "ghostblock.commands.gconfig.togglesuggest.disabled";
        ChatComponentTranslation feedback = new ChatComponentTranslation(feedbackKey);
        feedback.getChatStyle().setColor(newState ? EnumChatFormatting.GREEN : EnumChatFormatting.RED);
        sender.addChatMessage(feedback);
    }

    /**
     * 在聊天框中显示所有当前的配置项及其值。
     */
    private void displayCurrentSettings(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.current_settings.header")
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.AQUA)));

        // --- 基础设置 ---
        sender.addChatMessage(formatSettingLine("alwaysBatchFill", GhostConfig.FillCommand.alwaysBatchFill));
        sender.addChatMessage(formatSettingLine("forcedBatchSize", GhostConfig.FillCommand.forcedBatchSize));
        sender.addChatMessage(formatSettingLine("enableAutoSave", GhostConfig.SaveOptions.enableAutoSave));
        
        String displayFileName = GhostConfig.SaveOptions.defaultSaveFileName;
        if (displayFileName == null || displayFileName.trim().isEmpty()) {
            displayFileName = "(" + LangUtil.translate("ghostblock.commands.gconfig.current_settings.default_filename_placeholder") + ")";
        }
        sender.addChatMessage(formatSettingLine("defaultSaveName", displayFileName));

        // --- 功能开关 ---
        sender.addChatMessage(formatSettingLine("enableChatSuggestions", GhostConfig.ChatFeatures.enableChatSuggestions));
        sender.addChatMessage(formatSettingLine("enableAutoPlaceOnJoin", GhostConfig.AutoPlace.enableAutoPlaceOnJoin));
        sender.addChatMessage(formatSettingLine("enableAutoSneakAtEdge", GhostConfig.AutoSneak.enableAutoSneakAtEdge));
        sender.addChatMessage(formatSettingLine("enablePlayerESP", GhostConfig.PlayerESP.enablePlayerESP));
        sender.addChatMessage(formatSettingLine("enableBedrockMiner", GhostConfig.BedrockMiner.enableBedrockMiner));
        sender.addChatMessage(formatSettingLine("fastPistonBreaking", GhostConfig.GameplayTweaks.fastPistonBreaking));

        // --- 翻译设置 ---
        sender.addChatMessage(formatSettingLine("enableAutomaticTranslation", GhostConfig.Translation.enableAutomaticTranslation));
        sender.addChatMessage(formatSettingLine("translationProvider", GhostConfig.Translation.translationProvider));
        sender.addChatMessage(formatSettingLine("translationTargetLang", GhostConfig.Translation.translationTargetLang));

        // --- 自动挖掘设置 ---
        sender.addChatMessage(formatSettingLine("autoMineMiningMode", GhostConfig.AutoMine.miningMode));
        sender.addChatMessage(formatSettingLine("autoMineRotationSpeed", String.format("%.1f", GhostConfig.AutoMine.rotationSpeed)));
        sender.addChatMessage(formatSettingLine("autoMineMithrilOptimization", GhostConfig.AutoMine.enableMithrilOptimization));

        // 提示
        sender.addChatMessage(new ChatComponentText(" "));
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.hint_toggle_suggest")
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.DARK_AQUA)));
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
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.GREEN)));
    }

    /**
     * 显示详细的帮助信息 (已精简示例)。
     */
    private void displayHelp(ICommandSender sender) {
        EnumChatFormatting hl = EnumChatFormatting.GOLD;
        EnumChatFormatting tx = EnumChatFormatting.GRAY;
        EnumChatFormatting us = EnumChatFormatting.YELLOW;
        EnumChatFormatting op = EnumChatFormatting.AQUA;

        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.menu")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.description")));
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.usage.main")));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig <设置项> <值>"));
        
        // 列出主要类型的说明
        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.available_settings")));
        sender.addChatMessage(new ChatComponentText(tx + "(输入 /gconfig 并按 Tab 键查看所有可用选项)"));

        // 仅显示几个关键的参数类型说明，避免刷屏
        sender.addChatMessage(new ChatComponentText(op + "  enable... " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " (true/false)"));
        sender.addChatMessage(new ChatComponentText(op + "  autoMine... " + tx + "数值或模式"));
        sender.addChatMessage(new ChatComponentText(op + "  translation... " + tx + "文本代码"));

        // 精简后的示例
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
                // 为其他数值型选项提供一些常见建议
                case "autominerotationspeed":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "10.0", "45.0", "90.0");
                case "autominetimeout":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "10", "20");
            }
        }
        return Collections.emptyList();
    }

    /**
     * 辅助方法，用于判断一个设置项是否是布尔类型，以便提供正确的 tab 补全。
     */
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
               key.equals("automineenabletoolswitching");
    }
}