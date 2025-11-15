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
     * 根据参数分发到不同的处理方法，如显示设置、显示帮助或修改设置。
     * @param sender 命令发送者
     * @param args 命令参数
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
                sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.YELLOW, "ghostblock.commands.gconfig.success.key_cleared"));
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
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.GREEN, "ghostblock.commands.gconfig.success.key_set"));
                } else if (settingKey.equals("enablebedrockminer") && "true".equalsIgnoreCase(valueStr)) {
                    sendSuccessMessage(sender, settingName, valueStr);
                    sender.addChatMessage(CommandHelper.formatMessage(EnumChatFormatting.AQUA, "ghostblock.commands.gconfig.fastpiston_autogen"));
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

        sender.addChatMessage(formatSettingLine("autoMineRotationSpeed", String.format("%.1f", GhostConfig.AutoMine.rotationSpeed)));
        sender.addChatMessage(formatSettingLine("autoMineSpeedVariability", String.format("%.1f", GhostConfig.AutoMine.rotationSpeedVariability)));
        sender.addChatMessage(formatSettingLine("autoMineEnableRandomSpeed", GhostConfig.AutoMine.enableRandomRotationSpeed));
        sender.addChatMessage(formatSettingLine("autoMineMaxReachDistance", String.format("%.1f", GhostConfig.AutoMine.maxReachDistance)));
        sender.addChatMessage(formatSettingLine("autoMineSearchRadius", GhostConfig.AutoMine.searchRadius));
        sender.addChatMessage(formatSettingLine("autoMineTimeout", GhostConfig.AutoMine.mineTimeoutSeconds));
        sender.addChatMessage(formatSettingLine("autoMinePreventDiggingDown", GhostConfig.AutoMine.preventDiggingDown));
        sender.addChatMessage(formatSettingLine("autoMineEnableVeinMining", GhostConfig.AutoMine.enableVeinMining));
        sender.addChatMessage(formatSettingLine("autoMineInstantRotation", GhostConfig.AutoMine.instantRotation));
        sender.addChatMessage(formatSettingLine("autoMineServerRotation", GhostConfig.AutoMine.serverRotation));
        sender.addChatMessage(formatSettingLine("autoMineSneak", GhostConfig.AutoMine.sneakOnMine));
        sender.addChatMessage(formatSettingLine("autoMineEnableRandomMove", GhostConfig.AutoMine.enableRandomMovements));
        sender.addChatMessage(formatSettingLine("autoMineRandomMoveInterval", GhostConfig.AutoMine.randomMoveInterval));
        sender.addChatMessage(formatSettingLine("autoMineRandomMoveDuration", GhostConfig.AutoMine.randomMoveDuration));
        sender.addChatMessage(formatSettingLine("autoMineRandomMoveIntervalVariability", GhostConfig.AutoMine.randomMoveIntervalVariability));
        sender.addChatMessage(formatSettingLine("autoMineMiningMode", GhostConfig.AutoMine.miningMode));
        sender.addChatMessage(formatSettingLine("autoMineAntiCheatCheck", GhostConfig.AutoMine.antiCheatCheck));
        // --- 新增显示 ---
        sender.addChatMessage(formatSettingLine("autoMineVoidSafetyCheck", GhostConfig.AutoMine.enableVoidSafetyCheck));
        sender.addChatMessage(formatSettingLine("autoMineVoidSafetyYLimit", GhostConfig.AutoMine.voidSafetyYLimit));
        
        sender.addChatMessage(formatSettingLine("autoCraftPlacementDelay", GhostConfig.AutoCraft.autoCraftPlacementDelayTicks));
        sender.addChatMessage(formatSettingLine("autoCraftCycleDelay", GhostConfig.AutoCraft.autoCraftCycleDelayTicks));
        sender.addChatMessage(formatSettingLine("autoCraftMenuOpenDelay", GhostConfig.AutoCraft.autoCraftMenuOpenDelayTicks));
        sender.addChatMessage(formatSettingLine("autoCraftTableOpenDelay", GhostConfig.AutoCraft.autoCraftTableOpenDelayTicks));
        sender.addChatMessage(formatSettingLine("autoCraftPickupStashWait", GhostConfig.AutoCraft.autoCraftPickupStashWaitTicks));

        sender.addChatMessage(new ChatComponentText(" "));
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.hint_toggle_suggest")
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.DARK_AQUA)));
    }

    /**
     * 格式化单行配置项的显示。
     * @param name 配置项名称
     * @param value 配置项的值
     * @return 格式化后的聊天组件
     */
    private ChatComponentText formatSettingLine(String name, Object value) {
        String valueStr = String.valueOf(value);
        ChatComponentText line = new ChatComponentText("  " + name + ": ");
        line.getChatStyle().setColor(EnumChatFormatting.GRAY);
        ChatComponentText valueComp = new ChatComponentText(valueStr);
        valueComp.getChatStyle().setColor(EnumChatFormatting.YELLOW);
        line.appendSibling(valueComp);
        return line;
    }

    /**
     * 发送一个标准的成功消息。
     */
    private void sendSuccessMessage(ICommandSender sender, String setting, Object value) {
        sender.addChatMessage(new ChatComponentTranslation("ghostblock.commands.gconfig.success", setting, value)
                .setChatStyle(new ChatComponentText("").getChatStyle().setColor(EnumChatFormatting.GREEN)));
    }

    /**
     * 显示详细的帮助信息，包括所有可用配置项和示例。
     */
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
        sender.addChatMessage(new ChatComponentText(op + "  alwaysBatchFill " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean")));
        sender.addChatMessage(new ChatComponentText(op + "  forcedBatchSize " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.positive_integer")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutoSave " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean")));
        sender.addChatMessage(new ChatComponentText(op + "  defaultSaveName " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.text")));
        sender.addChatMessage(new ChatComponentText(op + "  enableChatSuggestions " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableChatSuggestions")));
        sender.addChatMessage(new ChatComponentText(op + "  enableCommandHistoryScroll " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableCommandHistoryScroll")));
        sender.addChatMessage(new ChatComponentText(op + "  disableTwitchAtKey " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.disableTwitchAtKey")));
        sender.addChatMessage(new ChatComponentText(op + "  enableChatTranslation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableChatTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  enableSignTranslation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableSignTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  enableItemTranslation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableItemTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutomaticTranslation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableAutomaticTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  autoShowCachedTranslation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoShowCachedTranslation")));
        sender.addChatMessage(new ChatComponentText(op + "  showTranslationOnly " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.showTranslationOnly")));
        sender.addChatMessage(new ChatComponentText(op + "  hideTranslationKeybindTooltip " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.hideTranslationKeybindTooltip")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutoPlaceOnJoin " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoPlaceOnJoin")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAutoSneakAtEdge " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableAutoSneakAtEdge")));
        sender.addChatMessage(new ChatComponentText(op + "  autoSneakForwardOffset " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.double_range", "0.05-1.0") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.autoSneakForwardOffset")));
        sender.addChatMessage(new ChatComponentText(op + "  autoSneakVerticalCheckDepth " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.double_range", "0.1-3.0") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.autoSneakVerticalCheckDepth")));
        sender.addChatMessage(new ChatComponentText(op + "  enablePlayerESP " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enablePlayerESP")));
        sender.addChatMessage(new ChatComponentText(op + "  enableBedrockMiner " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.enableBedrockMiner")));
        sender.addChatMessage(new ChatComponentText(op + "  fastPistonBreaking " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.fastPistonBreaking")));
        sender.addChatMessage(new ChatComponentText(op + "  hideArrowsOnPlayers " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.hideArrowsOnPlayers")));
        sender.addChatMessage(new ChatComponentText(op + "  enableNoteFeature " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableNoteFeature")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAdvancedEditing " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableAdvancedEditing")));
        sender.addChatMessage(new ChatComponentText(op + "  enableMarkdownRendering " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableMarkdownRendering")));
        sender.addChatMessage(new ChatComponentText(op + "  enableColorRendering " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableColorRendering")));
        sender.addChatMessage(new ChatComponentText(op + "  enableAmpersandColorCodes " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.enableAmpersandColorCodes")));
        sender.addChatMessage(new ChatComponentText(op + "  fixGuiStateLossOnResize " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.fixGuiStateLossOnResize")));
        sender.addChatMessage(new ChatComponentText(op + "  niuTransApiKey " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.text") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.niuTransApiKey")));
        sender.addChatMessage(new ChatComponentText(op + "  translationSourceLang " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.text") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.translationSourceLang")));
        sender.addChatMessage(new ChatComponentText(op + "  translationTargetLang " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.text") + " - " + LangUtil.translate("ghostblock.commands.gconfig.help.setting.translationTargetLang")));
        
        sender.addChatMessage(new ChatComponentText(op + "  autoMineRotationSpeed " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.double_range", "1.0-180.0") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineRotationSpeed")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineSpeedVariability " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.double_range", "0.0-20.0") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineSpeedVariability")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineEnableRandomSpeed " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineEnableRandomSpeed")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineMaxReachDistance " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.double_range", "1.0-6.0") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineMaxReach")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineSearchRadius " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "3-15") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineSearchRadius")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineTimeout " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "2-30") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineTimeout")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMinePreventDiggingDown " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMinePreventDiggingDown")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineEnableVeinMining " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineEnableVeinMining")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineInstantRotation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineInstantRotation")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineServerRotation " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineServerRotation")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineSneak " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineSneak")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineEnableRandomMove " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineEnableRandomMove")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineRandomMoveInterval " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "10-400") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineRandomMoveInterval")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineRandomMoveDuration " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "1-20") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineRandomMoveDuration")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineRandomMoveIntervalVariability " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "0-1000") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineRandomMoveIntervalVariability")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineMiningMode " + tx + "(SIMULATE/PACKET_NORMAL/PACKET_INSTANT) - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineMiningMode")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineAntiCheatCheck " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineAntiCheatCheck")));
        // --- 新增显示 ---
        sender.addChatMessage(new ChatComponentText(op + "  autoMineVoidSafetyCheck " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.boolean") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineVoidSafetyCheck")));
        sender.addChatMessage(new ChatComponentText(op + "  autoMineVoidSafetyYLimit " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "0-255") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoMineVoidSafetyYLimit")));
        
        sender.addChatMessage(new ChatComponentText(op + "  autoCraftPlacementDelay " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "1-100") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoCraftPlacementDelay")));
        sender.addChatMessage(new ChatComponentText(op + "  autoCraftCycleDelay " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "1-200") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoCraftCycleDelay")));
        sender.addChatMessage(new ChatComponentText(op + "  autoCraftMenuOpenDelay " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "1-100") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoCraftMenuOpenDelay")));
        sender.addChatMessage(new ChatComponentText(op + "  autoCraftTableOpenDelay " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "1-100") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoCraftTableOpenDelay")));
        sender.addChatMessage(new ChatComponentText(op + "  autoCraftPickupStashWait " + tx + LangUtil.translate("ghost.commands.gconfig.help.type.integer_range", "10-200") + " - " + LangUtil.translate("ghost.commands.gconfig.help.setting.autoCraftPickupStashWait")));


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
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMineRotationSpeed 10.0"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMineTimeout 10"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMineSpeedVariability 5.0"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMinePreventDiggingDown false"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMineRandomMoveInterval 200"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoMineMiningMode PACKET_NORMAL"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoCraftPlacementDelay 5"));
        sender.addChatMessage(new ChatComponentText(us + "  /gconfig autoCraftCycleDelay 20"));

        sender.addChatMessage(new ChatComponentText(tx + LangUtil.translate("ghostblock.commands.gconfig.help.aliases") + ": " + hl + String.join(", ", getCommandAliases())));
    }

    /**
     * 提供命令的 Tab 自动补全功能。
     */
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
                    } catch (Exception ignored) {}
                    nameSuggestions = nameSuggestions.stream().distinct().sorted().collect(Collectors.toList());
                    return CommandBase.getListOfStringsMatchingLastWord(args, nameSuggestions);
                case "autosneakforwardoffset":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "0.25", "0.35", "0.5", "0.75", "1.0");
                case "autosneakverticalcheckdepth":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "0.5", "1.0", "1.5", "2.0", "2.5", "3.0");
                case "autominerotationspeed":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "10.0", "20.0", "30.0", "45.0", "90.0");
                case "autominespeedvariability":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "2.5", "5.0", "10.0");
                case "automaxreachdistance":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "3.0", "4.0", "4.5", "5.0", "6.0");
                case "autominerearchradius":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "7", "10", "15");
                case "autominetimeout":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "7", "10", "15");
                case "autominerandommoveinterval":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "20", "100", "200", "300");
                case "autominerandommoveduration":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "2", "5", "10", "20");
                case "autominerandommoveintervalvariability":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "10", "20", "50", "100");
                case "automineminingmode":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "SIMULATE", "PACKET_NORMAL", "PACKET_INSTANT");
                // --- 新增 ---
                case "autominevoidsafetylimit":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "10", "20");
                
                case "autocraftplacementdelay":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "1", "3", "5", "10");
                case "autocraftcycledelay":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "10", "15", "20");
                case "autocraftmenuopendelay":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "10", "15", "20");
                case "autocrafttableopendelay":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "10", "15", "20");
                case "autocraftpickupstashwait":
                    return CommandBase.getListOfStringsMatchingLastWord(args, "5", "10", "20");
            }
        }
        return Collections.emptyList();
    }
    
    /**
     * 辅助方法，用于判断一个设置项是否是布尔类型，以便提供正确的 tab 补全。
     * @param key 设置项的名称 (小写)
     * @return 如果是布尔类型则为 true
     */
    private boolean isBooleanCommand(String key) {
        // 使用精确的key来判断，避免误判
        return key.startsWith("enable") || key.startsWith("always") || key.startsWith("disable") ||
               key.startsWith("show") || key.startsWith("hide") ||
               // auto开头的布尔值需要单独列出
               key.equals("autoShowCachedTranslation") || 
               key.equals("headlesspistonmode") || key.equals("blinkduringtaskstick") ||
               key.equals("fastpistonbreaking") || key.equals("fixguistatelossonresize") ||
               key.equals("automineserverrotation") || key.equals("automineinstantrotation") ||
               key.equals("autominesneak") || key.equals("automineenablerandommove") ||
               key.equals("automineenablerandomspeed") || key.equals("autominepreventdiggingdown") ||
               key.equals("automineenableveinmining") ||
               key.equals("automineanticheatcheck") ||
               key.equals("autominevoidsafetycheck"); // 新增
    }
}