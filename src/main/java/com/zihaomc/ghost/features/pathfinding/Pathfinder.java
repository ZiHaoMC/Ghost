package com.zihaomc.ghost.features.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.block.state.IBlockState;

import java.util.*;

/**
 * 修复版 A* 寻路算法。
 * - 修复了撞墙问题：使用 RayTrace 进行精确的墙壁检测。
 * - 修复了楼梯问题：增加了专门的垂直移动判断逻辑。
 */
public class Pathfinder {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double DIAGONAL_COST = 1.414;

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

            if (current.pos.distanceSq(end) < 1.5) {
                List<BlockPos> rawPath = retracePath(current);
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
                    neighborNode.hCost = Math.sqrt(neighborPos.distanceSq(end)); // 欧几里得距离

                    if (openSet.contains(neighborNode)) {
                        openSet.remove(neighborNode);
                    }
                    openSet.add(neighborNode);
                    allNodes.put(neighborPos, neighborNode);
                }
            }
        }

        return new ArrayList<>();
    }

    /**
     * 路径平滑：使用光线追踪检测是否可以走直线。
     * 这比之前的点采样更精准，不会试图穿过墙角。
     */
    private static List<BlockPos> smoothPath(List<BlockPos> path) {
        if (path.size() <= 2) return path;

        List<BlockPos> smoothed = new ArrayList<>();
        smoothed.add(path.get(0));
        int currentIdx = 0;

        while (currentIdx < path.size() - 1) {
            int nextIdx = currentIdx + 1;
            // 贪婪策略：寻找最远的可见节点
            for (int i = path.size() - 1; i > currentIdx + 1; i--) {
                // 仅在高度差不大时尝试平滑，避免跳崖
                if (Math.abs(path.get(currentIdx).getY() - path.get(i).getY()) <= 1 && 
                    canSeeDirectly(path.get(currentIdx), path.get(i))) {
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
     * 使用 RayTrace 检查两点之间是否有方块阻挡。
     * 这是防止“撞墙”的关键修复。
     */
    private static boolean canSeeDirectly(BlockPos start, BlockPos end) {
        // 从起点中心+眼高，看向终点中心+眼高
        Vec3 startVec = new Vec3(start.getX() + 0.5, start.getY() + 1.0, start.getZ() + 0.5);
        Vec3 endVec = new Vec3(end.getX() + 0.5, end.getY() + 1.0, end.getZ() + 0.5);

        // false, true, false -> 不忽略流体，忽略无碰撞箱方块(草)，不返回未碰撞方块
        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startVec, endVec, false, true, false);
        
        // 如果 RayTrace 击中了方块，说明有墙
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        
        // 额外检查：确保终点脚下是实心的 (防止平滑路径导致掉进坑里)
        return isSolid(end.down());
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

    /**
     * 获取邻居节点，包含核心的移动逻辑
     */
    private static List<Neighbor> getNeighbors(BlockPos pos) {
        List<Neighbor> neighbors = new ArrayList<>();
        
        BlockPos[] cardinals = {pos.north(), pos.south(), pos.east(), pos.west()};
        boolean[] walkable = new boolean[4]; // 记录四个方向是否可行，用于对角线判断

        // 1. 处理四个正方向 (前后左右)
        for (int i = 0; i < 4; i++) {
            BlockPos target = cardinals[i];
            
            // 情况 A: 平地移动 (目标格子是空气，脚下是实心)
            if (isPassable(target) && isSolid(target.down()) && isSafeHead(target)) {
                neighbors.add(new Neighbor(target, 1.0));
                walkable[i] = true;
            } 
            // 情况 B: 上台阶/楼梯 (目标格子是实心，但目标上方是空气)
            // 修复点：这里检测 target 是实心，但 target.up 是空的
            else if (isSolid(target) && isPassable(target.up()) && isSafeHead(target.up())) {
                neighbors.add(new Neighbor(target.up(), 1.0)); // 实际上移动到了上方一格
                // 上台阶不算“平地可通行”，所以 walkable[i] 保持 false (防止对角线穿模)
            }
            // 情况 C: 下台阶 (目标格子是空气，目标脚下是空气，但再下面是实心)
            else if (isPassable(target) && isPassable(target.down()) && isSolid(target.down(2)) && isSafeHead(target.down())) {
                neighbors.add(new Neighbor(target.down(), 1.0));
                walkable[i] = true;
            }
        }

        // 2. 处理对角线 (仅限平地)
        // 索引对应: 0=N, 1=S, 2=E, 3=W
        // NE(0,2), NW(0,3), SE(1,2), SW(1,3)
        checkDiagonal(neighbors, walkable[0], walkable[2], pos.north().east());
        checkDiagonal(neighbors, walkable[0], walkable[3], pos.north().west());
        checkDiagonal(neighbors, walkable[1], walkable[2], pos.south().east());
        checkDiagonal(neighbors, walkable[1], walkable[3], pos.south().west());

        return neighbors;
    }

    private static void checkDiagonal(List<Neighbor> list, boolean b1, boolean b2, BlockPos target) {
        // 只有当两个正方向都可通行时，才允许走对角线，防止切角穿墙
        if (b1 && b2 && isPassable(target) && isSolid(target.down()) && isSafeHead(target)) {
            list.add(new Neighbor(target, DIAGONAL_COST));
        }
    }

    // --- 核心检测逻辑 ---

    /**
     * 检查某个位置是否“空旷”，允许玩家身体进入
     */
    private static boolean isPassable(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        // 危险方块检查
        if (block == Blocks.lava || block == Blocks.flowing_lava || block == Blocks.fire || block == Blocks.cactus) return false;
        
        // 关键修复：使用 getCollisionBoundingBox
        // 如果返回 null，说明没有碰撞箱 (空气、草、花)，可以穿过
        return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null;
    }

    /**
     * 检查某个位置是否“实心”，允许玩家踩在上面
     */
    private static boolean isSolid(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        
        if (block == Blocks.air) return false;
        
        // 排除栅栏、墙等不能直接踩上去(因为太高)或者判定复杂的方块
        // 但允许楼梯、台阶
        if (block instanceof BlockFence || block instanceof BlockFenceGate || block instanceof BlockWall) return false;
        
        // 检查材质是否实心 (水、岩浆返回 false)
        return block.getMaterial().isSolid();
    }

    /**
     * 检查头顶是否有空间 (2格高)
     */
    private static boolean isSafeHead(BlockPos feetPos) {
        return isPassable(feetPos.up());
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