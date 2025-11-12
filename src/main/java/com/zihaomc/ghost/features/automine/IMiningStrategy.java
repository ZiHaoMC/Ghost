package com.zihaomc.ghost.features.automine;

import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

/**
 * 自动挖掘策略的接口。
 * 定义了不同挖掘模式（如模拟、数据包）需要实现的通用方法。
 */
public interface IMiningStrategy {

    /**
     * 当开始挖掘一个新的目标方块时调用。
     * 用于重置策略内部的状态。
     * @param target 新的目标方块坐标。
     */
    void onStartMining(BlockPos target);

    /**
     * 当自动挖掘功能完全停止时调用。
     * 用于执行清理工作，例如释放按键。
     */
    void onStopMining();

    /**
     * 在每个 "MINING" 状态的游戏刻（tick）中调用。
     * 这是执行核心挖掘逻辑的地方。
     * @param target 当前的目标方块坐标。
     * @param bestPointToLookAt 建议的瞄准点。
     */
    void handleMiningTick(BlockPos target, Vec3 bestPointToLookAt);

    /**
     * 获取此策略的模式名称。
     * @return 模式的字符串表示形式。
     */
    String getModeName();
}