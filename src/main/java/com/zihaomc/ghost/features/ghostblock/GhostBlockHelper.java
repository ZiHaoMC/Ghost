package com.zihaomc.ghost.features.ghostblock;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockState.BlockStateProxy;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.data.GhostBlockData;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 包含 GhostBlockCommand 及其处理器所需的各种静态辅助方法。
 */
public class GhostBlockHelper {

    // --- 颜色常量 ---
    public static final EnumChatFormatting LABEL_COLOR = EnumChatFormatting.GRAY;
    public static final EnumChatFormatting VALUE_COLOR = EnumChatFormatting.YELLOW;
    public static final EnumChatFormatting FINISH_COLOR = EnumChatFormatting.GREEN;

    /**
     * 创建翻译源切换按钮的文本组件。
     */
    public static IChatComponent createProviderSwitchButtons(String sourceText, String currentProvider) {
        if (!GhostConfig.Translation.showProviderSwitchButtons) {
            return new ChatComponentText("");
        }

        ChatComponentText buttons = new ChatComponentText(" "); 
        String[] providers = {"GOOGLE", "BING", "MYMEMORY", "NIUTRANS"};
        
        String escapedText = sourceText.replace("\"", "\\\"");

        for (String provider : providers) {
            if (provider.equalsIgnoreCase(currentProvider)) continue;

            String abbr = getProviderAbbreviation(provider);
            ChatComponentText button = new ChatComponentText("[" + abbr + "] ");
            String command = "/gtranslate -p " + provider + " \"" + escapedText + "\"";
            
            String tooltipStr = LangUtil.translate("ghost.tooltip.switch_provider", provider);
            ChatComponentText tooltipComponent = new ChatComponentText(tooltipStr);
            tooltipComponent.getChatStyle().setColor(EnumChatFormatting.YELLOW);

            ChatStyle style = new ChatStyle()
                    .setColor(EnumChatFormatting.DARK_GRAY)
                    .setBold(false)
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltipComponent));
            
            button.setChatStyle(style);
            buttons.appendSibling(button);
        }
        return buttons;
    }

    private static String getProviderAbbreviation(String provider) {
        switch (provider.toUpperCase()) {
            case "GOOGLE": return "G";
            case "BING": return "B";
            case "MYMEMORY": return "M";
            case "NIUTRANS": return "N";
            default: return provider.substring(0, 1).toUpperCase();
        }
    }

    public static ChatComponentText formatMessage(String messageKey, Object... args) {
        return formatMessage(EnumChatFormatting.GRAY, messageKey, args);
    }

    public static ChatComponentText formatMessage(EnumChatFormatting contentColor, String messageKey, Object... args) {
        ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
        prefix.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY));
        ChatComponentText content = new ChatComponentText(LangUtil.translate(messageKey, args));
        content.setChatStyle(new ChatStyle().setColor(contentColor));
        prefix.appendSibling(content);
        return prefix;
    }

    public static BlockStateProxy parseBlockState(ICommandSender sender, String input) throws CommandException {
        String blockIdString = input;
        int meta = 0;

        int lastColonIndex = input.lastIndexOf(':');
        if (lastColonIndex > 0) {
            String potentialMeta = input.substring(lastColonIndex + 1);
            try {
                int parsedMeta = Integer.parseInt(potentialMeta);
                meta = parsedMeta;
                blockIdString = input.substring(0, lastColonIndex);
            } catch (NumberFormatException e) {
            }
        }

        Block block;
        try {
            block = CommandBase.getBlockByText(sender, blockIdString);
        } catch (CommandException e) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block_id", blockIdString));
        }

        if (block == null) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block_id", blockIdString));
        }

        try {
            block.getStateFromMeta(meta);
        } catch (IllegalArgumentException e) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_metadata", meta, block.getRegistryName()));
        }

        if (block == Blocks.air) meta = 0;
        
        LogUtil.debug("log.info.command.parse.success", block.getRegistryName(), Block.getIdFromBlock(block), meta);
        return new BlockStateProxy(Block.getIdFromBlock(block), meta);
    }
    
    public static BlockPos parseBlockPosLegacy(ICommandSender sender, String[] args, int index) throws CommandException {
        if (args.length < index + 3) {
            throw new WrongUsageException("ghostblock.commands.usage");
        }
        EntityPlayer player = sender instanceof EntityPlayer ? (EntityPlayer) sender : null;
        double baseX = (player != null) ? player.posX : 0.0D;
        double baseY = (player != null) ? player.posY : 0.0D;
        double baseZ = (player != null) ? player.posZ : 0.0D;
        double x = parseRelativeCoordinateLegacy(sender, args[index], baseX);
        double y = parseRelativeCoordinateLegacy(sender, args[index + 1], baseY);
        double z = parseRelativeCoordinateLegacy(sender, args[index + 2], baseZ);
        return new BlockPos(Math.floor(x), Math.floor(y), Math.floor(z));
    }

    private static double parseRelativeCoordinateLegacy(ICommandSender sender, String input, double base) throws CommandException {
        if (input.startsWith("~")) {
            String offsetStr = input.substring(1);
            if (offsetStr.isEmpty()) {
                return base;
            } else {
                try {
                    return base + Double.parseDouble(offsetStr);
                } catch (NumberFormatException e) {
                    throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_coordinate_format", input));
                }
            }
        } else {
            try {
                return Double.parseDouble(input);
            } catch (NumberFormatException e) {
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_coordinate_format", input));
            }
        }
    }

    public static boolean isNumber(String input) {
        if (input == null || input.isEmpty()) return false;
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static void validateBatchSize(int batchSize) throws CommandException {
        if (batchSize <= 0) {
            throw new CommandException(LangUtil.translate("ghostblock.commands.error.batch_size_too_small"));
        }
    }

    public static String getAutoClearFileName(WorldClient world) {
        return "clear_" + GhostBlockData.getWorldIdentifier(world);
    }
    
    public static String getAutoPlaceSaveFileName(net.minecraft.world.World world) {
        if (world == null) return "autoplace_unknown_world";
        return "autoplace_" + GhostBlockData.getWorldIdentifier(world);
    }
    
    public static List<String> getAvailableFileNames() {
        List<String> files = new ArrayList<>();
        File savesDir = new File(GhostBlockData.SAVES_DIR);
        if (!savesDir.exists() || !savesDir.isDirectory()) {
            return files;
        }
        File[] jsonFiles = savesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (jsonFiles != null) {
            for (File file : jsonFiles) {
                String name = file.getName();
                String baseName = name.substring(0, name.length() - 5);
                if (!baseName.isEmpty() && !baseName.toLowerCase().startsWith("clear_") && !baseName.toLowerCase().startsWith("undo_")) {
                    files.add(baseName);
                }
            }
        }
        Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
        return files;
    }
    
    public static boolean isBlockSectionReady(WorldClient world, BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() >= 256) return false;
        if (!world.isBlockLoaded(pos)) return false; 
        
        Chunk chunk = world.getChunkFromBlockCoords(pos);
        int storageY = pos.getY() >> 4;
        
        return chunk.getBlockStorageArray()[storageY] != null;
    }

    public static void setGhostBlock(WorldClient world, BlockPos pos, BlockStateProxy state) throws CommandException {
        if (world.isRemote) {
            Block block = Block.getBlockById(state.blockId);
            if (block != null) {
                world.setBlockState(pos, block.getStateFromMeta(state.metadata), 3);
                world.checkLightFor(EnumSkyBlock.BLOCK, pos);
                world.checkLightFor(EnumSkyBlock.SKY, pos);
            } else {
                LogUtil.error("log.error.setGhostBlock.invalidId", state.blockId);
                throw new CommandException(LangUtil.translate("ghostblock.commands.error.invalid_block"));
            }
        }
    }
    
    public static List<String> getCoordinateSuggestions(ICommandSender sender, int coordinateIndex, BlockPos targetPos) {
        List<String> suggestions = new ArrayList<>();

        if (sender instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) sender;
            int playerCoord = 0;
            switch (coordinateIndex) {
                case 0: playerCoord = (int) Math.floor(player.posX); break;
                case 1: playerCoord = (int) Math.floor(player.posY); break;
                case 2: playerCoord = (int) Math.floor(player.posZ); break;
            }
            suggestions.add(String.valueOf(playerCoord));
        } else if (targetPos == null) {
            suggestions.add("0");
        }
        
        suggestions.add("~");

        if (targetPos != null) {
            switch (coordinateIndex) {
                case 0: suggestions.add(String.valueOf(targetPos.getX())); break;
                case 1: suggestions.add(String.valueOf(targetPos.getY())); break;
                case 2: suggestions.add(String.valueOf(targetPos.getZ())); break;
            }
        }

        return suggestions.stream().distinct().collect(Collectors.toList());
    }

    public static boolean hasFlag(String[] args, String... flags) {
        for (int i = 0; i < args.length - 1; i++) {
            for (String flag : flags) {
                if (args[i].equalsIgnoreCase(flag)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean containsIgnoreCase(List<String> list, String target) {
        if (target == null) return false;
        return list.stream().anyMatch(s -> target.equalsIgnoreCase(s));
    }
    
    public static String formatIdList(Collection<?> ids) {
        return String.join(", ", ids.stream().map(Object::toString).collect(Collectors.toList()));
    }
    
    public static IChatComponent createProgressMessage(String key, int percent, String progressBar) {
        String rawMessage = LangUtil.translate(key, "{0}", "{1}");
        String[] parts = rawMessage.split("\\{(\\d)\\}", -1);
        IChatComponent message = new ChatComponentText("");
        message.appendSibling(new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default")).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY)));
        if (parts.length > 0) message.appendSibling(new ChatComponentText(parts[0]).setChatStyle(new ChatStyle().setColor(LABEL_COLOR)));
        message.appendSibling(new ChatComponentText(percent + "%").setChatStyle(new ChatStyle().setColor(VALUE_COLOR)));
        if (parts.length > 1) message.appendSibling(new ChatComponentText(parts[1]).setChatStyle(new ChatStyle().setColor(LABEL_COLOR)));
        message.appendSibling(new ChatComponentText(progressBar));
        if (parts.length > 2) message.appendSibling(new ChatComponentText(parts[2]).setChatStyle(new ChatStyle().setColor(LABEL_COLOR)));
        return message;
    }
    
    public static String createProgressBar(float progressPercent, int length) {
        int progress = (int) (progressPercent / 100 * length);
        progress = Math.min(progress, length);
        progress = Math.max(0, progress);
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.GREEN);
        for (int i = 0; i < progress; i++) sb.append("=");
        if (progress < length) {
            sb.append(EnumChatFormatting.GOLD).append(">");
            sb.append(EnumChatFormatting.GRAY);
            for (int i = progress + 1; i < length; i++) sb.append("-");
        }
        return sb.toString();
    }
}