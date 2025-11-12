package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 使用数据包瞬间破坏方块的策略。
 * 它会在同一个tick内发送开始和停止挖掘的数据包。
 */
public class PacketInstantMiningStrategy implements IMiningStrategy {

    private final Minecraft mc = Minecraft.getMinecraft();

    @Override
    public void onStartMining(BlockPos target) {
        // 此模式无内部状态需要重置
    }

    @Override
    public void onStopMining() {
        // 此模式下无需特殊清理
    }

    @Override
    public void handleMiningTick(BlockPos target, Vec3 bestPointToLookAt) {
        // 瞬发模式总是强制瞬间旋转
        float[] targetRots = RotationUtil.getRotations(bestPointToLookAt);
        mc.thePlayer.rotationYaw = targetRots[0];
        mc.thePlayer.rotationPitch = targetRots[1];

        // 手动进行射线追踪，不依赖 mc.objectMouseOver
        MovingObjectPosition mop = mc.thePlayer.rayTrace(GhostConfig.AutoMine.maxReachDistance, 1.0F);

        // 如果射线成功命中目标方块
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mop.getBlockPos().equals(target)) {
            EnumFacing facing = mop.sideHit;
            // 连续发送开始、挥手、停止数据包
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.START_DESTROY_BLOCK, target, facing));
            mc.thePlayer.swingItem();
            mc.getNetHandler().addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, target, facing));
            // 触发反作弊回弹检测
            AutoMineHandler.startValidation(target);
        }
        // 如果没对准，就在下一tick再次尝试
    }

    @Override
    public String getModeName() {
        return "PACKET_INSTANT";
    }
}