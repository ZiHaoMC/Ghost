package com.zihaomc.ghost.features.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;

import java.util.*;

/**
 * 简单的 A* 寻路算法实现。
 * 用于计算地面行走的路径。
 */
public class Pathfinder {

    private static final Minecraft mc = Minecraft.getMinecraft();

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

            if (current.pos.equals(end) || current.pos.distanceSq(end) < 2.0) {
                return retracePath(current);
            }

            closedSet.add(current.pos);

            for (BlockPos neighborPos : getNeighbors(current.pos)) {
                if (closedSet.contains(neighborPos)) continue;

                if (!isWalkable(neighborPos)) continue;

                double newGCost = current.gCost + current.pos.distanceSq(neighborPos);

                Node neighborNode = allNodes.getOrDefault(neighborPos, new Node(neighborPos, null, Double.MAX_VALUE, 0));
                
                if (newGCost < neighborNode.gCost) {
                    neighborNode.parent = current;
                    neighborNode.gCost = newGCost;
                    neighborNode.hCost = neighborPos.distanceSq(end);
                    
                    if (!openSet.contains(neighborNode)) {
                        openSet.add(neighborNode);
                        allNodes.put(neighborPos, neighborNode);
                    }
                }
            }
        }

        return null; 
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

    private static List<BlockPos> getNeighbors(BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        // 水平方向
        neighbors.add(pos.north());
        neighbors.add(pos.south());
        neighbors.add(pos.east());
        neighbors.add(pos.west());
        // 对角线 (可选，为了简化暂不添加，防止卡墙)
        // 上下坡检测
        neighbors.add(pos.north().up());
        neighbors.add(pos.south().up());
        neighbors.add(pos.east().up());
        neighbors.add(pos.west().up());
        neighbors.add(pos.north().down());
        neighbors.add(pos.south().down());
        neighbors.add(pos.east().down());
        neighbors.add(pos.west().down());
        return neighbors;
    }

    private static boolean isWalkable(BlockPos pos) {
        // 检查脚下是否有方块支撑
        if (!isSolid(pos.down())) return false;
        
        // 检查当前位置和头部位置是否无阻挡
        if (isSolid(pos) || isSolid(pos.up())) return false;

        // 简单的避免危险检测 (岩浆等)
        Block blockBelow = mc.theWorld.getBlockState(pos.down()).getBlock();
        if (blockBelow == Blocks.lava || blockBelow == Blocks.flowing_lava || blockBelow == Blocks.cactus) return false;

        return true;
    }

    private static boolean isSolid(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        Material mat = block.getMaterial();
        return mat.isSolid() && block.isFullCube(); 
    }

    private static class Node implements Comparable<Node> {
        BlockPos pos;
        Node parent;
        double gCost; // 距离起点的代价
        double hCost; // 距离终点的预估代价

        public Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
        }

        public double getFCost() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.getFCost(), other.getFCost());
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Node) {
                return pos.equals(((Node) obj).pos);
            }
            return false;
        }
    }
}