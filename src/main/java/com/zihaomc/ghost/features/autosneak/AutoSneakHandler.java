package com.zihaomc.ghost.features.autosneak;

import com.zihaomc.ghost.config.GhostConfig;
// import com.zihaomc.ghost.utils.LogUtil; // 如果需要调试，取消此行注释
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard; // 导入LWJGL的Keyboard类

public class AutoSneakHandler {

    private boolean modIsControllingSneakKey = false; // Mod当前是否正在通过 setKeyBindState *控制* 蹲下键的状态
    private final Minecraft mc = Minecraft.getMinecraft();

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        World world = mc.theWorld;

        if (player == null || world == null) {
            if (modIsControllingSneakKey) {
                releaseSneakKeyByMod(); // 确保在退出世界或玩家消失时释放
            }
            return;
        }

        if (!GhostConfig.AutoSneak.enableAutoSneakAtEdge) {
            if (modIsControllingSneakKey) {
                releaseSneakKeyByMod(); // 如果功能被禁用，释放由 Mod 触发的蹲下
            }
            return;
        }

        // 玩家是否满足执行自动蹲伏逻辑的前提条件
        boolean canPlayerAutoSneak = player.onGround &&
                                   !player.capabilities.isFlying &&
                                   !mc.gameSettings.keyBindJump.isKeyDown() && // 检查游戏内跳跃键是否激活
                                   !player.isInWater() &&
                                   !player.isInLava();

        // Mod 是否认为玩家应该蹲下
        boolean modWantsPlayerToSneak = false;
        if (canPlayerAutoSneak) {
            modWantsPlayerToSneak = checkIsPlayerAtEdge(player, world);
        }

        // 玩家是否真实地、物理地按下了潜行键
        boolean isPhysicalSneakKeyPressedByPlayer = false;
        int sneakKeyCode = mc.gameSettings.keyBindSneak.getKeyCode();
        if (sneakKeyCode > 0 && sneakKeyCode < Keyboard.KEYBOARD_SIZE) { // 确保keyCode有效且在Keyboard数组范围内
            isPhysicalSneakKeyPressedByPlayer = Keyboard.isKeyDown(sneakKeyCode);
        }


        // --- 核心决策逻辑 ---
        if (modWantsPlayerToSneak) {
            // Mod希望玩家蹲下
            if (!player.isSneaking()) { // 如果玩家当前没有实际蹲下
                if (!isPhysicalSneakKeyPressedByPlayer) { // 并且玩家没有手动按住物理潜行键
                    pressSneakKeyByMod(); // Mod按下蹲下键
                }
                // else: 玩家手动按着物理潜行键，Mod不干预 (即使他还没实际蹲下，比如按键刚按下)
            } else { // 玩家已经在蹲下了
                // 如果玩家在蹲下，但既不是因为手动按住物理键，也不是Mod之前控制的
                // (例如：玩家手动按了键，开始蹲伏，然后松开了物理键，但mod仍然希望他蹲伏)
                // Mod此时应该“接管”控制权，以便之后能正确释放
                if (!isPhysicalSneakKeyPressedByPlayer && !modIsControllingSneakKey) {
                    modIsControllingSneakKey = true;
                }
                // else: 玩家手动按着物理键蹲下，或者Mod之前按了键导致蹲下且仍在控制，则保持现状
            }
        } else {
            // Mod不希望玩家蹲下 (不在边缘或不满足前提条件)
            if (modIsControllingSneakKey) { // 只有当蹲下状态之前是由Mod触发或“接管”时
                if (!isPhysicalSneakKeyPressedByPlayer) { // 并且玩家当前没有手动按住物理潜行键
                    releaseSneakKeyByMod(); // Mod松开蹲下键，玩家将站起
                } else {
                    // 玩家手动按住了物理潜行键。Mod虽然不希望自动蹲下，但由于玩家的手动操作，Mod应放弃控制权。
                    // 蹲下状态会因为玩家的手动操作而继续。
                    modIsControllingSneakKey = false;
                }
            }
            // else: Mod之前没有控制蹲下。如果玩家手动蹲下，他们会继续直到松开。Mod不干预。
        }
    }

    // 使用你提供的 checkIsPlayerAtEdge 方法结构，并稍作调整以提高稳健性
    private boolean checkIsPlayerAtEdge(EntityPlayerSP player, World world) {
        double playerFeetY = player.getEntityBoundingBox().minY;
        float yaw = player.rotationYaw;
        float moveForward = player.moveForward; // 这些值由Minecraft更新，反映玩家的移动组件
        float moveStrafe = player.moveStrafing;
        Vec3 moveDirection;

        double forwardOffset = GhostConfig.AutoSneak.autoSneakForwardOffset;
        double verticalCheckDepth = GhostConfig.AutoSneak.autoSneakVerticalCheckDepth;

        // 判断是基于玩家的移动意图还是朝向
        if (Math.abs(moveForward) > 0.001f || Math.abs(moveStrafe) > 0.001f) {
            // 根据移动的forward和strafe分量，以及玩家的yaw，计算世界坐标系下的移动方向向量
            float strafeComp = moveStrafe, forwardComp = moveForward;
            float sinYaw = MathHelper.sin(yaw * (float)Math.PI / 180.0F);
            float cosYaw = MathHelper.cos(yaw * (float)Math.PI / 180.0F);
            double motionX = (double)(strafeComp * cosYaw - forwardComp * sinYaw);
            double motionZ = (double)(forwardComp * cosYaw + strafeComp * sinYaw);
            
            // 使用平方长度避免开方，判断计算出的移动向量是否足够大
            double lengthSq = motionX * motionX + motionZ * motionZ;
            if (lengthSq >= 1.0E-8D) { // 1E-4 * 1E-4 = 1E-8. 一个较小的阈值
                double length = MathHelper.sqrt_double(lengthSq);
                moveDirection = new Vec3(motionX / length, 0, motionZ / length);
            } else {
                // 移动分量产生的合向量太小，使用玩家的视线方向 (仅考虑水平分量)
                Vec3 lookVec = player.getLookVec();
                moveDirection = new Vec3(lookVec.xCoord, 0, lookVec.zCoord).normalize();
            }
        } else {
            // 玩家没有明显的移动输入 (moveForward 和 moveStrafe 都很小)，使用玩家的视线方向
            Vec3 lookVec = player.getLookVec();
            moveDirection = new Vec3(lookVec.xCoord, 0, lookVec.zCoord).normalize();
        }

        // 检查最终计算出的moveDirection是否有效
        // Vec3.normalize() 对于零向量可能会返回 (NaN, NaN, NaN) 或 (0,0,0)
        // 如果长度非常小，意味着没有明确的水平方向去检测边缘。
        if (moveDirection.lengthVector() < 1.0E-4D) {
            return false; // 无效或不明确的检测方向
        }
        // 如果之前的normalize()可能因为输入是(0,0,0)而没有正确归一化，这里可以再次normalize。
        // 但通常情况下，如果lengthVector() > 0，它已经被归一化了。
        // moveDirection = moveDirection.normalize(); // 通常不需要重复，上面的逻辑已确保向量有效或已归一化

        double checkX = player.posX + moveDirection.xCoord * forwardOffset;
        double checkZ = player.posZ + moveDirection.zCoord * forwardOffset;
        // 从玩家脚底实体边界框的minY再往下一点点开始检测，避免检测到玩家当前站立的方块自身
        BlockPos posToTestBelow = new BlockPos(checkX, playerFeetY - 0.01, checkZ);

        for (int i = 0; i < MathHelper.ceiling_double_int(verticalCheckDepth); i++) {
            BlockPos currentTestPos = posToTestBelow.down(i);
            if (!world.isAirBlock(currentTestPos)) { // 如果不是空气方块
                return false; // 找到了非空气方块，说明下方有支撑，不在边缘
            }
        }
        // 在检测深度内全是空气，说明在边缘
        return true;
    }

    /**
     * 由Mod按下蹲下键。
     */
    private void pressSneakKeyByMod() {
        // 只有当键的逻辑状态当前不是按下时，才通过程序设置它为按下
        // 这样可以避免不必要的 KeyBinding.setKeyBindState 调用，尽管重复调用通常无害
        if (!mc.gameSettings.keyBindSneak.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
        }
        modIsControllingSneakKey = true;
        // LogUtil.debug("log.debug.autosneak.press");
    }

    /**
     * 由Mod释放蹲下键。
     */
    private void releaseSneakKeyByMod() {
        // 只有当键的逻辑状态当前是按下时，才通过程序设置它为释放
        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
        modIsControllingSneakKey = false;
        // LogUtil.debug("log.debug.autosneak.release");
    }
}