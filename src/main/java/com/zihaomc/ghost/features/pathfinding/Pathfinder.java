package com.zihaomc.ghost.features.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.block.state.IBlockState;

import java.util.*;

/**
 * 优化后的 A* 寻路算法。
 * - 支持 8 方向移动 (对角线)。
 * - 包含路径平滑处理 (去除不必要的拐点，允许任意角度行走)。
 */
public class Pathfinder {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double DIAGONAL_COST = 1.414; // 根号2，对角线移动的代价

    /**
     * 计算路径的主入口
     */
    public static List<BlockPos> computePath(BlockPos start, BlockPos end, int maxIterations) {
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

            // 到达终点 (距离小于1.5格视为到达)
            if (current.pos.distanceSq(end) < 1.5) {
                List<BlockPos> rawPath = retracePath(current);
                return smoothPath(rawPath); // 返回平滑处理后的路径
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
                    // 使用欧几里得距离作为启发函数，鼓励走直线
                    neighborNode.hCost = Math.sqrt(neighborPos.distanceSq(end));
                    
                    if (openSet.contains(neighborNode)) {
                        openSet.remove(neighborNode); // 移除旧的以更新排序
                    }
                    openSet.add(neighborNode);
                    allNodes.put(neighborPos, neighborNode);
                }
            }
        }

        return new ArrayList<>(); // 未找到路径
    }

    /**
     * 路径平滑算法 (String Pulling)
     * 尝试直接连接相隔较远的节点，如果中间没有障碍物，则跳过中间节点。
     */
    private static List<BlockPos> smoothPath(List<BlockPos> path) {
        if (path.size() <= 2) return path;

        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(path.get(0));
        int currentIdx = 0;

        while (currentIdx < path.size() - 1) {
            int nextIdx = currentIdx + 1;
            // 从最远处开始尝试连接，贪婪算法
            for (int i = path.size() - 1; i > currentIdx + 1; i--) {
                // 只有在同一高度才进行激进的平滑，防止在楼梯上卡住
                if (path.get(currentIdx).getY() == path.get(i).getY() && 
                    canWalkDirectly(path.get(currentIdx), path.get(i))) {
                    nextIdx = i;
                    break;
                }
            }
            smoothed.add(path.get(nextIdx));
            currentIdx = nextIdx;
        }
        return smoothed;
    }

    /**
     * 检查两点之间是否可以直接行走 (无墙壁且地板连续)
     */
    private static boolean canWalkDirectly(BlockPos start, BlockPos end) {
        Vec3 startVec = new Vec3(start.getX() + 0.5, start.getY() + 1.0, start.getZ() + 0.5);
        Vec3 endVec = new Vec3(end.getX() + 0.5, end.getY() + 1.0, end.getZ() + 0.5);

        // 1. 身体碰撞检查 (防止穿墙)
        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startVec, endVec, false, true, false);
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }

        // 2. 地面检查 (防止跨越虚空/坑洞)
        double dist = Math.sqrt(start.distanceSq(end));
        int steps = (int) Math.ceil(dist); 
        for (int i = 1; i < steps; i++) {
            double progress = i / dist;
            double x = start.getX() + (end.getX() - start.getX()) * progress;
            double y = start.getY(); // 假设平地
            double z = start.getZ() + (end.getZ() - start.getZ()) * progress;
            
            // 检查路径上每一个点的脚下是否有方块
            if (!isWalkable(new BlockPos(x, y, z))) {
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

        // 1. 获取四个基准方向的可行性 (支持上下台阶)
        boolean wn = addWithVerticalChecks(neighbors, n, 1.0);
        boolean ws = addWithVerticalChecks(neighbors, s, 1.0);
        boolean we = addWithVerticalChecks(neighbors, e, 1.0);
        boolean ww = addWithVerticalChecks(neighbors, w, 1.0);

        // 2. 获取对角线方向 (仅限平地，且两侧必须无障碍防止卡墙)
        // 比如向 东北(NE) 走，必须 北(N) 和 东(E) 都是可走的，否则会被墙角卡住
        if (wn && we && isWalkable(n.east())) neighbors.add(new Neighbor(n.east(), DIAGONAL_COST));
        if (wn && ww && isWalkable(n.west())) neighbors.add(new Neighbor(n.west(), DIAGONAL_COST));
        if (ws && we && isWalkable(s.east())) neighbors.add(new Neighbor(s.east(), DIAGONAL_COST));
        if (ws && ww && isWalkable(s.west())) neighbors.add(new Neighbor(s.west(), DIAGONAL_COST));

        return neighbors;
    }

    // 添加节点，并尝试处理 1 格高差 (自动跳跃/下落)
    // 返回值表示该基准方向是否可以通行 (用于对角线判断)
    private static boolean addWithVerticalChecks(List<Neighbor> neighbors, BlockPos target, double cost) {
        if (isWalkable(target)) {
            neighbors.add(new Neighbor(target, cost));
            return true;
        } else if (isWalkable(target.up()) && isPassable(target) && isPassable(target.up(2))) { // 上台阶
            neighbors.add(new Neighbor(target.up(), cost));
            return false; // 对角线通常不处理跳跃
        } else if (isWalkable(target.down()) && isPassable(target.down(1)) && isPassable(target)) { // 下台阶
            neighbors.add(new Neighbor(target.down(), cost));
            return true;
        }
        return false;
    }

    // 检查某个位置是否可以站立
    private static boolean isWalkable(BlockPos pos) {
        // 1. 脚下必须是实心方块 (防止掉虚空)
        if (!isSolid(pos.down())) return false;
        // 2. 身体位置必须无碰撞体积 (不是墙)
        if (!isPassable(pos)) return false;
        // 3. 头部位置必须无碰撞体积
        if (!isPassable(pos.up())) return false;
        // 4. 危险方块检查 (岩浆、蜘蛛网等)
        if (isDangerous(pos) || isDangerous(pos.down())) return false;
        return true;
    }

    // 检查方块是否允许穿过 (没有碰撞箱)
    private static boolean isPassable(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        // getCollisionBoundingBox 返回 null 表示可以穿过 (如空气、草、花)
        return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null;
    }

    // 检查方块是否适合作为地面
    private static boolean isSolid(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.air) return false;
        // Material.isSolid() 对于水和岩浆返回 false，对石头、台阶返回 true
        return block.getMaterial().isSolid();
    }

    private static boolean isDangerous(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.lava || block == Blocks.flowing_lava || 
               block == Blocks.cactus || block == Blocks.fire || block == Blocks.web;
    }

    private static class Node implements Comparable<Node> {
        BlockPos pos;
        Node parent;
        double gCost; // 离起点的代价
        double hCost; // 离终点的估算代价

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