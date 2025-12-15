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
 * 
 * 修改日志：
 * 1. 修复栅栏门/围墙高度判定。
 * 2. 移除错误的粘液块原地起跳逻辑。
 * 3. 新增粘液块与水体的“高空安全坠落”判定 (Safe Fall)。
 * 4. 优化水体游泳逻辑。
 * 5. 修复 IBlockState 编译错误 (getMaterial -> getBlock().getMaterial())。
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
    private static final int MAX_CALC_NODES = 5000; 
    
    // 卡死检测
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
        debug(">>> 全局目标设定: " + target);
        generateNextSegment(); 
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

        cache.incrementDensity(new BlockPos(mc.thePlayer));

        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 2.0) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        if (!isCalculating && (currentPath.isEmpty() || currentPathIndex >= currentPath.size() - 5)) {
            if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) > 2.0) {
                generateNextSegment();
            }
        }

        if (checkStuck()) {
            BlockPos head = new BlockPos(mc.thePlayer).offset(mc.thePlayer.getHorizontalFacing());
            cache.incrementDensity(head); 
            cache.incrementDensity(new BlockPos(mc.thePlayer));
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
                            smoothPath(path);
                            currentPath = new CopyOnWriteArrayList<>(path);
                            currentPathIndex = 0;
                            resetStuckTimers();
                        } else {
                            debugErr("无路可走！");
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
        
        if (onLadder || inWater) {
             lookTarget = new Vec3(targetX, nextNode.getY() > mc.thePlayer.posY ? nextNode.getY() + 1 : nextNode.getY(), targetZ);
        } else {
            double yDiff = nextNode.getY() - mc.thePlayer.posY;
            if (yDiff > 0.5) {
                lookTarget = new Vec3(targetX, nextNode.getY() + 1.2, targetZ); 
            } else if (yDiff < -0.5) {
                lookTarget = new Vec3(targetX, nextNode.getY() - 0.5, targetZ);
            } else {
                lookTarget = new Vec3(targetX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), targetZ); 
            }
        }

        float[] rotations = RotationUtil.getRotations(lookTarget);
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
            // 水中：如果目标在上方，按跳跃上浮
            if (nextNode.getY() > mc.thePlayer.posY || (nextNode.getY() == Math.floor(mc.thePlayer.posY) && mc.thePlayer.posY % 1 > 0.8)) {
                jump = true;
            }
        } else {
            if (mc.thePlayer.onGround) {
                if (nextNode.getY() > mc.thePlayer.posY + 0.6) {
                    jump = true;
                } else if (nextNode.getY() == Math.floor(mc.thePlayer.posY)) {
                    double dist = Math.hypot(nextNode.getX() + 0.5 - mc.thePlayer.posX, nextNode.getZ() + 0.5 - mc.thePlayer.posZ);
                    if (dist > 1.0) {
                         BlockPos belowNext = nextNode.down();
                         if (!PathCalculator.isSolid(belowNext) && !PathCalculator.isLadder(belowNext) && !PathCalculator.isWater(belowNext)) {
                             jump = true;
                             sprint = true;
                         }
                    }
                }
                
                // 落地缓冲：如果落在粘液块上，可以考虑按Shift停止弹跳，这里简化为继续走
                if (mc.theWorld.getBlockState(new BlockPos(mc.thePlayer).down()).getBlock() == Blocks.slime_block) {
                    sprint = false; // 粘液块上走路滑，不疾跑
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
        if (yawDiff > 30) sprint = false; 

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
        mc.thePlayer.setSprinting(sprint);
    }

    private static void updatePathIndex() {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        int bestIndex = currentPathIndex;
        double minDistance = Double.MAX_VALUE;

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
            if (hDist < 0.6 * 0.6) { 
                currentPathIndex++;
            }
        }
    }

    private static boolean checkStuck() {
        stuckTimer++;
        if (stuckTimer > 30) { 
            stuckTimer = 0;
            Vec3 currentPos = mc.thePlayer.getPositionVector();
            if (lastCheckPos != null) {
                double moved = currentPos.distanceTo(lastCheckPos);
                if (moved < 0.3) { 
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
        private final ConcurrentHashMap<Long, Integer> densityMap = new ConcurrentHashMap<>();

        private long getDensityKey(BlockPos pos) { 
            long x = pos.getX() >> 1; 
            long y = pos.getY() >> 1; 
            long z = pos.getZ() >> 1;
            return (x & 0x3FFFFF) | ((z & 0x3FFFFF) << 22) | ((y & 0xFF) << 44); 
        }

        public void load() { densityMap.clear(); } 
        public void save() { } 

        public void incrementDensity(BlockPos pos) { 
            densityMap.merge(getDensityKey(pos), 1, Integer::sum); 
        }
        
        public double getCostPenalty(BlockPos pos) {
            return densityMap.getOrDefault(getDensityKey(pos), 0) * 2.0; 
        }
    }

    // =======================================================
    //            PathCalculator (A* 核心重构)
    // =======================================================
    private static class PathCalculator {
        private static final double COST_WALK = 1.0;
        private static final double COST_JUMP = 2.0; 
        private static final double COST_FALL = 1.0; 
        private static final double COST_LADDER = 1.5;
        private static final double COST_SWIM = 2.0;

        public static List<BlockPos> compute(BlockPos start, BlockPos end, int limit, PathCache cache) {
            PriorityQueue<Node> openSet = new PriorityQueue<>();
            Map<BlockPos, Node> allNodes = new HashMap<>();

            Node startNode = new Node(start, null, 0, getHeuristic(start, end));
            openSet.add(startNode);
            allNodes.put(start, startNode);

            Node bestNodeSoFar = startNode;
            double minHeuristic = startNode.hCost;

            int iterations = 0;

            while (!openSet.isEmpty() && iterations < limit) {
                iterations++;
                Node current = openSet.poll();

                if (current.hCost < minHeuristic) {
                    minHeuristic = current.hCost;
                    bestNodeSoFar = current;
                }

                if (current.pos.distanceSq(end) < 2.25) {
                    return reconstructPath(current);
                }

                for (Node neighborProto : getNeighbors(current.pos)) {
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

        private static List<Node> getNeighbors(BlockPos pos) {
            List<Node> moves = new ArrayList<>();
            IBlockState state = mc.theWorld.getBlockState(pos);
            // 修复点1：state.getMaterial() -> state.getBlock().getMaterial()
            boolean inWater = state.getBlock().getMaterial() == Material.water;

            // --- 0. 水中逻辑 (Swim) ---
            if (inWater) {
                BlockPos up = pos.up();
                if (canGoThrough(up)) {
                    moves.add(new Node(up, null, COST_SWIM, 0)); // 向上游
                }
                for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                    BlockPos offset = pos.offset(dir);
                    // 水中只要目标格不是实心即可
                    if (canGoThrough(offset)) {
                         moves.add(new Node(offset, null, COST_SWIM, 0));
                    }
                }
                return moves;
            }

            // --- 1. 陆地移动 (包含高空坠落判定) ---
            for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                BlockPos offset = pos.offset(dir);

                // 平走
                if (isWalkable(offset)) {
                    moves.add(new Node(offset, null, COST_WALK, 0));
                }
                // 跳跃 (1格)
                else if (isWalkable(offset.up()) && canGoThrough(pos.up(2))) {
                    moves.add(new Node(offset.up(), null, COST_JUMP, 0)); 
                }
                // 下落 (普通下落3格 + 粘液块/水 高空下落)
                else {
                    // 向下搜索最多 20 格
                    for (int i = 1; i <= 20; i++) {
                        BlockPos target = offset.down(i);
                        
                        // 检查垂直通道是否通畅（头顶不能撞到，脚下不能踩到空气墙）
                        // 这里的检查是为了确保从 pos 走到 offset 然后掉下去的过程中没有阻挡
                        boolean airClear = true;
                        for(int j=0; j<i; j++) {
                            // offset.down(j) 必须是可穿过的
                             if (!canGoThrough(offset.down(j))) { airClear = false; break; }
                        }
                        if (!airClear) break; // 通道被堵死，停止向下搜索

                        if (isWalkable(target)) {
                            // 找到落脚点了
                            if (i <= 3) {
                                // 正常下落
                                moves.add(new Node(target, null, COST_FALL * i, 0));
                            } else {
                                // 高空下落 (>3格)：必须检查落点材质
                                // isWalkable 已经确认了 target.down() 是实心的，现在检查它是什么
                                if (isSafeHighFall(target)) {
                                     moves.add(new Node(target, null, COST_FALL * i, 0));
                                }
                            }
                            break; // 找到地面后就不用继续往下看了
                        }
                    }
                }
            }
            
            // --- 2. 对角线移动 ---
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

            // --- 3. 梯子逻辑 ---
            if (isLadder(pos)) {
                if (isLadder(pos.up()) || isWalkable(pos.up())) moves.add(new Node(pos.up(), null, COST_LADDER, 0));
                if (isLadder(pos.down()) || isWalkable(pos.down())) moves.add(new Node(pos.down(), null, COST_LADDER, 0));
            }

            return moves;
        }

        public static boolean isWalkable(BlockPos pos) { 
            // 水中特判：如果已经在水里，视为可行走（游泳）
            if (isWater(pos)) return canGoThrough(pos);

            // 1. 基础地面实体检查
            if (!isSolid(pos.down())) return false;
            
            // 2. 身体空间检查
            if (!canGoThrough(pos) || !canGoThrough(pos.up())) return false;
            
            // 3. 栅栏/墙的高度修正 (修复卡门问题)
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
            // 修复点2：getBlockState(pos).getMaterial() -> .getBlock().getMaterial()
            return mc.theWorld.getBlockState(pos).getBlock().getMaterial() == Material.water;
        }
        
        public static boolean isLadder(BlockPos pos) { 
            Block block = mc.theWorld.getBlockState(pos).getBlock(); 
            return block instanceof BlockLadder || block instanceof BlockVine; 
        }

        // 普通行走安全检查
        public static boolean isSafe(BlockPos pos) { 
            Block in = mc.theWorld.getBlockState(pos).getBlock();
            // 允许水，拒绝岩浆
            if (in.getMaterial() == Material.lava || in instanceof BlockFire) return false; 
            
            // 检查脚下
            Block down = mc.theWorld.getBlockState(pos.down()).getBlock();
            if (down instanceof BlockCactus) return false; 
            
            return true;
        }

        // 高空下落安全检查
        private static boolean isSafeHighFall(BlockPos pos) {
            if (isWater(pos)) return true; // 落在水里安全
            Block blockBelow = mc.theWorld.getBlockState(pos.down()).getBlock();
            return blockBelow == Blocks.slime_block || blockBelow == Blocks.hay_block; // 粘液块或干草块
        }

        private static class Node implements Comparable<Node> {
            BlockPos pos;
            Node parent;
            double gCost; 
            double hCost; 
            double fCost; 

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