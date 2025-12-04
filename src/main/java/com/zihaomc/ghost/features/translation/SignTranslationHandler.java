package com.zihaomc.ghost.features.translation;

import com.zihaomc.ghost.config.GhostConfig;
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
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || !event.world.isRemote || (!GhostConfig.Translation.enableSignTranslation && !GhostConfig.Translation.enableAutomaticTranslation)) {
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
                        final String result = TranslationUtil.translate(combinedText);
                        Minecraft.getMinecraft().addScheduledTask(() -> {
                            // 安全检查：确保玩家仍在游戏中，以防止在切换世界时崩溃
                            if (Minecraft.getMinecraft().thePlayer == null) {
                                return;
                            }
                        
                            ChatComponentText resultMessage = new ChatComponentText("");
                            
                            if (result.startsWith(TranslationUtil.ERROR_PREFIX)) {
                                // 错误消息
                                String errorContent = result.substring(TranslationUtil.ERROR_PREFIX.length());
                                ChatComponentText errorPrefix = new ChatComponentText(LangUtil.translate("ghost.generic.prefix.default"));
                                
                                // --- 统一颜色为深灰色 ---
                                errorPrefix.getChatStyle().setColor(EnumChatFormatting.DARK_GRAY);
                                
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