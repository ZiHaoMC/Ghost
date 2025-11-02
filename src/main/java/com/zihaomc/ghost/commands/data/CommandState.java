package com.zihaomc.ghost.commands.data;

import com.zihaomc.ghost.commands.tasks.ClearTask;
import com.zihaomc.ghost.commands.tasks.FillTask;
import com.zihaomc.ghost.commands.tasks.LoadTask;
import com.zihaomc.ghost.data.GhostBlockData;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集中存储 GhostBlockCommand 的所有状态，
 * 以便在不同的处理器和事件监听器之间共享。
 */
public class CommandState {

    // --- 任务管理 ---
    public static final AtomicInteger taskIdCounter = new AtomicInteger(0);
    public static final Map<Integer, TaskSnapshot> pausedTasks = new HashMap<>();
    public static final List<FillTask> activeFillTasks = Collections.synchronizedList(new ArrayList<>());
    public static final List<LoadTask> activeLoadTasks = Collections.synchronizedList(new ArrayList<>());
    public static final List<ClearTask> activeClearTasks = Collections.synchronizedList(new ArrayList<>());

    // --- 撤销/重做 ---
    // 从 Deque 改为 List 以支持按索引撤销
    public static final List<UndoRecord> undoHistory = new ArrayList<>();

    // --- 清除确认 ---
    public static final Map<String, ClearConfirmation> pendingConfirmations = new HashMap<>();
    public static final int CONFIRMATION_TIMEOUT = 30 * 1000; // 30秒

    // --- 内部数据结构 ---

    /**
     * 代表一次可撤销的操作记录。
     * (重构以支持更详细的描述)
     */
    public static class UndoRecord {
        public enum OperationType {
            SET,
            FILL,
            LOAD,
            CLEAR_BLOCK,
            CLEAR_FILE
        }

        public final String undoFileName;
        public final Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups;
        public final OperationType operationType;
        public final Integer relatedTaskId;
        public final String commandString;
        
        // 用于生成详细描述的附加信息
        public final String details;

        public UndoRecord(String undoFileName, Map<String, List<GhostBlockData.GhostBlockEntry>> fileBackups, OperationType type, Integer relatedTaskId, String commandString, String details) {
            this.undoFileName = undoFileName;
            this.fileBackups = fileBackups != null ? new HashMap<>(fileBackups) : new HashMap<>();
            this.operationType = type;
            this.relatedTaskId = relatedTaskId;
            this.commandString = commandString != null ? commandString : "";
            this.details = details != null ? details : "";
        }
    }

    /**
     * 代表一个待确认的清除文件操作。
     */
    public static class ClearConfirmation {
        public final long timestamp;
        public final List<File> targetFiles;

        public ClearConfirmation(List<File> targetFiles, long timestamp) {
            this.timestamp = timestamp;
            this.targetFiles = Collections.unmodifiableList(new ArrayList<>(targetFiles));
        }
    }

    /**
     * 方块状态的轻量级代理。
     */
    public static class BlockStateProxy {
        public final int blockId;
        public final int metadata;

        public BlockStateProxy(int blockId, int metadata) {
            this.blockId = blockId;
            this.metadata = metadata;
        }
    }

    /**
     * 用于保存已暂停任务的状态快照。
     */
    public static class TaskSnapshot {
        public final String type;
        public final List<BlockPos> remainingBlocks;
        public final List<GhostBlockData.GhostBlockEntry> remainingEntries;
        public final int batchSize;
        public final int total;
        public final BlockStateProxy state;
        public final boolean saveToFile;
        public final String saveFileName;
        public final ICommandSender sender;
        public final int taskId;
        public final List<GhostBlockData.GhostBlockEntry> entriesToSaveForUserFile;

        public TaskSnapshot(FillTask task) {
            this.type = "fill";
            this.remainingBlocks = new ArrayList<>(task.getRemainingBlocks());
            this.batchSize = task.getBatchSize();
            this.total = task.getTotalBlocks();
            this.state = task.getState();
            this.saveToFile = task.isSaveToFile();
            this.saveFileName = task.getSaveFileName();
            this.sender = task.getSender();
            this.taskId = task.getTaskId();
            this.remainingEntries = null;
            this.entriesToSaveForUserFile = task.getEntriesToSaveForUserFile() != null ? new ArrayList<>(task.getEntriesToSaveForUserFile()) : new ArrayList<>();
        }

        public TaskSnapshot(LoadTask task) {
            this.type = "load";
            this.remainingEntries = task.getRemainingEntries();
            this.batchSize = task.getBatchSize();
            this.total = task.getTotalEntries();
            this.state = null;
            this.saveToFile = false;
            this.saveFileName = null;
            this.sender = task.getSender();
            this.taskId = task.getTaskId();
            this.remainingBlocks = null;
            this.entriesToSaveForUserFile = null;
        }
    }
}