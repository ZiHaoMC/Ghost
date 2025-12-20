
/*
 * This module is a derivative work of Baritone (https://github.com/cabaletta/baritone).
 * This module is licensed under the GNU LGPL v3.0.
 */

package com.zihaomc.ghost.features.automine;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 模拟玩家按键进行挖掘的策略。
 * 这是最安全、最像人类玩家行为的模式。
 */
public class SimulateMiningStrategy implements IMiningStrategy {

    private final Minecraft mc = Minecraft.getMinecraft();

    @Override
    public void onStartMining(BlockPos target) {
        // 在切换目标时，确保攻击键是松开的
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }
    }

    @Override
    public void onStopMining() {
        // 停止时，确保攻击键是松开的
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }
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
        boolean isCrosshairOnTarget = mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mouseOver.getBlockPos().equals(target);

        if (isCrosshairOnTarget) {
            // 如果准星对准了目标，就按下攻击键
            if (!mc.gameSettings.keyBindAttack.isKeyDown()) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), true);
            }
        } else {
            // 否则松开
            if (mc.gameSettings.keyBindAttack.isKeyDown()) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            }
        }
    }

    @Override
    public String getModeName() {
        return "SIMULATE";
    }
}