package com.zihaomc.ghost.features.gameplay;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil; // <--- 导入
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraft.potion.Potion;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class FastPistonBreakingHandler {

    private static final boolean DEBUG_HANDLER_SPEED = true;
    
    // 我们认定的、正确的活塞硬度
    private static final float CORRECT_PISTON_HARDNESS = 1.5f;
    // 游戏API返回的、不正确的活塞硬度
    private static final float INCORRECT_PISTON_HARDNESS = 0.5f;

    @SubscribeEvent
    public void onPlayerBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!GhostConfig.fastPistonBreaking || event.entityPlayer == null) {
            return;
        }

        Block targetBlock = event.state.getBlock();
        if (targetBlock != Blocks.piston && targetBlock != Blocks.sticky_piston) {
            return;
        }

        ItemStack heldItem = event.entityPlayer.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemPickaxe)) {
            return;
        }
        
        EntityPlayer player = event.entityPlayer;

        if (DEBUG_HANDLER_SPEED) {
            LogUtil.debug("log.debug.fastpiston.recalculating");
            LogUtil.debug("log.debug.fastpiston.originalSpeed", event.newSpeed);
        }

        // 步骤 1: 计算理论上的总挖掘速度 (s*m)
        float s = ((ItemTool) heldItem.getItem()).getToolMaterial().getEfficiencyOnProperMaterial();
        int l = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, heldItem);
        if (l > 0) {
            s += (float) (l * l + 1);
        }
        
        float m = 1.0f;
        if (player.isPotionActive(Potion.digSpeed)) {
            int amplifier = player.getActivePotionEffect(Potion.digSpeed).getAmplifier();
            m *= 1.0F + (amplifier + 1) * 0.2F;
        }
        if (player.isPotionActive(Potion.digSlowdown)) {
            // ... (省略疲劳、水下等计算，因为在常规场景下 m=1 或仅受急迫影响)
        }
        
        float sm = s * m;
        if (DEBUG_HANDLER_SPEED) LogUtil.debug("log.debug.fastpiston.calculatedSM", sm);

        // 步骤 2: 校正速度值以适应游戏错误的硬度
        // 我们需要设置的 newSpeed，应该满足: newSpeed / (错误的硬度) = (我们计算的速度) / (正确的硬度)
        // newSpeed = (我们计算的速度) * (错误的硬度 / 正确的硬度)
        float correctionFactor = INCORRECT_PISTON_HARDNESS / CORRECT_PISTON_HARDNESS; // 0.5 / 1.5 = 1/3
        float correctedSpeed = sm * correctionFactor;
        
        if (DEBUG_HANDLER_SPEED) {
            LogUtil.debug("log.debug.fastpiston.correctionFactor", correctionFactor);
            LogUtil.debug("log.debug.fastpiston.finalSpeed", correctedSpeed);
        }

        // 步骤 3: 直接用我们校正后的速度覆盖事件的速度
        event.newSpeed = correctedSpeed;
    }
}
