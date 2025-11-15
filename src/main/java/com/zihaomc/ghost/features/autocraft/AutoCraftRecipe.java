package com.zihaomc.ghost.features.autocraft;

import net.minecraft.item.Item;

/**
 * 代表一个自动合成配方的定义。
 * 存储了合成一个物品所需的所有信息，如输入名称和数量。
 */
public class AutoCraftRecipe {
    /**
     * 配方的唯一标识符，用于命令中。
     */
    public final String recipeKey;

    /**
     * 输入材料在游戏内显示的无格式名称。
     */
    public final String ingredientDisplayName;

    /**
     * 合成一个成品所需的输入材料数量。
     */
    public final int requiredAmount;

    public AutoCraftRecipe(String recipeKey, String ingredientDisplayName, int requiredAmount) {
        this.recipeKey = recipeKey;
        this.ingredientDisplayName = ingredientDisplayName;
        this.requiredAmount = requiredAmount;
    }
}