package com.zihaomc.ghost.features.pathfinding;

import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.Vec3;

import java.util.*;

public class Pathfinder {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double WALL_PENALTY = 1000.0; 

    public static List<BlockPos> computePath(BlockPos start, BlockPos end, int maxIterations) {
        return computePathInternal(start, end, maxIterations);
    }

    private static List<BlockPos> computePathInternal(BlockPos rawStart, BlockPos end, int maxIterations) {
        // [核心修复] 智能起点选择
        // 现在会根据玩家的真实距离，选择最近的那个空地，防止选到墙后面去
        BlockPos start = getValidStart(rawStart);

        if (isSafeFloor(end) && isPassable(end.up()) && isPassable(end.up(2))) {
            end = end.up();
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Double> costMap = new HashMap<>();
        
        Node startNode = new Node(start, null, 0, getHeuristic(start, end));
        openSet.add(startNode);
        costMap.put(start, 0.0);

        int iterations = 0;

        while (!openSet.isEmpty() && iterations < maxIterations) {
            iterations++;
            Node current = openSet.poll();

            if (current.gCost > costMap.getOrDefault(current.pos, Double.MAX_VALUE)) {
                continue;
            }

            if (current.pos.distanceSq(end) < 1.5) {
                return retracePath(current);
            }

            for (Neighbor neighbor : getNeighbors(current.pos)) {
                BlockPos neighborPos = neighbor.pos;
                double penalty = getEnvironmentPenalty(neighborPos);
                double newGCost = current.gCost + neighbor.cost + penalty;

                if (newGCost < costMap.getOrDefault(neighborPos, Double.MAX_VALUE)) {
                    costMap.put(neighborPos, newGCost);
                    double hCost = getHeuristic(neighborPos, end);
                    openSet.add(new Node(neighborPos, current, newGCost, hCost));
                }
            }
        }
        
        return new ArrayList<>();
    }

    /**
     * [修复版] 获取有效起点
     * 如果 rawStart 是墙壁，寻找周围最近的空气格，而不是随机找一个
     */
    private static BlockPos getValidStart(BlockPos original) {
        // 1. 如果原始位置有效，直接返回
        if (canStandAt(original)) return original;
        
        // 尝试脚下 (防止因为半个台阶导致判定在上面)
        if (canStandAt(original.down())) return original.down();

        // 2. 搜索周围 4 个邻居，找出所有可行的候选点
        List<BlockPos> candidates = new ArrayList<>();
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            BlockPos neighbor = original.offset(facing);
            if (canStandAt(neighbor)) {
                candidates.add(neighbor);
            }
        }

        // 3. 如果没找到候选点，只能硬着头皮返回原始的
        if (candidates.isEmpty()) return original;

        // 4. [关键步骤] 按距离排序！
        // 找出离玩家真实坐标 (double) 最近的那个候选点
        // 这样如果你在 10.9 (靠近 10)，它绝对不会选到 12 (墙后)
        final Vec3 playerPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        
        candidates.sort((pos1, pos2) -> {
            // 计算中心点距离
            double d1 = playerPos.squareDistanceTo(new Vec3(pos1.getX() + 0.5, pos1.getY(), pos1.getZ() + 0.5));
            double d2 = playerPos.squareDistanceTo(new Vec3(pos2.getX() + 0.5, pos2.getY(), pos2.getZ() + 0.5));
            return Double.compare(d1, d2);
        });

        // 返回最近的那个
        return candidates.get(0);
    }

    private static double getEnvironmentPenalty(BlockPos pos) {
        double penalty = 0;
        if (!isPassable(pos.north())) penalty += WALL_PENALTY;
        if (!isPassable(pos.south())) penalty += WALL_PENALTY;
        if (!isPassable(pos.east()))  penalty += WALL_PENALTY;
        if (!isPassable(pos.west()))  penalty += WALL_PENALTY;
        return penalty;
    }

    private static double getHeuristic(BlockPos pos, BlockPos end) {
        return Math.abs(pos.getX() - end.getX()) + Math.abs(pos.getZ() - end.getZ()) + Math.abs(pos.getY() - end.getY());
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

        addIfSafe(neighbors, n, 1.0);
        addIfSafe(neighbors, s, 1.0);
        addIfSafe(neighbors, e, 1.0);
        addIfSafe(neighbors, w, 1.0);

        return neighbors;
    }

    private static boolean addIfSafe(List<Neighbor> neighbors, BlockPos target, double cost) {
        if (canStandAt(target)) {
            neighbors.add(new Neighbor(target, cost));
            return true;
        } 
        else if (isSafeFloor(target) && isPassable(target.up()) && isPassable(target.up(2))) {
            neighbors.add(new Neighbor(target.up(), cost + 0.5)); 
            return false; 
        } 
        else if (isPassable(target) && isPassable(target.down()) && canStandAt(target.down())) {
            neighbors.add(new Neighbor(target.down(), cost + 0.5));
            return true;
        }
        return false;
    }

    private static boolean canStandAt(BlockPos pos) {
        return isSafeFloor(pos.down()) && isPassable(pos) && isPassable(pos.up()) && !isDangerous(pos) && !isDangerous(pos.down());
    }

    private static boolean isPassable(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.air || block instanceof BlockAir) return true;
        if (block instanceof BlockTallGrass || block instanceof BlockFlower || block instanceof BlockBush || block instanceof BlockReed) return true;
        if (block instanceof BlockSnow && state.getValue(BlockSnow.LAYERS) == 1) return true;
        if (block.getCollisionBoundingBox(mc.theWorld, pos, state) == null) return true;

        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, state);
        if (box != null && (box.maxY - pos.getY() > 0.1)) {
            return false;
        }
        return true;
    }

    private static boolean isSafeFloor(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        
        if (block == Blocks.air) return false;
        if (isDangerous(pos)) return false;
        if (block instanceof BlockCarpet || block instanceof BlockSnow || block instanceof BlockLilyPad) return true;
        if (block instanceof BlockSlab || block instanceof BlockStairs || block instanceof BlockFence || block instanceof BlockWall) return true;
        if (block.isFullCube() || mc.theWorld.doesBlockHaveSolidTopSurface(mc.theWorld, pos)) return true;
        
        Material mat = block.getMaterial();
        return mat.isSolid() && mat != Material.cactus && mat != Material.lava;
    }

    private static boolean isDangerous(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.lava || block == Blocks.flowing_lava || 
               block == Blocks.cactus || block == Blocks.fire;
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
    }
}