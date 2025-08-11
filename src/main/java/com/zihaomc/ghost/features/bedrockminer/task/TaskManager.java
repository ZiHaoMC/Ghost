package com.zihaomc.ghost.features.bedrockminer.task;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.bedrockminer.enums.TaskState;
import com.zihaomc.ghost.features.bedrockminer.utils.BlinkUtils;
import com.zihaomc.ghost.features.bedrockminer.utils.MessageUtils;
import com.zihaomc.ghost.features.bedrockminer.utils.RotationUtils;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.BlockPos;
import java.lang.ref.WeakReference;
import java.util.*;

public class TaskManager {
    private boolean enabled = false;
    private WeakReference<WorldClient> prevWorldRef;
    private final Set<BlockPos> posSet = new HashSet<>();
    private final LinkedList<Task> taskQueue = new LinkedList<>();
    private final RotationUtils rotationUtils = new RotationUtils();
    
    // 我们需要一个单例或者从主 Handler 传递实例
    private static final TaskManager INSTANCE = new TaskManager();
    public static TaskManager getInstance() { return INSTANCE; }


    public void tick() {
        rotationUtils.resetRotationIfNoKeepRotation();
        if (!isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        
        WorldClient world = mc.theWorld;
        if (world == null) {
            if (prevWorldRef != null) {
                prevWorldRef.clear();
                prevWorldRef = null;
            }
            onDisable();
            return;
        } else {
            if (prevWorldRef == null || prevWorldRef.get() != world) {
                this.prevWorldRef = new WeakReference<>(world);
                onDisable();
                return;
            }
        }
        
        if (taskQueue.isEmpty()) return;

        NetworkManager connection = player.sendQueue.getNetworkManager();
        final boolean startedBlinking = connection != null && GhostConfig.isBlinkDuringTasksTick() && !taskQueue.isEmpty() && BlinkUtils.tryStartBlinking(connection);

        try {
            final Iterator<Task> taskIterator = taskQueue.iterator();
            while (taskIterator.hasNext()) {
                final Task task = taskIterator.next();
                final boolean ignoreOtherTasks = task.tick(this);
                if (task.state == TaskState.Finished) {
                    taskIterator.remove();
                    posSet.remove(task.targetPos);
                }
                if (ignoreOtherTasks) break;
            }
        } finally {
            if (startedBlinking) {
                BlinkUtils.tryStopBlinking(connection);
            }
        }
    }

    public boolean handleAttackBlock(BlockPos blockPos) {
        if (!isEnabled()) return false;
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (!GhostConfig.getBlockWhitelist().contains(world.getBlockState(blockPos).getBlock())) return false;
        if (posSet.contains(blockPos)) return false;
        final Task task = new Task(blockPos);
        posSet.add(blockPos);
        taskQueue.add(task);
        return true;
    }

    public boolean handleUseOnBlock(BlockPos targetBlock) {
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null || !GhostConfig.getBlockWhitelist().contains(world.getBlockState(targetBlock).getBlock())) return false;
        toggle();
        return true;
    }

    public void toggle() {
        if (isEnabled()) {
            onDisable();
            MessageUtils.printMessage("§7Block Miner 关闭成功。");
        } else {
            onEnable();
            MessageUtils.printMessage("§2Block Miner 启动成功！");
            if (!Minecraft.getMinecraft().isSingleplayer())
                MessageUtils.printMessage("§7[警告] 在服务器使用可能被视为作弊。");
        }
    }

    private void onEnable() { this.enabled = true; }
    private void onDisable() {
        this.enabled = false;
        posSet.clear();
        taskQueue.clear();
        rotationUtils.forceClearRotations();
    }
    
    public RotationUtils getRotationUtils() { return rotationUtils; }
    public boolean isEnabled() { return this.enabled; }
}