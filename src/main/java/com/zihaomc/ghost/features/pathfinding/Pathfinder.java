package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.block.*;
import net.minecraft.block.material.Material; // 修复了这里的导入
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.block.state.IBlockState;

import java.util.*;

public class Pathfinder {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double DIAGONAL_COST = 1.414;
    private static final boolean DEBUG = true;

    public static List<BlockPos> computePath(BlockPos start, BlockPos end, int maxIterations) {
        if (DEBUG) LogUtil.info("[Pathfinder] 正在计算路径: " + start + " -> " + end);
        
        // 预检查：如果终点本身不可行走（比如在墙里），尝试找终点附近的空位
        if (!isPassable(end) || !isPassable(end.up())) {
            if (DEBUG) LogUtil.warn("[Pathfinder] 终点被阻挡，尝试修正...");
            if (isPassable(end.up())) end = end.up(); // 尝试向上修正
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, Node> allNodes = new HashMap<>();

        Node startNode = new Node(start, null, 0, start.distanceSq(end));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        int iterations = 0;

        while (!openSet.isEmpty() && iterations < maxIterations) {
            iterations++;
            Node current = openSet.poll();

            // 稍微放宽到达判定 (1.5 -> 2.0)
            if (current.pos.distanceSq(end) < 2.0) {
                List<BlockPos> rawPath = retracePath(current);
                if (DEBUG) LogUtil.info("[Pathfinder] 路径计算成功，原始节点数: " + rawPath.size());
                return smoothPath(rawPath);
            }

            closedSet.add(current.pos);

            for (Neighbor neighbor : getNeighbors(current.pos)) {
                BlockPos neighborPos = neighbor.pos;
                if (closedSet.contains(neighborPos)) continue;

                double newGCost = current.gCost + neighbor.cost;
                Node neighborNode = allNodes.getOrDefault(neighborPos, new Node(neighborPos, null, Double.MAX_VALUE, 0));

                if (newGCost < neighborNode.gCost) {
                    neighborNode.parent = current;
                    neighborNode.gCost = newGCost;
                    neighborNode.hCost = Math.sqrt(neighborPos.distanceSq(end));
                    
                    if (openSet.contains(neighborNode)) {
                        openSet.remove(neighborNode);
                    }
                    openSet.add(neighborNode);
                    allNodes.put(neighborPos, neighborNode);
                }
            }
        }

        if (DEBUG) LogUtil.warn("[Pathfinder] 寻路失败：无法找到路径 (迭代 " + iterations + " 次)");
        return new ArrayList<>();
    }

    private static List<BlockPos> smoothPath(List<BlockPos> path) {
        if (path.size() <= 2) return path;

        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(path.get(0));
        int currentIdx = 0;

        while (currentIdx < path.size() - 1) {
            int nextIdx = currentIdx + 1;
            // 贪婪搜索：尽可能连最远的点
            for (int i = path.size() - 1; i > currentIdx + 1; i--) {
                BlockPos p1 = path.get(currentIdx);
                BlockPos p2 = path.get(i);
                
                // 仅在高度差为0时尝试激进平滑
                if (p1.getY() == p2.getY()) {
                    if (checkCollisionPath(p1, p2)) {
                        nextIdx = i;
                        break;
                    }
                }
            }
            smoothed.add(path.get(nextIdx));
            currentIdx = nextIdx;
        }
        return smoothed;
    }

    /**
     * 物理碰撞箱扫描 (Box Sweep)
     * 检测从 start 滑动到 end 是否会撞到任何东西
     */
    private static boolean checkCollisionPath(BlockPos start, BlockPos end) {
        double startX = start.getX() + 0.5;
        double startY = start.getY();
        double startZ = start.getZ() + 0.5;
        
        double endX = end.getX() + 0.5;
        double endY = end.getY();
        double endZ = end.getZ() + 0.5;

        double dist = Math.sqrt(start.distanceSq(end));
        // 步长设为 0.4，确保不漏掉方块
        int steps = (int) Math.ceil(dist / 0.4); 
        
        // [关键调整] 半径设为 0.2
        // 玩家实际半径 0.3，设为 0.2 可以允许轻微擦边，避免在狭窄斜道被误判为卡住
        double r = 0.2; 

        for (int i = 1; i < steps; i++) {
            double progress = (double) i / steps;
            double x = startX + (endX - startX) * progress;
            double y = startY + (endY - startY) * progress;
            double z = startZ + (endZ - startZ) * progress;

            // 1. 碰撞检测
            AxisAlignedBB entityBB = new AxisAlignedBB(x - r, y, z - r, x + r, y + 1.8, z + r);
            if (!mc.theWorld.getCollidingBoundingBoxes(null, entityBB).isEmpty()) {
                return false; // 路径上有障碍
            }
            
            // 2. 坠落检测
            // 检查每一步的脚下是否有方块，防止平滑后走出悬崖
            BlockPos groundPos = new BlockPos(x, y - 0.5, z);
            if (!isSafeFloor(groundPos)) {
                return false; 
            }
        }
        
        return true;
    }

    private static List<BlockPos> retracePath(Node endNode) {
        List<BlockPos> path = new ArrayList<>();
        Node current = endNode;
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static class Neighbor {
        BlockPos pos;
        double cost;
        public Neighbor(BlockPos pos, double cost) { this.pos = pos; this.cost = cost; }
    }

    private static List<Neighbor> getNeighbors(BlockPos pos) {
        List<Neighbor> neighbors = new ArrayList<>();
        
        BlockPos n = pos.north();
        BlockPos s = pos.south();
        BlockPos e = pos.east();
        BlockPos w = pos.west();

        boolean wn = addWithVerticalChecks(neighbors, n, 1.0);
        boolean ws = addWithVerticalChecks(neighbors, s, 1.0);
        boolean we = addWithVerticalChecks(neighbors, e, 1.0);
        boolean ww = addWithVerticalChecks(neighbors, w, 1.0);

        // 对角线检测
        // 稍微放宽条件：只要对角线目标点本身是安全的，且正交方向至少有一个没完全堵死，就尝试加入
        // 实际上 checkCollisionPath 会负责最后的安全把关，这里可以稍微宽容一点让 A* 能找到路
        if (wn && we) checkDiagonal(neighbors, pos.north().east());
        if (wn && ww) checkDiagonal(neighbors, pos.north().west());
        if (ws && we) checkDiagonal(neighbors, pos.south().east());
        if (ws && ww) checkDiagonal(neighbors, pos.south().west());

        return neighbors;
    }

    private static boolean addWithVerticalChecks(List<Neighbor> neighbors, BlockPos target, double cost) {
        if (isWalkable(target)) {
            neighbors.add(new Neighbor(target, cost));
            return true;
        } 
        // 上台阶
        else if (isSafeFloor(target) && isPassable(target.up()) && isPassable(target.up(2))) {
            neighbors.add(new Neighbor(target.up(), cost));
            return false; // 上台阶算作阻挡，防止对角线穿模
        } 
        // 下台阶
        else if (isPassable(target) && isPassable(target.down()) && isWalkable(target.down())) {
            neighbors.add(new Neighbor(target.down(), cost));
            return true;
        }
        return false;
    }

    private static void checkDiagonal(List<Neighbor> list, BlockPos target) {
        if (isWalkable(target)) {
            list.add(new Neighbor(target, DIAGONAL_COST));
        }
    }

    // --- 核心判定逻辑 (修复版) ---

    // 一个位置是否可以站人：脚下有地，身体和头不撞墙，不危险
    private static boolean isWalkable(BlockPos pos) {
        return isSafeFloor(pos.down()) && isPassable(pos) && isPassable(pos.up()) && !isDangerous(pos) && !isDangerous(pos.down());
    }

    // 检查方块是否可以穿过 (例如空气、草、花)
    private static boolean isPassable(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        // 门、栅栏门如果是开着的，理论上可以走，但为了简化，这里只判断碰撞箱
        // getCollisionBoundingBox 返回 null 表示无碰撞体积
        return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null;
    }

    // 检查方块是否可以作为地面 (防止地毯、半砖被误判为虚空)
    private static boolean isSafeFloor(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        
        // 1. 绝对不可行
        if (block == Blocks.air) return false;
        if (isDangerous(pos)) return false;

        // 2. 特殊白名单 (虽然材质属性可能奇怪，但确实能踩)
        if (block instanceof BlockCarpet || block instanceof BlockSnow || block instanceof BlockLilyPad) return true;
        if (block instanceof BlockSlab || block instanceof BlockStairs) return true;
        
        // 3. 通用检测
        // 如果方块是完整的立方体，或者是顶部实心的(如倒置楼梯)，就可以踩
        if (block.isFullCube() || mc.theWorld.doesBlockHaveSolidTopSurface(mc.theWorld, pos)) return true;
        
        // 4. 材质检测 (保底)
        Material mat = block.getMaterial();
        return mat.isSolid() && mat != Material.cactus && mat != Material.lava;
    }

    private static boolean isDangerous(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.lava || block == Blocks.flowing_lava || 
               block == Blocks.cactus || block == Blocks.fire || block == Blocks.web;
    }

    private static class Node implements Comparable<Node> {
        BlockPos pos;
        Node parent;
        double gCost;
        double hCost;

        public Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
        }

        public double getFCost() { return gCost + hCost; }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.getFCost(), other.getFCost());
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Node) return pos.equals(((Node) obj).pos);
            return false;
        }
    }
}