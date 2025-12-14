package com.zihaomc.ghost.features.pathfinding;

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

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 路径导航管理器 (重构版)
 * 核心改进：移除射线检测，完全依赖带有 Best-So-Far 机制的 A* 算法。
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

    // 路径记忆系统 (防卡死黑名单 + 热力图)
    private static final PathCache cache = new PathCache();

    // 配置参数
    private static final boolean DEBUG = true;
    // 增加节点上限，因为我们需要算得更远。这里给 5000 足够应对大多数地形
    private static final int MAX_CALC_NODES = 5000; 
    
    // 卡死检测变量
    private static int stuckTimer = 0;
    private static int collisionTimer = 0;
    private static Vec3 lastCheckPos = null;

    // --- 公共 API ---

    public static void setGlobalTarget(BlockPos target) {
        globalTarget = target;
        currentPath.clear();
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
        resetStuckTimers();
        cache.load(); 
        // 切换目标时，最好稍微降低之前的热度，或者完全清空，取决于需求
        // cache.clearDensity(); 
        debug(">>> 全局目标设定: " + target);
        generateNextSegment(); // 触发计算
    }

    public static void stop() {
        if (isPathfinding && DEBUG) debug("<<< 寻路停止");
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

        // 1. 更新区域热力图 (记录足迹)
        // 这会让 A* 算法天然厌恶走回头路
        cache.incrementDensity(new BlockPos(mc.thePlayer));

        // 2. 终点到达检查
        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 2.0) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        // 3. 路径衔接管理
        // 如果没有在计算，且路径快走完了（或者压根没路径），就去算下一段
        if (!isCalculating && (currentPath.isEmpty() || currentPathIndex >= currentPath.size() - 5)) {
             // 只有距离目标还远的时候才算
            if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) > 2.0) {
                generateNextSegment();
            }
        }

        // 4. 卡死与异常检测
        if (checkStuck()) {
            // 标记当前前方为死路，增加热度，强制 A* 下次换路
            BlockPos head = new BlockPos(mc.thePlayer).offset(mc.thePlayer.getHorizontalFacing());
            cache.incrementDensity(head); // 增加当前位置的厌恶值
            cache.incrementDensity(new BlockPos(mc.thePlayer));
            
            currentPath.clear();
            generateNextSegment(); // 强制重算
            return;
        }

        // 5. 执行移动
        if (!currentPath.isEmpty() && currentPathIndex < currentPath.size()) {
            executeMovement();
        } else if (isCalculating) {
            resetKeys(); // 等待计算时停止移动
        }
    }

    /**
     * 核心逻辑：生成路径
     * 现在变得非常简单：直接问 A* 要路径。
     * 如果 A* 算不到终点，它会给我们一条“目前为止离终点最近”的路径。
     */
    private static void generateNextSegment() {
        if (isCalculating || globalTarget == null) return;
        isCalculating = true;

        final BlockPos startPos = new BlockPos(mc.thePlayer);
        final BlockPos finalTarget = globalTarget;

        pathPool.execute(() -> {
            try {
                // 直接计算到全局终点，允许最多 MAX_CALC_NODES 个节点
                // cache 参数传入，用于在计算代价时加入热力图惩罚
                List<BlockPos> path = PathCalculator.compute(startPos, finalTarget, MAX_CALC_NODES, cache);

                mc.addScheduledTask(() -> {
                    if (isPathfinding) {
                        if (path != null && !path.isEmpty()) {
                            // 简单的平滑处理
                            smoothPath(path);
                            
                            // 更新路径
                            currentPath = new CopyOnWriteArrayList<>(path);
                            currentPathIndex = 0;
                            resetStuckTimers();
                            
                            // Debug: 告诉玩家我们是不是只算了一半
                            BlockPos pathEnd = path.get(path.size() - 1);
                            double distRemaining = Math.sqrt(pathEnd.distanceSq(finalTarget));
                            if (distRemaining > 5.0) {
                                // debug("计算分段路径... (本段终点距目标还有 " + (int)distRemaining + " 米)");
                            }
                        } else {
                            debugErr("无路可走！(可能被完全封闭)");
                            stop();
                        }
                    }
                    isCalculating = false;
                });

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false);
            }
        });
    }

    // --- 辅助方法 ---

    private static void smoothPath(List<BlockPos> path) {
        if (mc.thePlayer == null || path.isEmpty()) return;
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        // 移除已经经过的或者就在脚下的节点
        while (!path.isEmpty()) {
            BlockPos next = path.get(0);
            if (playerPos.squareDistanceTo(new Vec3(next.getX()+0.5, next.getY(), next.getZ()+0.5)) < 2.0) {
                path.remove(0);
            } else {
                break;
            }
        }
    }

    private static void executeMovement() {
        updatePathIndex();
        if (currentPathIndex >= currentPath.size()) return;

        BlockPos nextNode = currentPath.get(currentPathIndex);
        
        boolean inWater = mc.thePlayer.isInWater();
        boolean onLadder = isOnLadder();

        // 视角控制
        Vec3 lookTarget;
        double jitter = (rng.nextDouble() - 0.5) * 0.1;
        double targetX = nextNode.getX() + 0.5 + jitter;
        double targetZ = nextNode.getZ() + 0.5 + jitter;
        
        if (onLadder) {
            lookTarget = new Vec3(targetX, nextNode.getY() > mc.thePlayer.posY ? nextNode.getY() + 1 : nextNode.getY(), targetZ);
        } else {
            double yDiff = nextNode.getY() - mc.thePlayer.posY;
            if (yDiff > 0.5) {
                lookTarget = new Vec3(targetX, nextNode.getY() + 1.2, targetZ); // 看上面
            } else if (yDiff < -0.5) {
                lookTarget = new Vec3(targetX, nextNode.getY() - 0.5, targetZ); // 看脚下
            } else {
                lookTarget = new Vec3(targetX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), targetZ); // 看平视
            }
        }

        float[] rotations = RotationUtil.getRotations(lookTarget);
        // 更加平滑的转向
        float smoothFactor = 30f; 
        mc.thePlayer.rotationYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], smoothFactor);
        mc.thePlayer.rotationPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], smoothFactor);

        // 移动控制
        boolean jump = false;
        boolean forward = true;
        boolean sprint = true;

        if (onLadder) {
            sprint = false;
            if (Math.abs(nextNode.getY() - mc.thePlayer.posY) < 0.2) forward = false;
        } else if (inWater) {
            sprint = false;
            if (nextNode.getY() >= mc.thePlayer.posY) jump = true;
        } else {
            if (mc.thePlayer.onGround) {
                if (nextNode.getY() > mc.thePlayer.posY + 0.6) {
                    jump = true;
                } else if (nextNode.getY() == Math.floor(mc.thePlayer.posY)) {
                    // 跑酷检测
                    double dist = Math.hypot(nextNode.getX() + 0.5 - mc.thePlayer.posX, nextNode.getZ() + 0.5 - mc.thePlayer.posZ);
                    if (dist > 1.0) {
                         BlockPos belowNext = nextNode.down();
                         if (!PathCalculator.isSolid(belowNext) && !PathCalculator.isLadder(belowNext)) {
                             jump = true;
                             sprint = true;
                         }
                    }
                }
                
                // 撞墙防卡
                if (mc.thePlayer.isCollidedHorizontally && forward) {
                    collisionTimer++;
                    if (collisionTimer > 2) jump = true;
                } else {
                    collisionTimer = 0;
                }
            }
        }
        
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - rotations[0]));
        if (yawDiff > 30) sprint = false; // 转向太大不疾跑

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
        mc.thePlayer.setSprinting(sprint);
    }

    private static void updatePathIndex() {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        int bestIndex = currentPathIndex;
        double minDistance = Double.MAX_VALUE;

        // 向前搜索最近的节点
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
            if (hDist < 0.6 * 0.6) { // 稍微靠近一点就切下一个
                currentPathIndex++;
            }
        }
    }

    private static boolean checkStuck() {
        stuckTimer++;
        if (stuckTimer > 30) { // 1.5秒
            stuckTimer = 0;
            Vec3 currentPos = mc.thePlayer.getPositionVector();
            if (lastCheckPos != null) {
                double moved = currentPos.distanceTo(lastCheckPos);
                if (moved < 0.3) { // 移动很慢
                    debugErr("卡顿检测，尝试重新规划...");
                    return true;
                }
            }
            lastCheckPos = currentPos;
        }
        return false;
    }

    private static void resetStuckTimers() { stuckTimer = 0; collisionTimer = 0; lastCheckPos = null; }

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
        // 使用 Long 存储 BlockPos 的 Hash，节省内存
        private final ConcurrentHashMap<Long, Integer> densityMap = new ConcurrentHashMap<>();
        private final File cacheFile = new File(mc.mcDataDir, "ghost_density.dat");

        private long getDensityKey(BlockPos pos) { 
            // 降低分辨率：每 2x2x2 个方块共享一个热度值，减少内存占用并增加模糊性
            long x = pos.getX() >> 1; 
            long y = pos.getY() >> 1; 
            long z = pos.getZ() >> 1;
            return (x & 0x3FFFFF) | ((z & 0x3FFFFF) << 22) | ((y & 0xFF) << 44); 
        }

        public void load() { densityMap.clear(); } // 简化：每次重新开始时清空，防止之前的任务干扰
        public void save() { } // 简化：不需要持久化保存到硬盘

        public void incrementDensity(BlockPos pos) { 
            densityMap.merge(getDensityKey(pos), 1, Integer::sum); 
        }
        
        public double getCostPenalty(BlockPos pos) {
            // 这里的倍率决定了“厌恶旧路”的程度。
            // 2.0 表示走过一次的地方，代价相当于多走 2 格平路
            return densityMap.getOrDefault(getDensityKey(pos), 0) * 2.0; 
        }
    }

    // =======================================================
    //            PathCalculator (A* 核心重构)
    // =======================================================
    private static class PathCalculator {
        private static final double COST_WALK = 1.0;
        private static final double COST_JUMP = 2.0; // 跳跃比平走累
        private static final double COST_FALL = 1.0; 
        private static final double COST_LADDER = 1.5;

        /**
         * 带有 Best-So-Far 机制的 A* 算法
         */
        public static List<BlockPos> compute(BlockPos start, BlockPos end, int limit, PathCache cache) {
            PriorityQueue<Node> openSet = new PriorityQueue<>();
            Map<BlockPos, Node> allNodes = new HashMap<>();

            Node startNode = new Node(start, null, 0, getHeuristic(start, end));
            openSet.add(startNode);
            allNodes.put(start, startNode);

            // [核心逻辑] 记录离终点最近的节点 (Best So Far)
            Node bestNodeSoFar = startNode;
            double minHeuristic = startNode.hCost;

            int iterations = 0;

            while (!openSet.isEmpty() && iterations < limit) {
                iterations++;
                Node current = openSet.poll();

                // 检查是否更新“目前为止最好”的节点
                if (current.hCost < minHeuristic) {
                    minHeuristic = current.hCost;
                    bestNodeSoFar = current;
                }

                // 到达终点 (距离小于 1.5 格)
                if (current.pos.distanceSq(end) < 2.25) {
                    return reconstructPath(current);
                }

                // 扩展邻居
                for (Node neighborProto : getNeighbors(current.pos)) {
                    // [核心逻辑] 加入热力图惩罚
                    // 如果这个邻居是我们经常去的地方，costPenalty 会很大，A* 就会尽量避免选它
                    double penalty = (cache != null) ? cache.getCostPenalty(neighborProto.pos) : 0;
                    
                    double newGCost = current.gCost + neighborProto.gCost + penalty;

                    Node existingNode = allNodes.get(neighborProto.pos);

                    if (existingNode == null || newGCost < existingNode.gCost) {
                        double hCost = getHeuristic(neighborProto.pos, end);
                        if (existingNode == null) {
                            Node newNode = new Node(neighborProto.pos, current, newGCost, hCost);
                            allNodes.put(neighborProto.pos, newNode);
                            openSet.add(newNode);
                        } else {
                            // 更新已有节点
                            openSet.remove(existingNode);
                            existingNode.parent = current;
                            existingNode.gCost = newGCost;
                            existingNode.hCost = hCost;
                            existingNode.fCost = newGCost + hCost;
                            openSet.add(existingNode);
                        }
                    }
                }
            }

            // 如果循环结束还没到终点（步数耗尽），不要返回 null！
            // 而是返回 bestNodeSoFar 的路径。这能让我们走到离终点最近的地方。
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
            // 欧几里得距离作为启发值
            return Math.sqrt(pos.distanceSq(target));
        }

        private static List<Node> getNeighbors(BlockPos pos) {
            List<Node> moves = new ArrayList<>();

            // 1. 水平移动 & 跳跃 & 下落
            for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                BlockPos offset = pos.offset(dir);

                if (isWalkable(offset)) {
                    moves.add(new Node(offset, null, COST_WALK, 0));
                }
                else if (isWalkable(offset.up()) && canGoThrough(pos.up(2))) {
                    moves.add(new Node(offset.up(), null, COST_JUMP, 0)); // 跳跃
                }
                else {
                    // 下落检测 (最多3格)
                    for (int i = 1; i <= 3; i++) {
                        BlockPos down = offset.down(i);
                        if (isWalkable(down)) {
                            // 检查垂直通道是否通畅
                            boolean blocked = false;
                            for(int j=0; j<i; j++) if(!canGoThrough(offset.down(j))) blocked = true;
                            
                            if (!blocked) {
                                moves.add(new Node(down, null, COST_FALL, 0));
                                break;
                            }
                        }
                    }
                }
            }
            
            // 2. 对角线移动 (防止卡墙角)
            // 只有当两个相邻的直线方向都通畅时，才允许走对角线
            // 例如：去 (1, 1)，必须 (1, 0) 和 (0, 1) 都是通的
            for (int x = -1; x <= 1; x += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    BlockPos diag = pos.add(x, 0, z);
                    if (isWalkable(diag)) {
                        if (canGoThrough(pos.add(x, 0, 0)) && canGoThrough(pos.add(0, 0, z))) {
                             moves.add(new Node(diag, null, COST_WALK * 1.414, 0));
                        }
                    }
                }
            }

            // 3. 梯子逻辑
            if (isLadder(pos)) {
                if (isLadder(pos.up()) || isWalkable(pos.up())) moves.add(new Node(pos.up(), null, COST_LADDER, 0));
                if (isLadder(pos.down()) || isWalkable(pos.down())) moves.add(new Node(pos.down(), null, COST_LADDER, 0));
            }

            return moves;
        }

        public static boolean isWalkable(BlockPos pos) { 
            return isSolid(pos.down()) && canGoThrough(pos) && canGoThrough(pos.up()) && isSafe(pos);
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
            // 处理门和栅栏门
            if (block instanceof BlockFenceGate && state.getValue(BlockFenceGate.OPEN)) return true;
            if (block instanceof BlockDoor && state.getValue(BlockDoor.OPEN)) return true;
            
            return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null 
                || block instanceof BlockAir || block instanceof BlockLiquid || block instanceof BlockLadder || block instanceof BlockVine || !block.getMaterial().isSolid();
        }
        
        public static boolean isLadder(BlockPos pos) { 
            Block block = mc.theWorld.getBlockState(pos).getBlock(); 
            return block instanceof BlockLadder || block instanceof BlockVine; 
        }

        public static boolean isSafe(BlockPos pos) { 
            Block in = mc.theWorld.getBlockState(pos).getBlock();
            Block down = mc.theWorld.getBlockState(pos.down()).getBlock();
            
            // 检查身体位置：不能是液体(岩浆/水)或火
            // 如果你想允许在浅水里走，可以把 instanceof BlockLiquid 去掉，
            // 但 A* 算法处理游泳比较复杂，建议先判定为不安全
            if (in instanceof BlockLiquid || in instanceof BlockFire) return false; 
            
            // 检查脚下位置：不能是仙人掌
            // 1.8.9 没有 BlockMagma (岩浆块)，所以删除了那个检查
            if (down instanceof BlockCactus) return false; 
            
            return true;
        }

        private static class Node implements Comparable<Node> {
            BlockPos pos;
            Node parent;
            double gCost; // 真实代价 + 热力图惩罚
            double hCost; // 离终点的距离
            double fCost; // 总和

            public Node(BlockPos p, Node pa, double g, double h) { 
                pos = p; parent = pa; gCost = g; hCost = h; fCost = g + h; 
            }
            
            @Override public int compareTo(Node o) { 
                int cmp = Double.compare(this.fCost, o.fCost); 
                return (cmp == 0) ? Double.compare(this.hCost, o.hCost) : cmp; 
            }
        }
    }
}