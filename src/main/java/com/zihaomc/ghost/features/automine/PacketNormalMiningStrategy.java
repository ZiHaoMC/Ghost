package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 使用数据包进行常规挖掘的策略。
 * 它会模拟挖掘进度，并在进度达到100%时发送停止挖掘的数据包。
 */
public class PacketNormalMiningStrategy implements IMiningStrategy {

    private final Minecraft mc = Minecraft.getMinecraft();
    private float breakProgress = 0.0f;
    private BlockPos lastPacketTarget = null;

    @Override
    public void onStartMining(BlockPos target) {
        this.breakProgress = 0.0f;
        // lastPacketTarget 会在首次发包时设置
    }

    @Override
    public void onStopMining() {
        // 此模式下无需特殊清理
    }

    @Override
    public void handleMiningTick(BlockPos target, Vec3 bestPointToLookAt) {
        // --- 旋转逻辑 ---
        float[] targetRots = RotationUtil.getRotations(bestPointToLookAt);
        if (GhostConfig.AutoMine.instantRotation) {
            mc.thePlayer.rotationYaw = targetRots[0];
            mc.thePlayer.rotationPitch = targetRots[1];
        } else {
            float[] smoothRots = RotationUtil.getSmoothRotations(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, targetRots[0], targetRots[1], (float) GhostConfig.AutoMine.rotationSpeed);
            mc.thePlayer.rotationYaw = smoothRots[0];
            mc.thePlayer.rotationPitch = smoothRots[1];
        }

        // --- 挖掘逻辑 ---
        MovingObjectPosition mouseOver = mc.objectMouseOver;
        if (mouseOver == null || mouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || !mouseOver.getBlockPos().equals(target)) {
            // 如果没对准，就等待下一Tick
            return;
        }

        EnumFacing facing = mouseOver.sideHit;
        IBlockState targetBlockState = mc.theWorld.getBlockState(target);

        // 如果是新的目标，发送开始挖掘的数据包
        if (!target.equals(lastPacketTarget)) {
            breakProgress = 0.0f;
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.START_DESTROY_BLOCK, target, facing));
            lastPacketTarget = target;
        }

        // 持续挥手并累加挖掘进度
        mc.thePlayer.swingItem();
        float hardness = targetBlockState.getBlock().getPlayerRelativeBlockHardness(mc.thePlayer, mc.theWorld, target);
        breakProgress += hardness;

        // 挖掘完成
        if (breakProgress >= 1.0f) {
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, target, facing));
            // 触发反作弊回弹检测
            AutoMineHandler.startValidation(target);
        }
    }

    @Override
    public String getModeName() {
        return "PACKET_NORMAL";
    }
}