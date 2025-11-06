package com.zihaomc.ghost.utils;

import com.zihaomc.ghost.config.GhostConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 封装所有与玩家视角旋转和可见性检查相关的数学计算。
 */
public class RotationUtil {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float ROTATION_TOLERANCE = 0.5f;

    /**
     * 计算玩家眼睛到指定空间点所需的 Yaw 和 Pitch。
     * @param target 目标三维向量
     * @return float 数组, [0] = yaw, [1] = pitch
     */
    public static float[] getRotations(Vec3 target) {
        Vec3 playerEyePos = mc.thePlayer.getPositionEyes(1.0F);

        double deltaX = target.xCoord - playerEyePos.xCoord;
        double deltaY = target.yCoord - playerEyePos.yCoord;
        double deltaZ = target.zCoord - playerEyePos.zCoord;

        double distance = MathHelper.sqrt_double(deltaX * deltaX + deltaZ * deltaZ);

        float yaw = (float) (MathHelper.atan2(deltaZ, deltaX) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) (-(MathHelper.atan2(deltaY, distance) * 180.0D / Math.PI));

        return new float[]{
                MathHelper.wrapAngleTo180_float(yaw),
                MathHelper.wrapAngleTo180_float(pitch)
        };
    }

    /**
     * 计算朝向目标视角平滑移动一步后的新视角。
     * @param speed 基础旋转速度 (度/tick)
     * @return float 数组, [0] = newYaw, [1] = newPitch
     */
    public static float[] getSmoothRotations(float currentYaw, float currentPitch, float targetYaw, float targetPitch, float speed) {
        float finalSpeed = speed;

        // 如果开启了随机速度，则计算一个波动的速度
        if (GhostConfig.AutoMine.enableRandomRotationSpeed) {
            double variability = GhostConfig.AutoMine.rotationSpeedVariability;
            // 计算一个在 [-variability, +variability] 范围内的随机偏移量
            double randomOffset = (Math.random() - 0.5) * 2.0 * variability;
            finalSpeed += randomOffset;
            // 确保速度不会低于一个最小值，防止旋转过慢或停止
            finalSpeed = Math.max(1.0f, finalSpeed);
        }

        float yawDifference = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDifference = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);

        float yawStep = MathHelper.clamp_float(yawDifference, -finalSpeed, finalSpeed);
        float pitchStep = MathHelper.clamp_float(pitchDifference, -finalSpeed, finalSpeed);

        float newYaw = currentYaw + yawStep;
        float newPitch = currentPitch + pitchStep;

        newPitch = MathHelper.clamp_float(newPitch, -90.0F, 90.0F);

        return new float[]{newYaw, newPitch};
    }

    /**
     * 检查当前视角是否已足够接近目标视角。
     */
    public static boolean isRotationComplete(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(currentYaw - targetYaw));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(currentPitch - targetPitch));
        return yawDiff < ROTATION_TOLERANCE && pitchDiff < ROTATION_TOLERANCE;
    }
    
    /**
     * 智能寻找方块上的最佳可视瞄准点。
     * 优先检查中心点，如果中心点不可见，则检查所有面的中心点，并返回与当前准星夹角最小的那个。
     *
     * @param pos 要检查的方块位置
     * @return 如果找到可见点，返回该点的 Vec3 坐标；否则返回 null。
     */
    public static Vec3 getClosestVisiblePoint(BlockPos pos) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        
        // 1. 优先检查方块的精确中心点
        Vec3 centerPoint = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        MovingObjectPosition centerRayTrace = mc.theWorld.rayTraceBlocks(eyes, centerPoint, false, true, false);
        if (centerRayTrace == null || centerRayTrace.typeOfHit == MovingObjectPosition.MovingObjectType.MISS || centerRayTrace.getBlockPos().equals(pos)) {
            return centerPoint; // 中心点可见，直接返回
        }

        // 2. 如果中心点被遮挡，则遍历所有面寻找替代点
        Vec3 bestPoint = null;
        double minAngleDiff = Double.MAX_VALUE;

        for (EnumFacing facing : EnumFacing.values()) {
            Vec3 pointOnFace = new Vec3(
                pos.getX() + 0.5 + facing.getFrontOffsetX() * 0.5,
                pos.getY() + 0.5 + facing.getFrontOffsetY() * 0.5,
                pos.getZ() + 0.5 + facing.getFrontOffsetZ() * 0.5
            );

            MovingObjectPosition faceRayTrace = mc.theWorld.rayTraceBlocks(eyes, pointOnFace, false, true, false);
            if (faceRayTrace == null || faceRayTrace.typeOfHit == MovingObjectPosition.MovingObjectType.MISS || faceRayTrace.getBlockPos().equals(pos)) {
                float[] rotations = getRotations(pointOnFace);
                float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - rotations[0]));
                float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationPitch - rotations[1]));
                double angleDiff = yawDiff * yawDiff + pitchDiff * pitchDiff; // 使用平方和来比较，避免开方

                if (angleDiff < minAngleDiff) {
                    minAngleDiff = angleDiff;
                    bestPoint = pointOnFace;
                }
            }
        }
        
        return bestPoint;
    }
}