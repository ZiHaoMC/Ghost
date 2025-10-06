package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
import com.zihaomc.ghost.LangUtil;
import net.minecraft.block.BlockSign;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.stream.Collectors;

public class SignTranslationHandler {

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || !event.world.isRemote || (!GhostConfig.enableSignTranslation && !GhostConfig.enableAutomaticTranslation)) {
            return;
        }

        if (event.world.getBlockState(event.pos).getBlock() instanceof BlockSign) {
            
            TileEntity tileEntity = event.world.getTileEntity(event.pos);
            if (tileEntity instanceof TileEntitySign) {
                TileEntitySign sign = (TileEntitySign) tileEntity;

                String combinedText = Arrays.stream(sign.signText)
                        .map(IChatComponent::getUnformattedText)
                        .collect(Collectors.joining(" "))
                        .trim();

                if (!combinedText.isEmpty()) {
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + LangUtil.translate("ghost.sign.translating")));

                    new Thread(() -> {
                        final String result = NiuTransUtil.translate(combinedText);
                        Minecraft.getMinecraft().addScheduledTask(() -> {
                            ChatComponentText resultMessage = new ChatComponentText("");
                            
                            if (result.startsWith(NiuTransUtil.ERROR_PREFIX)) {
                                // 错误消息
                                String errorContent = result.substring(NiuTransUtil.ERROR_PREFIX.length());
                                ChatComponentText errorPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
                                errorPrefix.getChatStyle().setColor(EnumChatFormatting.RED);
                                ChatComponentText errorText = new ChatComponentText(errorContent);
                                errorText.getChatStyle().setColor(EnumChatFormatting.RED);
                                resultMessage.appendSibling(errorPrefix).appendSibling(errorText);
                            } else {
                                // 成功消息
                                ChatComponentText prefix = new ChatComponentText(LangUtil.translate("ghost.sign.prefix.translation") + " ");
                                prefix.getChatStyle().setColor(EnumChatFormatting.AQUA);
                                ChatComponentText content = new ChatComponentText(result);
                                resultMessage.appendSibling(prefix).appendSibling(content);
                            }
                            
                            Minecraft.getMinecraft().thePlayer.addChatMessage(resultMessage);
                        });
                    }).start();

                    event.setCanceled(true);
                }
            }
        }
    }
}