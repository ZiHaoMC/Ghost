// src/main/java/com/zihaomc/ghost/features/bedrockminer/utils/InventoryUtils.java
package com.zihaomc.ghost.features.bedrockminer.utils;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class InventoryUtils {

    private InventoryUtils() {}

    // --- 物品查找方法 (已补全) ---

    public static int findFirstItemInHotbar(InventoryPlayer inventory, Item item) {
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); ++i) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    public static int findFirstItemInHotbar(InventoryPlayer inventory, Predicate<ItemStack> predicate) {
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); ++i) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && predicate.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    public static int findBestItemInHotbar(InventoryPlayer inventory, Predicate<ItemStack> predicate, Comparator<ItemStack> comparator) {
        int bestIndex = -1;
        ItemStack bestStack = null;

        for (int i = 0; i < InventoryPlayer.getHotbarSize(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && predicate.test(stack)) {
                if (bestStack == null || comparator.compare(stack, bestStack) > 0) {
                    bestStack = stack;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }

    // --- 物品交换和使用方法 (已补全, BlockMiner 的核心操作) ---

    /**
     * 在将指定槽位的物品临时换到副手（在1.8.9是主手）期间执行一个操作。
     * 1.8.9 没有副手，所以我们将其实现为与快捷栏的另一个空格子交换。
     * 为了简化，我们先实现一个更直接的：切换当前选中物品。
     */
    public static <T> T useSlotDuring(int hotbarSlot, Supplier<T> supplier) {
        InventoryPlayer inventory = Minecraft.getMinecraft().thePlayer.inventory;
        int originalSlot = inventory.currentItem; // 记录原始槽位
        inventory.currentItem = hotbarSlot; // 切换到目标槽位

        T result = supplier.get(); // 执行操作

        inventory.currentItem = originalSlot; // 切换回原始槽位
        return result;
    }

    /**
     * 1.8.9 中模拟与副手交换的操作。
     * 找到一个空格子，将目标物品换过去，执行操作，再换回来。
     * 这个操作比较复杂，且 BlockMiner 的逻辑在 1.8.9 中可以简化。
     * BlockMiner 中 moveToOffHandDuring 的目的是为了在不改变主手工具的情况下使用物品。
     * 在 1.8.9 中，我们可以通过 PlayerControllerMP.sendUseItem 来实现类似效果。
     * 为了忠实移植，我们先保留一个简化版 useSlotDuring。
     * 另一个核心是 `useEmptyMainHandIfSneakingDuring`，这个更有用。
     */
     public static <T> T useEmptyMainHandIfSneakingDuring(Supplier<T> supplier) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        InventoryPlayer inventory = player.inventory;

        if (player.isSneaking() && inventory.getCurrentItem() != null) {
            // 查找一个空的快捷栏槽位
            int emptySlot = -1;
            for (int i = 0; i < InventoryPlayer.getHotbarSize(); i++) {
                if (inventory.getStackInSlot(i) == null) {
                    emptySlot = i;
                    break;
                }
            }
            if (emptySlot == -1) {
                // 没有空格子，操作无法进行
                return null; 
            }

            int originalSlot = inventory.currentItem;
            PlayerControllerMP playerController = Minecraft.getMinecraft().playerController;

            // 交换到空格子
            // 1.8.9 没有直接的交换快捷键，但可以通过 windowClick 实现
            // 快捷栏槽位在容器中的 index 是 36-44
            playerController.windowClick(player.inventoryContainer.windowId, originalSlot + 36, emptySlot, 2, player);

            T result = supplier.get(); // 执行操作

            // 换回来
            playerController.windowClick(player.inventoryContainer.windowId, originalSlot + 36, emptySlot, 2, player);

            return result;
        } else {
            // 如果不潜行或主手本来就是空的，直接执行
            return supplier.get();
        }
    }


    // --- 挖掘速度计算 (已补全和修正) ---

    public static float getBlockBreakingDelta(EntityPlayerSP player, IBlockState blockState, ItemStack itemStack) {
        float hardness = blockState.getBlock().getBlockHardness(player.worldObj, BlockPos.ORIGIN); // 1.8.9: 获取硬度的方式
        if (hardness < 0.0F) {
            return 0.0F;
        }
        
        // 1.8.9: canHarvestBlock 现在是一个非静态方法
        if (itemStack != null && itemStack.canHarvestBlock(blockState.getBlock())) {
            return getToolDigSpeed(itemStack, blockState, player) / hardness / 30.0F;
        } else {
            // 1.8.9: getStrVsBlock(block) 用于获取基础挖掘速度
            return player.inventory.getStrVsBlock(blockState.getBlock()) / hardness / 100.0F;
        }
    }

    private static float getToolDigSpeed(ItemStack stack, IBlockState state, EntityPlayerSP player) {
        float digSpeed = stack.getStrVsBlock(state.getBlock());

        if (digSpeed > 1.0F) {
            int efficiencyLevel = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, stack);
            if (efficiencyLevel > 0 && stack.isItemEnchanted()) {
                digSpeed += (float)(efficiencyLevel * efficiencyLevel + 1);
            }
        }

        if (player.isPotionActive(Potion.digSpeed)) {
            digSpeed *= 1.0F + (float)(player.getActivePotionEffect(Potion.digSpeed).getAmplifier() + 1) * 0.2F;
        }

        if (player.isPotionActive(Potion.digSlowdown)) {
            float fatigueMultiplier = 1.0F;
            switch(player.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) {
                case 0:
                    fatigueMultiplier = 0.3F;
                    break;
                case 1:
                    fatigueMultiplier = 0.09F;
                    break;
                case 2:
                    fatigueMultiplier = 0.0027F;
                    break;
                default:
                    fatigueMultiplier = 8.1E-4F;
            }
            digSpeed *= fatigueMultiplier;
        }

        // 1.8.9: getAquaAffinityModifier 返回的是 boolean
        if (player.isInWater() && !EnchantmentHelper.getAquaAffinityModifier(player)) {
            digSpeed /= 5.0F;
        }

        if (!player.onGround) {
            digSpeed /= 5.0F;
        }

        return (digSpeed < 0 ? 0 : digSpeed);
    }

    public static boolean isPickaxe(ItemStack stack) {
        // 1.8.9: 直接判断类型
        return stack != null && stack.getItem() instanceof ItemPickaxe;
    }
}