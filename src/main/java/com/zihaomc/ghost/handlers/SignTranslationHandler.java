package com.zihaomc.ghost.handlers;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.NiuTransUtil;
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
        // 1. 检查基本条件
        // - 必须是右键点击方块的动作
        // - 必须在客户端执行 (world.isRemote)
        // - 翻译功能必须在配置中启用
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || !event.world.isRemote || !GhostConfig.enableChatTranslationButton) {
            return;
        }

        // 2. 检查点击的是否是告示牌
        if (event.world.getBlockState(event.pos).getBlock() instanceof BlockSign) {
            
            // 3. 获取告示牌的TileEntity，这里包含了文本数据
            TileEntity tileEntity = event.world.getTileEntity(event.pos);
            if (tileEntity instanceof TileEntitySign) {
                TileEntitySign sign = (TileEntitySign) tileEntity;

                // 4. 提取并合并告示牌上的文本
                String combinedText = Arrays.stream(sign.signText)
                        .map(IChatComponent::getUnformattedText)
                        .collect(Collectors.joining(" ")) // 用空格连接各行
                        .trim();

                // 5. 如果有文本，则执行翻译
                if (!combinedText.isEmpty()) {
                    // 提示用户正在翻译
                    Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "正在翻译告示牌内容..."));

                    // 在新线程中执行网络请求，防止游戏卡顿
                    new Thread(() -> {
                        final String result = NiuTransUtil.translate(combinedText);
                        // 将结果发送回主线程进行处理，以安全地与Minecraft交互
                        Minecraft.getMinecraft().addScheduledTask(() -> {
                            ChatComponentText resultMessage = new ChatComponentText("§b[告示牌翻译]§r " + result);
                            Minecraft.getMinecraft().thePlayer.addChatMessage(resultMessage);
                        });
                    }).start();

                    // 6. **非常重要**: 取消这个右键事件。
                    // 这样可以防止玩家在右键告示牌时意外地放置手上的方块。
                    event.setCanceled(true);
                }
            }
        }
    }
}