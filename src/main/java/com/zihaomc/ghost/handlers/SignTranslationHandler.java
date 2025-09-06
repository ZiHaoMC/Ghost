package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
import com.zihaomc.ghost.LangUtil;
import net.minecraft.block.BlockSign;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 处理告示牌翻译功能。
 * 通过监听玩家右键点击事件来实现，因为1.8.9无法重新打开告示牌GUI。
 */
public class SignTranslationHandler {

    /**
     * 当玩家与世界交互时（例如左/右键点击）触发此事件。
     */
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || !event.world.isRemote || !GhostConfig.enableSignTranslation) {
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
                            ChatComponentText resultMessage = new ChatComponentText(LangUtil.translate("ghost.sign.result", result));
                            Minecraft.getMinecraft().thePlayer.addChatMessage(resultMessage);
                        });
                    }).start();

                    event.setCanceled(true);
                }
            }
        }
    }
}