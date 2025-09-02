package com.zihaomc.ghost.mixins;

// import com.zihaomc.ghost.features.bedrockminer.task.TaskManager;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerControllerMP.class)
public class MixinPlayerControllerMP {
    
    /**
     * **核心修正**
     * 目标方法: onPlayerDamageBlock(BlockPos posBlock, EnumFacing directionFacing)
     * 1.8.9 SRG 名称: func_180512_c
     * 这个方法的签名非常干净，就是 (BlockPos, EnumFacing)，非常适合我们。
     * 当玩家第一次左键点击一个方块时，这个方法会被调用。
     */
    @Inject(method = "func_180512_c", at = @At("HEAD"), cancellable = true)
    private void onPlayerDamageBlock(BlockPos posBlock, EnumFacing directionFacing, CallbackInfoReturnable<Boolean> cir) {
    // 暂时禁用此功能
    /*
         System.out.println("====== MIXIN: onClickBlock successful!");
        TaskManager taskManager = TaskManager.getInstance();
        if (taskManager.handleAttackBlock(posBlock)) {
            // 如果我们的代码处理了这个点击，就取消原始方法，并返回 true
            cir.setReturnValue(true);
        }
        */
    }
}