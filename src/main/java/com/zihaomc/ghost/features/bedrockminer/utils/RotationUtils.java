package com.zihaomc.ghost.features.bedrockminer.utils;

import com.zihaomc.ghost.features.bedrockminer.data.Rotation;
import com.zihaomc.ghost.mixins.accessors.EntityPlayerSPAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

public class RotationUtils {
    // 移除了所有 final2constant 和 MethodHandle 的内容，改为普通 Java 字段
    private final Deque<Rotation> rotations = new ArrayDeque<>();
    private boolean keepRotationToNextTick = false;

    public RotationUtils() {
        this.rotations.add(Rotation.None.INSTANCE);
    }

    public Rotation getRotation() {
        return rotations.getFirst();
    }

    private void setRotation(Rotation rotation) {
        rotations.removeFirst();
        rotations.addFirst(rotation);
    }
    
    public void markKeepRotation() {
        this.keepRotationToNextTick = true;
    }

    public void resetRotationIfNoKeepRotation() {
        if (keepRotationToNextTick) {
            this.keepRotationToNextTick = false;
        } else {
            setRotation(Rotation.None.INSTANCE);
        }
    }

    // 这是一个关键的翻译：updateLocation 在 1.8.9 中没有直接等价物，
    // 但我们可以通过调用一个已有的、能触发数据包发送的 public 方法来实现。
    // sendHorseJump 会间接触发 onUpdateWalkingPlayer，从而发送我们的伪造视角。
    public void updateLocation(EntityPlayerSP player) {
        // 我们使用 Accessor 来调用这个方法
        ((EntityPlayerSPAccessor) player).invokeSendHorseJump();
    }

    // 这里我们将 ClientPlayerEntity 替换为 EntityPlayerSP
    public <T> T useServerSideRotationDuring(EntityPlayerSP player, Supplier<T> supplier) {
        EntityPlayerSPAccessor playerAccessor = (EntityPlayerSPAccessor) player;
        float originYaw = player.rotationYaw;
        float originPitch = player.rotationPitch;

        // 使用 Accessor 获取上一次报告给服务器的视角
        player.rotationYaw = playerAccessor.getLastReportedYaw();
        player.rotationPitch = playerAccessor.getLastReportedPitch();
        
        T result = supplier.get();
        
        // 恢复玩家的真实视角
        player.rotationYaw = originYaw;
        player.rotationPitch = originPitch;
        
        return result;
    }

    public void forceClearRotations() {
        this.rotations.clear();
        this.rotations.add(Rotation.None.INSTANCE);
    }
}