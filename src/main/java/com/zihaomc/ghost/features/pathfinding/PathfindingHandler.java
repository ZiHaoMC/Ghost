package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.features.ghostblock.GhostBlockHelper;
import com.zihaomc.ghost.utils.RotationUtil;
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
 * 路径导航管理器 (卡死重寻增强版)
 * 
 * 修改说明：
 * 1. 优化 checkStuck 逻辑，改用水平距离判断，解决原地跳跃欺骗检测的问题。
 * 2. 增加路径点停滞检测，若长时间未到达下一个目标点则强制重寻路。
 * 3. 增强密度图反馈，卡死位置将被标记为高代价区域。
 */
public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ExecutorService pathPool = Executors.newSingleThreadExecutor();
    private static final Random rng = new Random();

    // 状态标志
    private static boolean isPathfinding = false;
    private static boolean isCalculating = false;
    private static List<BlockPos> currentPath = new CopyOnWriteArrayList<>();
    private static int currentPathIndex = 0;
    private static BlockPos globalTarget = null;

    // 路径记忆系统
    private static final PathCache cache = new PathCache();

    // 配置参数
    private static final boolean DEBUG = true;
    private static final int MAX_CALC_NODES = 15000; 
    
    // 卡死检测相关变量
    private static int stuckTimer = 0;
    private static int collisionTimer = 0;
    private static Vec3 lastCheckPos = null;
    private static int lastPathIndex = -1;
    private static int indexStagnationTimer = 0;

    // --- 公共 API ---

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

        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 1.5) {
            stop();
            mc.thePlayer.addChatMessage(GhostBlockHelper.formatMessage(EnumChatFormatting.GREEN, "ghost.pathfinding.arrival"));
            return;
        }

        // 路径预生成逻辑
        if (!isCalculating && (currentPath.isEmpty() || currentPathIndex >= currentPath.size() - 5)) {
            if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) > 2.0) {
                generateNextSegment();
            }
        }

        // 核心卡死处理逻辑
        if (checkStuck()) {
            // 获取玩家面前的方块并增加密度，防止再次撞墙
            BlockPos head = new BlockPos(mc.thePlayer).offset(mc.thePlayer.getHorizontalFacing());
            cache.incrementDensity(head); 
            cache.incrementDensity(new BlockPos(mc.thePlayer));
            
            // 立即清除当前路径并触发重新计算
            currentPath.clear();
            generateNextSegment();
            return;
        }

        if (!currentPath.isEmpty() && currentPathIndex < currentPath.size()) {
            executeMovement();
        } else if (isCalculating) {
            resetKeys(); 
        }
    }

    private static void generateNextSegment() {
        if (isCalculating || globalTarget == null) return;
        isCalculating = true;

        final BlockPos startPos = new BlockPos(mc.thePlayer);
        final BlockPos finalTarget = globalTarget;

        pathPool.execute(() -> {
            try {
                List<BlockPos> path = PathCalculator.compute(startPos, finalTarget, MAX_CALC_NODES, cache);

                mc.addScheduledTask(() -> {
                    if (isPathfinding) {
                        if (path != null && !path.isEmpty()) {
                            List<BlockPos> smoothed = smoothPathRaytrace(path);
                            currentPath = new CopyOnWriteArrayList<>(smoothed);
                            currentPathIndex = 0;
                            resetStuckTimers(); // 成功获得新路径后重置计时器
                        } else {
                            debugErr(LangUtil.translate("ghost.pathfinding.error.no_path"));
                        }
                    }
                    isCalculating = false;
                });

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> {
                    debugErr(LangUtil.translate("ghost.pathfinding.error.crash", e.getMessage()));
                    isCalculating = false;
                });
            }
        });
    }

    // --- 平滑算法 ---
    private static List<BlockPos> smoothPathRaytrace(List<BlockPos> rawPath) {
        if (rawPath.size() <= 2) return rawPath;
        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(rawPath.get(0));
        int currentIdx = 0;
        while (currentIdx < rawPath.size() - 1) {
            int bestNext = currentIdx + 1;
            for (int i = Math.min(rawPath.size() - 1, currentIdx + 12); i > currentIdx + 1; i--) {
                BlockPos start = rawPath.get(currentIdx);
                BlockPos end = rawPath.get(i);
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
        Vec3 startVec = new Vec3(start.getX() + 0.5, start.getY() + 1.0, start.getZ() + 0.5);
        Vec3 endVec = new Vec3(end.getX() + 0.5, end.getY() + 1.0, end.getZ() + 0.5);
        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startVec, endVec, false, true, false);
        return result == null;
    }

    // --- 移动执行逻辑 ---
    private static void executeMovement() {
        updatePathIndex();
        if (currentPathIndex >= currentPath.size()) return;

        BlockPos nextNode = currentPath.get(currentPathIndex);
        boolean inWater = mc.thePlayer.isInWater();
        boolean onLadder = isOnLadder();

        Vec3 lookTarget;
        double jitter = (rng.nextDouble() - 0.5) * 0.1;
        double targetX = nextNode.getX() + 0.5 + jitter;
        double targetZ = nextNode.getZ() + 0.5 + jitter;
        
        if (onLadder || inWater) {
             lookTarget = new Vec3(targetX, nextNode.getY() > mc.thePlayer.posY ? nextNode.getY() + 1 : nextNode.getY(), targetZ);
        } else {
            double yDiff = nextNode.getY() - mc.thePlayer.posY;
            if (yDiff > 0.5) {
                lookTarget = new Vec3(targetX, nextNode.getY() + 1.5, targetZ); 
            } else if (yDiff < -0.5) {
                lookTarget = new Vec3(targetX, nextNode.getY() - 0.5, targetZ);
            } else {
                lookTarget = new Vec3(targetX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), targetZ); 
            }
        }

        float[] rotations = RotationUtil.getRotations(lookTarget);
        float smoothFactor = 20f; 
        mc.thePlayer.rotationYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], smoothFactor);
        mc.thePlayer.rotationPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], smoothFactor);

        boolean jump = false;
        boolean forward = true;
        boolean sprint = true;

        if (onLadder) {
            sprint = false;
            if (Math.abs(nextNode.getY() - mc.thePlayer.posY) < 0.2) forward = false;
        } else if (inWater) {
            sprint = false;
            if (nextNode.getY() > mc.thePlayer.posY || (nextNode.getY() == Math.floor(mc.thePlayer.posY) && mc.thePlayer.posY % 1 > 0.8)) {
                jump = true;
            }
        } else {
            if (mc.thePlayer.onGround) {
                if (nextNode.getY() > mc.thePlayer.posY + 0.6) {
                    jump = true;
                } else if (nextNode.getY() == Math.floor(mc.thePlayer.posY)) {
                    double dist = Math.hypot(nextNode.getX() + 0.5 - mc.thePlayer.posX, nextNode.getZ() + 0.5 - mc.thePlayer.posZ);
                    if (dist > 1.2 && !mc.thePlayer.isCollidedHorizontally) {
                         BlockPos belowNext = nextNode.down();
                         if (!PathCalculator.isSolid(belowNext) && !PathCalculator.isLadder(belowNext) && !PathCalculator.isWater(belowNext)) {
                             jump = true;
                         }
                    }
                }
                
                if (mc.theWorld.getBlockState(new BlockPos(mc.thePlayer).down()).getBlock() == Blocks.slime_block) {
                    sprint = false;
                }

                if (mc.thePlayer.isCollidedHorizontally && forward) {
                    collisionTimer++;
                    if (collisionTimer > 2) jump = true;
                } else {
                    collisionTimer = 0;
                }
            }
        }
        
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - rotations[0]));
        if (yawDiff > 45) sprint = false;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
        mc.thePlayer.setSprinting(sprint);
    }

    private static void updatePathIndex() {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        double minDistance = Double.MAX_VALUE;
        int bestIndex = currentPathIndex;

        for (int i = currentPathIndex; i < Math.min(currentPathIndex + 8, currentPath.size()); i++) {
            BlockPos node = currentPath.get(i);
            double dist = playerPos.squareDistanceTo(new Vec3(node.getX() + 0.5, node.getY() + 0.5, node.getZ() + 0.5));
            if (dist < minDistance) {
                minDistance = dist;
                bestIndex = i;
            }
        }
        currentPathIndex = bestIndex;

        if (currentPathIndex < currentPath.size()) {
            BlockPos current = currentPath.get(currentPathIndex);
            double hDist = Math.pow(current.getX() + 0.5 - playerPos.xCoord, 2) + Math.pow(current.getZ() + 0.5 - playerPos.zCoord, 2);
            if (hDist < 0.6 * 0.6 && Math.abs(current.getY() - playerPos.yCoord) < 1.5) { 
                currentPathIndex++;
            }
        }
    }

    /**
     * 增强版卡死检测
     */
    private static boolean checkStuck() {
        // 1. 坐标位移检测 (每2秒检查一次)
        stuckTimer++;
        if (stuckTimer > 40) {
            stuckTimer = 0;
            Vec3 currentPos = mc.thePlayer.getPositionVector();
            if (lastCheckPos != null) {
                // 计算水平距离 (忽略Y轴的变化，解决原地跳跃欺骗检测的问题)
                double dx = currentPos.xCoord - lastCheckPos.xCoord;
                double dz = currentPos.zCoord - lastCheckPos.zCoord;
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                
                if (horizontalDist < 0.25) { 
                    debugErr(LangUtil.translate("ghost.pathfinding.stuck.horizontal"));
                    return true;
                }
            }
            lastCheckPos = currentPos;
        }

        // 2. 路径索引停滞检测 (如果玩家3秒内没有推进路径进度)
        if (currentPathIndex == lastPathIndex && !currentPath.isEmpty()) {
            indexStagnationTimer++;
            if (indexStagnationTimer > 60) {
                indexStagnationTimer = 0;
                debugErr(LangUtil.translate("ghost.pathfinding.stuck.index"));
                return true;
            }
        } else {
            lastPathIndex = currentPathIndex;
            indexStagnationTimer = 0;
        }

        return false;
    }

    private static void resetStuckTimers() { 
        stuckTimer = 0; 
        collisionTimer = 0; 
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

    // ==========================================
    //           PathCache (密度热力图系统)
    // ==========================================
    public static class PathCache {
        private final ConcurrentHashMap<Long, Integer> densityMap = new ConcurrentHashMap<>();

        public static long toLong(BlockPos pos) {
            return ((long) pos.getX() & 0x7FFFFFF) | (((long) pos.getZ() & 0x7FFFFFF) << 27) | ((long) pos.getY() << 54);
        }
        public void load() { densityMap.clear(); } 
        public void save() { } 
        public void incrementDensity(BlockPos pos) { 
            densityMap.merge(toLong(pos), 1, Integer::sum); 
        }
        public double getCostPenalty(BlockPos pos) {
            return densityMap.getOrDefault(toLong(pos), 0) * 1.5; 
        }
    }

    // =======================================================
    //            PathCalculator (A* 核心重构)
    // =======================================================
    private static class PathCalculator {
        private static final double COST_WALK = 1.0;
        private static final double COST_JUMP = 1.2; 
        private static final double COST_FALL = 1.0; 
        private static final double COST_LADDER = 1.5;
        private static final double COST_SWIM = 2.0;
        private static final double COST_SLIME_BLOCK = 4.0;
        private static final double COST_SOUL_SAND = 4.0;

        public static List<BlockPos> compute(BlockPos start, BlockPos end, int limit, PathCache cache) {
            FastBinaryHeap openSet = new FastBinaryHeap(limit + 100);
            Map<Long, Node> allNodes = new HashMap<>(4096);

            Node startNode = new Node(start, null, 0, getHeuristic(start, end));
            openSet.add(startNode);
            allNodes.put(PathCache.toLong(start), startNode);

            Node bestNodeSoFar = startNode;
            double minHeuristic = startNode.hCost;

            int iterations = 0;

            while (!openSet.isEmpty() && iterations < limit) {
                iterations++;
                Node current = openSet.removeFirst();

                if (current.hCost < minHeuristic) {
                    minHeuristic = current.hCost;
                    bestNodeSoFar = current;
                }

                if (current.pos.distanceSq(end) < 2.5) {
                    return reconstructPath(current);
                }

                List<BlockPos> neighbors = getNeighborsPos(current.pos);
                for (BlockPos neighborPos : neighbors) {
                    long neighborKey = PathCache.toLong(neighborPos);
                    
                    double moveCost = getCost(current.pos, neighborPos);
                    double penalty = (cache != null) ? cache.getCostPenalty(neighborPos) : 0;
                    double newGCost = current.gCost + moveCost + penalty;

                    Node existingNode = allNodes.get(neighborKey);

                    if (existingNode == null) {
                        double hCost = getHeuristic(neighborPos, end);
                        Node newNode = new Node(neighborPos, current, newGCost, hCost);
                        allNodes.put(neighborKey, newNode);
                        openSet.add(newNode);
                    } else if (newGCost < existingNode.gCost) {
                        existingNode.parent = current;
                        existingNode.gCost = newGCost;
                        existingNode.fCost = newGCost + existingNode.hCost;
                        
                        if (openSet.contains(existingNode)) {
                            openSet.update(existingNode);
                        } else {
                            openSet.add(existingNode);
                        }
                    }
                }
            }

            if (bestNodeSoFar != null && bestNodeSoFar != startNode) {
                return reconstructPath(bestNodeSoFar);
            }
            
            return null;
        }

        private static List<BlockPos> reconstructPath(Node node) {
            List<BlockPos> path = new ArrayList<>();
            while (node != null) { 
                path.add(node.pos); 
                node = node.parent; 
            }
            Collections.reverse(path);
            return path;
        }

        private static double getHeuristic(BlockPos pos, BlockPos target) {
            return Math.sqrt(pos.distanceSq(target));
        }

        private static double getCost(BlockPos current, BlockPos next) {
            double baseCost = COST_WALK;

            if (next.getY() > current.getY()) {
                baseCost = COST_JUMP;
            } else if (next.getY() < current.getY()) {
                baseCost = COST_FALL;
            } else if (isLadder(next)) {
                baseCost = COST_LADDER;
            } else if (isWater(next)) {
                baseCost = COST_SWIM;
            }

            Block groundBlock = mc.theWorld.getBlockState(next.down()).getBlock();
            
            if (groundBlock == Blocks.slime_block) {
                baseCost += COST_SLIME_BLOCK;
            } else if (groundBlock == Blocks.soul_sand) {
                baseCost += COST_SOUL_SAND;
            }

            return baseCost;
        }


        private static List<BlockPos> getNeighborsPos(BlockPos pos) {
            List<BlockPos> moves = new ArrayList<>(8);
            IBlockState state = mc.theWorld.getBlockState(pos);
            boolean inWater = state.getBlock().getMaterial() == Material.water;

            if (inWater) {
                BlockPos up = pos.up();
                if (canGoThrough(up)) moves.add(up);
                for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                    BlockPos offset = pos.offset(dir);
                    if (canGoThrough(offset)) moves.add(offset);
                }
                return moves;
            }

            for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                BlockPos offset = pos.offset(dir);
                if (isWalkable(offset)) {
                    moves.add(offset);
                }
                else if (isWalkable(offset.up()) && canGoThrough(pos.up(2))) {
                    moves.add(offset.up()); 
                }
                else {
                    for (int i = 1; i <= 20; i++) {
                        BlockPos target = offset.down(i);
                        if (!canGoThrough(offset.down(i-1))) break; 

                        if (isWalkable(target)) {
                            if (i <= 3 || isSafeHighFall(target)) {
                                moves.add(target);
                            }
                            break; 
                        }
                    }
                }
            }
            
            for (int x = -1; x <= 1; x += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    BlockPos diag = pos.add(x, 0, z);
                    if (isWalkable(diag)) {
                        boolean b1 = canGoThrough(pos.add(x, 0, 0));
                        boolean b2 = canGoThrough(pos.add(0, 0, z));
                        if (b1 && b2) {
                             moves.add(diag);
                        }
                    }
                }
            }

            if (isLadder(pos)) {
                if (isLadder(pos.up()) || isWalkable(pos.up())) moves.add(pos.up());
                if (isLadder(pos.down()) || isWalkable(pos.down())) moves.add(pos.down());
            }

            return moves;
        }

        public static boolean isWalkable(BlockPos pos) { 
            if (isWater(pos)) return canGoThrough(pos);
            if (!isSolid(pos.down())) return false;
            if (!canGoThrough(pos) || !canGoThrough(pos.up())) return false;
            
            IBlockState downState = mc.theWorld.getBlockState(pos.down());
            Block downBlock = downState.getBlock();
            boolean isHighGround = downBlock instanceof BlockFence || downBlock instanceof BlockWall || 
                                  (downBlock instanceof BlockFenceGate && !downState.getValue(BlockFenceGate.OPEN));
            if (isHighGround) {
                if (!canGoThrough(pos.up(2))) return false;
            }
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
            if (block instanceof BlockDoor && state.getValue(BlockDoor.OPEN)) return true;
            return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null 
                || block instanceof BlockAir || block instanceof BlockLiquid || block instanceof BlockLadder || block instanceof BlockVine || !block.getMaterial().isSolid();
        }

        public static boolean isWater(BlockPos pos) {
            return mc.theWorld.getBlockState(pos).getBlock().getMaterial() == Material.water;
        }
        
        public static boolean isLadder(BlockPos pos) { 
            Block block = mc.theWorld.getBlockState(pos).getBlock(); 
            return block instanceof BlockLadder || block instanceof BlockVine; 
        }

        public static boolean isSafe(BlockPos pos) { 
            Block in = mc.theWorld.getBlockState(pos).getBlock();
            if (in.getMaterial() == Material.lava || in instanceof BlockFire) return false; 
            Block down = mc.theWorld.getBlockState(pos.down()).getBlock();
            if (down instanceof BlockCactus) return false; 
            return true;
        }

        private static boolean isSafeHighFall(BlockPos pos) {
            if (isWater(pos)) return true;
            Block blockBelow = mc.theWorld.getBlockState(pos.down()).getBlock();
            return blockBelow == Blocks.slime_block || blockBelow == Blocks.hay_block;
        }

        private static class Node implements Comparable<Node> {
            BlockPos pos;
            Node parent;
            double gCost; 
            double hCost; 
            double fCost; 
            int heapIndex = -1;

            public Node(BlockPos p, Node pa, double g, double h) { 
                pos = p; parent = pa; gCost = g; hCost = h; fCost = g + h; 
            }
            
            @Override public int compareTo(Node o) { 
                int cmp = Double.compare(this.fCost, o.fCost); 
                return (cmp == 0) ? Double.compare(this.hCost, o.hCost) : cmp; 
            }
        }

        private static class FastBinaryHeap {
            private final Node[] items;
            private int currentItemCount;

            public FastBinaryHeap(int maxHeapSize) {
                items = new Node[maxHeapSize + 1];
                currentItemCount = 0;
            }

            public void add(Node item) {
                if (currentItemCount >= items.length - 1) return; 
                currentItemCount++;
                item.heapIndex = currentItemCount;
                items[currentItemCount] = item;
                sortUp(item);
            }

            public Node removeFirst() {
                if (currentItemCount == 0) return null; 
                
                Node firstItem = items[1];
                currentItemCount--;
                
                if (currentItemCount > 0) {
                    items[1] = items[currentItemCount + 1];
                    items[1].heapIndex = 1;
                    items[currentItemCount + 1] = null;
                    sortDown(items[1]);
                } else {
                    items[1] = null;
                }
                
                return firstItem;
            }

            public void update(Node item) {
                sortUp(item);
            }

            public boolean contains(Node item) {
                return item.heapIndex != -1;
            }

            public boolean isEmpty() {
                return currentItemCount == 0;
            }

            private void sortUp(Node item) {
                int parentIndex = item.heapIndex / 2;
                while (parentIndex > 0) {
                    Node parentItem = items[parentIndex];
                    if (item.compareTo(parentItem) < 0) {
                        swap(item, parentItem);
                    } else {
                        break;
                    }
                    parentIndex = item.heapIndex / 2;
                }
            }

            private void sortDown(Node item) {
                while (true) {
                    int childIndexLeft = item.heapIndex * 2;
                    int childIndexRight = item.heapIndex * 2 + 1;
                    int swapIndex = 0;

                    if (childIndexLeft <= currentItemCount) {
                        swapIndex = childIndexLeft;
                        if (childIndexRight <= currentItemCount) {
                            if (items[childIndexLeft].compareTo(items[childIndexRight]) > 0) {
                                swapIndex = childIndexRight;
                            }
                        }
                        if (item.compareTo(items[swapIndex]) > 0) {
                            swap(item, items[swapIndex]);
                        } else {
                            return;
                        }
                    } else {
                        return;
                    }
                }
            }

            private void swap(Node itemA, Node itemB) {
                items[itemA.heapIndex] = itemB;
                items[itemB.heapIndex] = itemA;
                int itemAIndex = itemA.heapIndex;
                itemA.heapIndex = itemB.heapIndex;
                itemB.heapIndex = itemAIndex;
            }
        }
    }
}