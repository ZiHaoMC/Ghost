/*
 * This module is a derivative work of Baritone (https://github.com/cabaletta/baritone).
 * This module is licensed under the GNU LGPL v3.0.
 */

package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.utils.RotationUtil; // 确保你有这个工具类
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;
import java.util.concurrent.*;

/**
 * 路径导航管理器 (Baritone 逻辑优化版)
 *
 * 修复内容：
 * 1. 堆优化：使用位运算二叉堆，移除 ArrayList 分配。
 * 2. 管道逻辑：只进不退，防止回头。
 * 3. 起步修剪：自动删除脚下的路径点，防止起步转圈。
 * 4. 旋转防抖：接近目标时锁定视角。
 */
public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ExecutorService pathPool = Executors.newSingleThreadExecutor();
    
    // 状态标志
    private static boolean isPathfinding = false;
    private static boolean isCalculating = false;
    private static List<BlockPos> currentPath = new CopyOnWriteArrayList<>();
    private static int currentPathIndex = 0;
    private static BlockPos globalTarget = null;

    private static final PathCache cache = new PathCache();

    // 配置参数
    private static final boolean DEBUG = true;
    private static final int MAX_CALC_NODES = 20000; // 稍微增加搜索深度

    // 卡死检测
    private static int stuckTimer = 0;
    private static Vec3 lastCheckPos = null;
    private static int lastPathIndex = -1;
    private static int indexStagnationTimer = 0;

    public static void setGlobalTarget(BlockPos target) {
        globalTarget = target;
        currentPath.clear();
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
        resetStuckTimers();
        cache.load();
        debug(LangUtil.translate("ghost.pathfinding.log.start", target));
        generateNextSegment();
    }

    public static void stop() {
        if (isPathfinding && DEBUG) debug(LangUtil.translate("ghost.pathfinding.log.stop"));
        isPathfinding = false;
        currentPath.clear();
        globalTarget = null;
        isCalculating = false;
        resetKeys();
        cache.save();
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static BlockPos getGlobalTarget() { return globalTarget; }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        cache.incrementDensity(new BlockPos(mc.thePlayer));

        // 到达终点判断 (1.5格内)
        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 2.25) {
            stop();
            mc.thePlayer.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghost.pathfinding.arrival"));
            return;
        }

        // 预生成下一段路径
        if (!isCalculating && (currentPath.isEmpty() || currentPathIndex >= currentPath.size() - 5)) {
            if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) > 9.0) {
                generateNextSegment();
            }
        }

        if (checkStuck()) {
            // 增加权重，避免下次走同样的路
            BlockPos head = new BlockPos(mc.thePlayer).offset(mc.thePlayer.getHorizontalFacing());
            cache.incrementDensity(head);
            cache.incrementDensity(new BlockPos(mc.thePlayer));
            
            currentPath.clear();
            generateNextSegment(); // 触发重寻
            return;
        }

        if (!currentPath.isEmpty() && currentPathIndex < currentPath.size()) {
            executeMovement();
        } else if (isCalculating) {
            resetKeys(); // 等待计算时停止移动
        }
    }

    private static void generateNextSegment() {
        if (isCalculating || globalTarget == null) return;
        isCalculating = true;

        final BlockPos startPos = new BlockPos(mc.thePlayer);
        final BlockPos finalTarget = globalTarget;

        pathPool.execute(() -> {
            try {
                // 1. 计算
                List<BlockPos> path = PathCalculator.compute(startPos, finalTarget, MAX_CALC_NODES, cache);
                
                mc.addScheduledTask(() -> {
                    if (isPathfinding) {
                        if (path != null && !path.isEmpty()) {
                            // 2. 平滑
                            List<BlockPos> smoothed = smoothPathRaytrace(path);
                            
                            // 3. 修剪 (关键修复：删除脚下的点，防止起步转圈)
                            List<BlockPos> prunedPath = new ArrayList<>(smoothed);
                            Vec3 playerPos = mc.thePlayer.getPositionVector();
                            
                            while (!prunedPath.isEmpty()) {
                                BlockPos first = prunedPath.get(0);
                                // 如果只剩最后一个点，保留
                                if (prunedPath.size() == 1) break;

                                double dX = playerPos.xCoord - (first.getX() + 0.5);
                                double dZ = playerPos.zCoord - (first.getZ() + 0.5);
                                double distSq = dX * dX + dZ * dZ;

                                // 如果非常近 (<0.8格) 或者已经在Y轴可控范围内
                                if (distSq < 0.64 && Math.abs(playerPos.yCoord - first.getY()) < 1.0) {
                                    prunedPath.remove(0);
                                } else {
                                    break;
                                }
                            }
                            
                            currentPath = new CopyOnWriteArrayList<>(prunedPath);
                            currentPathIndex = 0;
                            resetStuckTimers(); // 重置卡死计时
                        } else {
                            debugErr(LangUtil.translate("ghost.pathfinding.error.no_path"));
                        }
                    }
                    isCalculating = false;
                });
            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> {
                    isCalculating = false;
                });
            }
        });
    }

    // 限制平滑距离，防止穿墙
    private static List<BlockPos> smoothPathRaytrace(List<BlockPos> rawPath) {
        if (rawPath.size() <= 2) return rawPath;
        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(rawPath.get(0));
        
        int currentIdx = 0;
        while (currentIdx < rawPath.size() - 1) {
            int bestNext = currentIdx + 1;
            // 这里的 6 是向后看的最远距离，太远容易出错
            for (int i = Math.min(rawPath.size() - 1, currentIdx + 6); i > currentIdx + 1; i--) {
                BlockPos start = rawPath.get(currentIdx);
                BlockPos end = rawPath.get(i);
                
                // 物理距离限制，防止跨越太远
                if (start.distanceSq(end) > 36.0) continue;
                if (Math.abs(start.getY() - end.getY()) > 1) continue;
                
                if (canSee(start, end)) {
                    bestNext = i;
                    break;
                }
            }
            smoothed.add(rawPath.get(bestNext));
            currentIdx = bestNext;
        }
        return smoothed;
    }

    private static boolean canSee(BlockPos start, BlockPos end) {
        // 稍微抬高一点视线，避免扫到地毯或半砖
        Vec3 startVec = new Vec3(start.getX() + 0.5, start.getY() + 1.2, start.getZ() + 0.5);
        Vec3 endVec = new Vec3(end.getX() + 0.5, end.getY() + 1.2, end.getZ() + 0.5);
        return mc.theWorld.rayTraceBlocks(startVec, endVec, false, true, false) == null;
    }

    // 核心移动逻辑
    private static void executeMovement() {
        updatePathIndex();

        if (currentPath.isEmpty() || currentPathIndex >= currentPath.size()) return;

        BlockPos targetNode = currentPath.get(currentPathIndex);
        
        // --- 1. 目标插值 (LookAhead) ---
        double targetX = targetNode.getX() + 0.5;
        double targetZ = targetNode.getZ() + 0.5;
        
        // 如果有下一个点，视角看向当前点和下个点的中间，这样走直线更顺滑
        if (currentPathIndex < currentPath.size() - 1) {
            BlockPos nextNode = currentPath.get(currentPathIndex + 1);
            targetX = (targetX + nextNode.getX() + 0.5) / 2.0;
            targetZ = (targetZ + nextNode.getZ() + 0.5) / 2.0;
        }

        // --- 2. 旋转控制 (Deadzone) ---
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        double distToTarget = Math.sqrt(Math.pow(targetX - playerPos.xCoord, 2) + Math.pow(targetZ - playerPos.zCoord, 2));

        // 只有距离大于 0.3 时才转头，否则保持当前角度冲过去 (防止原地转圈)
        if (distToTarget > 0.3) {
            Vec3 lookTarget = new Vec3(targetX, targetNode.getY() + (isOnLadder() ? 1.0 : 0.0), targetZ);
            float[] neededRotations = RotationUtil.getRotations(lookTarget);
            
            float currentYaw = mc.thePlayer.rotationYaw;
            float diff = MathHelper.wrapAngleTo180_float(neededRotations[0] - currentYaw);
            
            // 只有偏差大于 5 度才调整
            if (Math.abs(diff) > 5.0f) {
                float speed = Math.abs(diff) > 30 ? 60f : 30f; // 动态转速
                mc.thePlayer.rotationYaw = limitAngleChange(currentYaw, neededRotations[0], speed);
            }
            mc.thePlayer.rotationPitch = 0;
        }

        // --- 3. 移动输入 ---
        boolean forward = true;
        boolean jump = false;
        boolean sprint = true;

        if (isOnLadder()) {
            sprint = false;
            if (Math.abs(targetNode.getY() - mc.thePlayer.posY) < 0.2) forward = false;
        } else if (mc.thePlayer.isInWater()) {
            sprint = false;
            // 如果目标在上面，或者水淹过头了
            if (targetNode.getY() >= mc.thePlayer.posY || mc.thePlayer.getAir() < 100) {
                jump = true;
            }
        } else {
            // 地面逻辑
            if (mc.thePlayer.onGround) {
                // 如果撞墙了或者目标较高，跳
                if (mc.thePlayer.isCollidedHorizontally || targetNode.getY() > mc.thePlayer.posY + 0.6) {
                    jump = true;
                }
            }
        }
        
        // 角度偏差太大不跑
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - RotationUtil.getRotations(new Vec3(targetX, targetNode.getY(), targetZ))[0]));
        if (yawDiff > 45) sprint = false;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
        mc.thePlayer.setSprinting(sprint);
    }

    // 管道式索引更新：只进不退
    private static void updatePathIndex() {
        if (currentPath.isEmpty()) return;
        if (currentPathIndex >= currentPath.size() - 1) return;

        Vec3 playerPos = mc.thePlayer.getPositionVector();
        BlockPos current = currentPath.get(currentPathIndex);
        BlockPos next = currentPath.get(currentPathIndex + 1);

        double distToCurrentSq = Math.pow(playerPos.xCoord - (current.getX() + 0.5), 2) + Math.pow(playerPos.zCoord - (current.getZ() + 0.5), 2);
        double distToNextSq = Math.pow(playerPos.xCoord - (next.getX() + 0.5), 2) + Math.pow(playerPos.zCoord - (next.getZ() + 0.5), 2);

        // 1. 距离竞争 (带缓冲/滞后)
        // 只有当离 Next 显著更近 (0.5格缓冲) 时才切换
        if (distToNextSq < distToCurrentSq - 0.5) {
            currentPathIndex++;
            return;
        }
        
        // 2. 贴脸强制切换
        // 如果已经踩在 Current 脸上了 (< 0.64 sq -> 0.8 block)，直接去下一个
        if (distToCurrentSq < 0.64) {
            currentPathIndex++;
            return;
        }

        // 3. 包围盒判定 (Baritone 逻辑)
        int playerBlockX = MathHelper.floor_double(mc.thePlayer.posX);
        int playerBlockY = MathHelper.floor_double(mc.thePlayer.posY);
        int playerBlockZ = MathHelper.floor_double(mc.thePlayer.posZ);

        if (playerBlockX == next.getX() && playerBlockZ == next.getZ()) {
            if (playerBlockY >= next.getY() - 1 && playerBlockY <= next.getY() + 1) {
                currentPathIndex++;
            }
        }
    }

    private static boolean checkStuck() {
        stuckTimer++;
        
        // 1. 放宽水平卡死检测 (80 ticks = 4秒)
        if (stuckTimer > 80) { 
            stuckTimer = 0;
            Vec3 currentPos = mc.thePlayer.getPositionVector();
            if (lastCheckPos != null) {
                double dx = currentPos.xCoord - lastCheckPos.xCoord;
                double dz = currentPos.zCoord - lastCheckPos.zCoord;
                if (Math.sqrt(dx * dx + dz * dz) < 0.5) { // 4秒没走半格
                    debugErr(LangUtil.translate("ghost.pathfinding.stuck.horizontal"));
                    return true;
                }
            }
            lastCheckPos = currentPos;
        }

        // 2. 索引停滞检测 (100 ticks = 5秒)
        if (currentPathIndex == lastPathIndex && !currentPath.isEmpty()) {
            indexStagnationTimer++;
            if (indexStagnationTimer > 100) {
                indexStagnationTimer = 0;
                debugErr(LangUtil.translate("ghost.pathfinding.stuck.index"));
                return true;
            }
        } else {
            // 只要索引动了，就清除所有嫌疑
            lastPathIndex = currentPathIndex;
            indexStagnationTimer = 0;
            stuckTimer = 0; 
        }

        return false;
    }

    private static void resetStuckTimers() { 
        stuckTimer = 0; 
        lastCheckPos = null; 
        lastPathIndex = -1;
        indexStagnationTimer = 0;
    }

    private static boolean isOnLadder() {
        if(mc.thePlayer == null) return false;
        BlockPos pos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.ladder || block == Blocks.vine;
    }

    private static float limitAngleChange(float current, float target, float maxChange) { 
        float change = MathHelper.wrapAngleTo180_float(target - current);
        change = MathHelper.clamp_float(change, -maxChange, maxChange);
        return current + change;
    }

    private static void resetKeys() { 
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
    }

    private static void debug(String msg) { if (DEBUG && mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Path] " + msg)); }
    private static void debugErr(String msg) { if (DEBUG && mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Path] " + msg)); }

    public static class PathCache {
        private final ConcurrentHashMap<Long, Integer> densityMap = new ConcurrentHashMap<>();
        public static long toLong(BlockPos pos) {
            return ((long) pos.getX() & 0x7FFFFFF) | (((long) pos.getZ() & 0x7FFFFFF) << 27) | ((long) pos.getY() << 54);
        }
        public void load() { densityMap.clear(); } 
        public void save() { } 
        public void incrementDensity(BlockPos pos) { densityMap.merge(toLong(pos), 1, Integer::sum); }
        public double getCostPenalty(BlockPos pos) { return densityMap.getOrDefault(toLong(pos), 0) * 2.0; }
    }

    // --- 高性能 A* 计算器 ---
    private static class PathCalculator {
        private static final double COST_WALK = 1.0;
        private static final double COST_JUMP = 2.0; 
        private static final double COST_FALL = 1.0; 
        private static final double COST_LADDER = 2.0;
        private static final double COST_SWIM = 3.0;

        public static List<BlockPos> compute(BlockPos start, BlockPos end, int limit, PathCache cache) {
            FastBinaryHeap openSet = new FastBinaryHeap(limit);
            // 给足容量减少 Resize
            Map<Long, Node> allNodes = new HashMap<>(16384);

            Node startNode = new Node(start, null, 0, getHeuristic(start, end));
            openSet.insert(startNode);
            allNodes.put(PathCache.toLong(start), startNode);

            Node bestNodeSoFar = startNode;
            double minHeuristic = startNode.hCost;
            int iterations = 0;
            
            // 缓存对象，避免循环内创建
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

            while (!openSet.isEmpty() && iterations < limit) {
                iterations++;
                Node current = openSet.removeLowest();

                if (current.hCost < minHeuristic) {
                    minHeuristic = current.hCost;
                    bestNodeSoFar = current;
                }

                if (current.pos.distanceSq(end) < 2.25) {
                    return reconstructPath(current);
                }

                // 展开邻居逻辑，避免创建 ArrayList
                processNeighbors(current, end, openSet, allNodes, cache, mutable);
            }
            
            if (bestNodeSoFar != null && bestNodeSoFar != startNode) return reconstructPath(bestNodeSoFar);
            return null;
        }

        private static void processNeighbors(Node current, BlockPos end, FastBinaryHeap openSet, Map<Long, Node> allNodes, PathCache cache, BlockPos.MutableBlockPos mutable) {
            for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                BlockPos offset = current.pos.offset(dir);
                
                // 平地
                if (isWalkable(offset)) {
                    addNode(current, offset, end, COST_WALK, openSet, allNodes, cache);
                } else {
                    // 自动跳跃
                    BlockPos up = offset.up();
                    if (isWalkable(up) && canGoThrough(current.pos.up(2))) {
                         addNode(current, up, end, COST_JUMP, openSet, allNodes, cache);
                    } else {
                        // 下落 (最多3格)
                        for (int i = 1; i <= 3; i++) {
                            BlockPos down = offset.down(i);
                            if (!canGoThrough(offset.down(i-1))) break; 
                            if (isWalkable(down)) {
                                addNode(current, down, end, COST_FALL, openSet, allNodes, cache);
                                break;
                            }
                        }
                    }
                }
            }
            
            // 梯子逻辑
            if (isLadder(current.pos)) {
                 if (isLadder(current.pos.up()) || isWalkable(current.pos.up())) {
                     addNode(current, current.pos.up(), end, COST_LADDER, openSet, allNodes, cache);
                 }
                 if (isLadder(current.pos.down()) || isWalkable(current.pos.down())) {
                     addNode(current, current.pos.down(), end, COST_LADDER, openSet, allNodes, cache);
                 }
            }
        }

        private static void addNode(Node current, BlockPos pos, BlockPos end, double costInfo, FastBinaryHeap openSet, Map<Long, Node> allNodes, PathCache cache) {
            long key = PathCache.toLong(pos);
            double penalty = (cache != null) ? cache.getCostPenalty(pos) : 0;
            if (isWater(pos)) costInfo *= COST_SWIM;
            
            double newG = current.gCost + costInfo + penalty;
            Node existing = allNodes.get(key);
            
            if (existing == null) {
                Node newNode = new Node(pos, current, newG, getHeuristic(pos, end));
                allNodes.put(key, newNode);
                openSet.insert(newNode);
            } else if (newG < existing.gCost) {
                existing.parent = current;
                existing.gCost = newG;
                existing.fCost = newG + existing.hCost;
                openSet.update(existing);
            }
        }

        private static List<BlockPos> reconstructPath(Node node) {
            List<BlockPos> path = new ArrayList<>();
            while (node != null) { path.add(node.pos); node = node.parent; }
            Collections.reverse(path);
            return path;
        }

        private static double getHeuristic(BlockPos pos, BlockPos target) { 
            // 欧几里得距离虽然稍慢，但在这用能获得更平滑的路径
            return Math.sqrt(pos.distanceSq(target)); 
        }

        // 简化的物理判定
        public static boolean isWalkable(BlockPos pos) { 
            if (isWater(pos)) return canGoThrough(pos);
            if (!isSolid(pos.down())) return false;
            if (!canGoThrough(pos) || !canGoThrough(pos.up())) return false;
            
            IBlockState downState = mc.theWorld.getBlockState(pos.down());
            Block downBlock = downState.getBlock();
            if (downBlock instanceof BlockFence || downBlock instanceof BlockWall) return false;
            
            return isSafe(pos);
        }

        public static boolean isSolid(BlockPos pos) { 
            IBlockState state = mc.theWorld.getBlockState(pos);
            Block block = state.getBlock();
            if (block instanceof BlockLiquid || block instanceof BlockLadder || block instanceof BlockVine) return false;
            return block.getMaterial().isSolid() && block.isCollidable(); 
        }
        
        public static boolean canGoThrough(BlockPos pos) {
            IBlockState state = mc.theWorld.getBlockState(pos);
            Block block = state.getBlock();
            if (block instanceof BlockFenceGate && state.getValue(BlockFenceGate.OPEN)) return true;
            if (block instanceof BlockDoor && (state.getValue(BlockDoor.OPEN) || block.getMaterial() == Material.circuits)) return true; 
            return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null || !block.getMaterial().isSolid();
        }

        public static boolean isWater(BlockPos pos) { return mc.theWorld.getBlockState(pos).getBlock().getMaterial() == Material.water; }
        public static boolean isLadder(BlockPos pos) { Block block = mc.theWorld.getBlockState(pos).getBlock(); return block instanceof BlockLadder || block instanceof BlockVine; }
        public static boolean isSafe(BlockPos pos) { 
            Block in = mc.theWorld.getBlockState(pos).getBlock();
            Block below = mc.theWorld.getBlockState(pos.down()).getBlock();
            if (in.getMaterial() == Material.lava || in instanceof BlockFire) return false; 
            return !(below instanceof BlockCactus) && below.getMaterial() != Material.lava;
        }

        private static class Node {
            final BlockPos pos; Node parent; double gCost; double hCost; double fCost; int heapIndex = -1;
            public Node(BlockPos p, Node pa, double g, double h) { pos = p; parent = pa; gCost = g; hCost = h; fCost = g + h; }
        }

        // 1-based index Binary Heap, optimized
        private static class FastBinaryHeap {
            private Node[] array; private int size;
            public FastBinaryHeap(int cap) { array = new Node[cap + 1]; }
            public boolean isEmpty() { return size == 0; }
            
            public void insert(Node node) {
                if (size >= array.length - 1) { // 简单扩容
                    Node[] newArray = new Node[array.length * 2];
                    System.arraycopy(array, 0, newArray, 0, array.length);
                    array = newArray;
                }
                size++;
                node.heapIndex = size;
                array[size] = node;
                upHeap(size);
            }
            
            public Node removeLowest() {
                if (size == 0) return null;
                Node res = array[1];
                Node last = array[size];
                array[1] = last;
                last.heapIndex = 1;
                array[size] = null;
                size--;
                res.heapIndex = -1;
                if (size > 1) downHeap(1);
                return res;
            }
            
            public void update(Node node) {
                // A* 只会减少代价，所以通常只需要 upHeap
                upHeap(node.heapIndex);
            }
            
            private void upHeap(int idx) {
                Node node = array[idx];
                double cost = node.fCost;
                while (idx > 1) {
                    int pIdx = idx >> 1;
                    Node parent = array[pIdx];
                    if (cost < parent.fCost) {
                        array[idx] = parent;
                        parent.heapIndex = idx;
                        idx = pIdx;
                    } else break;
                }
                array[idx] = node;
                node.heapIndex = idx;
            }
            
            private void downHeap(int idx) {
                Node node = array[idx];
                double cost = node.fCost;
                int half = size >> 1;
                while (idx <= half) {
                    int child = idx << 1;
                    if (child < size && array[child + 1].fCost < array[child].fCost) child++;
                    if (cost <= array[child].fCost) break;
                    array[idx] = array[child];
                    array[child].heapIndex = idx;
                    idx = child;
                }
                array[idx] = node;
                node.heapIndex = idx;
            }
        }
    }
}