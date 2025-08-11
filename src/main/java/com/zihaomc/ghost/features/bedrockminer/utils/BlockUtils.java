package com.zihaomc.ghost.features.bedrockminer.utils;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public final class BlockUtils {
    private BlockUtils() {}

    public static boolean isReplaceable(IBlockState blockState, World world, BlockPos pos) {
        if (blockState == null) return true;
        // 在 1.8.9, isReplaceable 是 material 的一个属性，或者直接判断是否是空气
        return blockState.getBlock().getMaterial().isReplaceable() || world.isAirBlock(pos);
    }
    
    public static float getHardness(IBlockState blockState, World world, BlockPos pos) {
        return blockState.getBlock().getBlockHardness(world, pos);
    }
    
    private static Vec3 getEyePos(EntityPlayerSP player) {
        // 1.8.9: 需要手动构造 Vec3
        return new Vec3(player.posX, player.posY + (double)player.getEyeHeight(), player.posZ);
    }

    // 1.8.9 的距离计算逻辑 (Old)
    public static boolean playerCanTouchBlock(EntityPlayerSP player, BlockPos blockPos, boolean isForBreaking) {
        // PlayerControllerMP.getBlockReachDistance() 会返回创造或生存的正确距离
        double reach = Minecraft.getMinecraft().playerController.getBlockReachDistance();

        // 1.8.9 使用的是基于玩家位置的简单距离检查
        double distanceSq = player.getDistanceSq(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
        
        // 我们给一个小的容错范围
        return distanceSq <= (reach + 1.0) * (reach + 1.0);
    }
}