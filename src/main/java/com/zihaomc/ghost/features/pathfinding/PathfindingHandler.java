package com.zihaomc.ghost.features.pathfinding;

import com.zihaomc.ghost.utils.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ExecutorService pathPool = Executors.newFixedThreadPool(2);

    private static boolean isPathfinding = false;
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    private static BlockPos globalTarget = null;
    private static boolean isCalculating = false;

    // 参数
    private static final int MAX_SEGMENT_LENGTH = 60;
    private static final int MIN_SEGMENT_LENGTH = 2;
    private static final boolean DEBUG = true;

    // --- 卡死检测变量 ---
    private static int stuckTimer = 0;
    private static Vec3 lastCheckPos = null;

    // --- 缓存与密度场系统 ---
    private static final PathCache cache = new PathCache();

    public static void setGlobalTarget(BlockPos target) {
        if (globalTarget == null || !globalTarget.equals(target)) {
            cache.clearDensity();
            cache.clearRuntimeHistory();
            stuckTimer = 0;
            lastCheckPos = null;
            debug(">>> 新目标设定，已重置探索热力图");
        }

        globalTarget = target;
        currentPath = null;
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;

        debug(">>> 启动新寻路，目标: " + target);
        cache.load();
        runTerrainAnalysis(target);
    }

    public static void stop() {
        if (isPathfinding && DEBUG) debug("<<< 寻路停止");
        isPathfinding = false;
        currentPath = null;
        globalTarget = null;
        isCalculating = false;
        stuckTimer = 0;
        lastCheckPos = null;
        resetKeys();
        cache.save();
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static BlockPos getGlobalTarget() { return globalTarget; }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        BlockPos currentPos = new BlockPos(mc.thePlayer);
        cache.incrementDensity(currentPos);

        double distToTarget = mc.thePlayer.getDistanceSq(globalTarget);
        boolean verticalMatch = Math.abs(mc.thePlayer.posY - globalTarget.getY()) < 1.0;

        if (globalTarget != null && distToTarget < 2.5 && verticalMatch) {
            stop();
            cache.clearDensity();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        if (currentPath != null && !currentPath.isEmpty() && !isCalculating) {
            if (checkStuckAndDeviation()) {
                return;
            }
        }

        if (!isCalculating) {
            boolean needsNewPath = (currentPath == null) || (currentPathIndex >= currentPath.size() - 3);
            if (needsNewPath) {
                generateNextSegment();
            }
        }

        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            resetKeys();
            return;
        }

        followPathSmoothed();
    }

    private boolean checkStuckAndDeviation() {
        if (currentPathIndex >= currentPath.size()) return false;

        BlockPos targetNode = currentPath.get(currentPathIndex);
        Vec3 playerPos = mc.thePlayer.getPositionVector();

        // 1. 垂直偏差 (只检查往上飞的情况)
        if (targetNode.getY() > playerPos.yCoord + 2.5) {
            if (DEBUG) debugErr("路径不可达 (高度差过大)，强制重寻...");
            forceRepath();
            return true;
        }

        // 2. 水平偏差
        double distSq = playerPos.squareDistanceTo(new Vec3(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5));
        if (distSq > 36.0) {
            if (DEBUG) debugErr("严重偏离预定路径，强制重寻...");
            forceRepath();
            return true;
        }

        // 3. 原地卡死
        stuckTimer++;
        if (stuckTimer >= 15) {
            if (lastCheckPos != null) {
                double moveDist = lastCheckPos.distanceTo(playerPos);
                if (moveDist < 0.5) {
                    if (DEBUG) debugErr("检测到长时间滞留，尝试避障...");
                    cache.markAsBadRegion(targetNode);
                    forceRepath();
                    stuckTimer = 0;
                    lastCheckPos = playerPos;
                    return true;
                }
            }
            lastCheckPos = playerPos;
            stuckTimer = 0;
        }
        return false;
    }

    private void forceRepath() {
        currentPath = null;
        currentPathIndex = 0;
        isCalculating = false;
        resetKeys();
    }

    private void generateNextSegment() {
        if (isCalculating) return;
        isCalculating = true;

        final BlockPos startPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);

        pathPool.execute(() -> {
            try {
                if (globalTarget == null || !isPathfinding) {
                    isCalculating = false;
                    return;
                }

                List<BlockPos> pathSegment = null;
                boolean forceManualDrop = false;

                double dx = globalTarget.getX() - startPos.getX();
                double dz = globalTarget.getZ() - startPos.getZ();
                double distToGlobalSq = dx * dx + dz * dz;

                // 1. 垂直下落判断
                if (distToGlobalSq < 16.0 && globalTarget.getY() < startPos.getY()) {
                    BlockPos dropSpot = findNearestDropSpot(startPos);
                    if (dropSpot != null) {
                        pathSegment = new ArrayList<>();
                        pathSegment.add(dropSpot);
                        forceManualDrop = true;
                        if(DEBUG) debug("发现垂直下落点，准备下落...");
                    }
                }

                // 2. 启发式扫描
                if (pathSegment == null) {
                    double baseAngle = Math.atan2(dz, dx);
                    List<BlockPos> candidates = scanForCandidates(startPos, baseAngle, Math.sqrt(distToGlobalSq));

                    if (candidates.isEmpty()) {
                        if(DEBUG) debug("常规扫描无果，尝试逃逸模式...");
                        candidates = scanEscapeCandidates(startPos);
                    }

                    if (distToGlobalSq < MAX_SEGMENT_LENGTH * MAX_SEGMENT_LENGTH) {
                        if (!cache.isBadRegion(globalTarget)) {
                            candidates.add(0, globalTarget);
                        }
                    }

                    sortCandidatesByHeuristic(candidates, startPos, globalTarget);

                    int tryCount = 0;
                    for (BlockPos candidate : candidates) {
                        if (candidate.equals(startPos)) continue;
                        tryCount++;
                        if (tryCount > 6) break;

                        List<BlockPos> testPath = Pathfinder.computePath(startPos, candidate, 4000);

                        if (testPath != null && !testPath.isEmpty()) {
                            pathSegment = testPath;
                            if(DEBUG) {
                                debug(String.format("路径分段生成: %s -> %s", startPos, candidate));
                            }
                            break;
                        } else {
                            cache.markAsBadRegion(candidate);
                        }

                        if (canWalkDirectly(mc.thePlayer.getPositionVector(), candidate)) {
                            pathSegment = new ArrayList<>();
                            pathSegment.add(candidate);
                            if(DEBUG) debug("直线可视 -> " + candidate);
                            break;
                        }
                    }
                }

                if (pathSegment != null && !pathSegment.isEmpty()) {
                    final List<BlockPos> finalPath = pathSegment;
                    final boolean manualDrop = forceManualDrop;
                    mc.addScheduledTask(() -> {
                        if (!isPathfinding) return;
                        if (manualDrop) {
                            List<BlockPos> p = new ArrayList<>();
                            p.add(finalPath.get(0));
                            applyNewPath(p);
                        } else {
                            applyNewPath(finalPath);
                        }
                        isCalculating = false;
                    });
                } else {
                    if (DEBUG) debugErr("寻路计算失败，等待重试...");
                    mc.addScheduledTask(() -> isCalculating = false);
                }

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false);
            }
        });
    }

    // --- 核心：启发式评分系统 ---

    private void sortCandidatesByHeuristic(List<BlockPos> candidates, BlockPos start, BlockPos target) {
        // 预先计算起点到终点的向量，用于计算角度偏差
        double totalDx = target.getX() - start.getX();
        double totalDz = target.getZ() - start.getZ();
        double totalDist = Math.sqrt(totalDx * totalDx + totalDz * totalDz);

        candidates.sort((p1, p2) -> {
            double score1 = calculateScore(p1, start, target, totalDx, totalDz, totalDist);
            double score2 = calculateScore(p2, start, target, totalDx, totalDz, totalDist);
            return Double.compare(score1, score2);
        });
    }

    private double calculateScore(BlockPos pos, BlockPos start, BlockPos target, double tDx, double tDz, double totalDist) {
        // G Cost: 距离起点的距离
        double gCost = Math.sqrt(pos.distanceSq(start));
        // H Cost: 距离终点的距离
        double hCost = Math.sqrt(pos.distanceSq(target));

        // 密度惩罚 (避免重复走同一片区域)
        int density = cache.getDensity(pos);
        double explorationPenalty = density * 100.0;

        // 高度处理: 优先保持高度或缓慢下降，避免不必要的剧烈上下
        double heightDiff = pos.getY() - start.getY();
        double heightPenalty = 0.0;
        if (pos.getY() > target.getY() && heightDiff > 0) heightPenalty = heightDiff * 20; // 目标在下方但我们往上走
        if (pos.getY() < target.getY() && heightDiff < 0) heightPenalty = Math.abs(heightDiff) * 20; // 目标在上方但我们往下走

        // 角度对齐奖励: 优先选择在 "起点->终点" 连线附近的点
        double dX = pos.getX() - start.getX();
        double dZ = pos.getZ() - start.getZ();
        double dotProduct = (dX * tDx + dZ * tDz) / (Math.sqrt(dX*dX + dZ*dZ) * totalDist);
        // dotProduct 接近 1.0 表示方向完美对齐
        double angleBonus = (1.0 - dotProduct) * 500.0; // 偏差越大惩罚越大

        return gCost + hCost + explorationPenalty + heightPenalty + angleBonus;
    }

    // --- 扫描逻辑 ---

    private List<BlockPos> scanForCandidates(BlockPos startPos, double baseAngle, double distToGlobal) {
        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        // 减少扫描角度范围，更加集中于目标方向
        int[] angles = {0, 10, -10, 20, -20, 35, -35, 50, -50, 65, -65};

        for (int angleOffset : angles) {
            double searchDist = Math.min(distToGlobal + 5.0, MAX_SEGMENT_LENGTH);
            double rad = baseAngle + Math.toRadians(angleOffset);

            BlockPos spot = raycastFindValidSpot(startPos, rad, searchDist);

            if (spot != null && !visited.contains(spot)) {
                if (cache.isBadRegion(spot)) continue;

                if (spot.distanceSq(startPos) > 9.0) { // 忽略太近的点
                    candidates.add(spot);
                    visited.add(spot);
                }
            }
        }
        return candidates;
    }

    private List<BlockPos> scanEscapeCandidates(BlockPos startPos) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45);
            BlockPos spot = raycastFindValidSpot(startPos, angle, MAX_SEGMENT_LENGTH / 2.0);
            if (spot != null && spot.distanceSq(startPos) > 16.0 && !cache.isBadRegion(spot)) {
                candidates.add(spot);
            }
        }
        return candidates;
    }

    private BlockPos raycastFindValidSpot(BlockPos start, double angleRad, double startDist) {
        double nx = Math.cos(angleRad);
        double nz = Math.sin(angleRad);
        double checkDist = startDist;
        while (checkDist > MIN_SEGMENT_LENGTH) {
            int tx = (int) (start.getX() + nx * checkDist);
            int tz = (int) (start.getZ() + nz * checkDist);
            if (mc.theWorld.getChunkProvider().chunkExists(tx >> 4, tz >> 4)) {
                BlockPos candidate = findSafeY(tx, tz, start.getY());
                if (candidate != null) return candidate;
            }
            checkDist -= 4.0; // 加大步长以提高性能
        }
        return null;
    }

    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        // 剪枝优化：如果新路径的开始部分离我们很近，直接跳过
        while (newPath.size() > 1) {
            BlockPos node = newPath.get(0);
            if (playerPos.squareDistanceTo(new Vec3(node.getX()+0.5, node.getY(), node.getZ()+0.5)) < 4.0) {
                newPath.remove(0);
            } else {
                break;
            }
        }
        if (newPath.isEmpty()) return;
        currentPath = new CopyOnWriteArrayList<>(newPath);
        currentPathIndex = 0;
    }

    private void followPathSmoothed() {
        if (currentPath == null || currentPath.isEmpty()) return;
        updatePathIndexAutomatically();
        if (currentPathIndex >= currentPath.size()) return;

        BlockPos aimTarget = currentPath.get(currentPathIndex);

        // 前瞻机制：如果能直接走到更远的节点，就忽略当前节点，实现切角转弯
        int lookAheadDist = 3;
        for (int i = 1; i <= lookAheadDist; i++) {
            int testIndex = currentPathIndex + i;
            if (testIndex >= currentPath.size()) break;
            BlockPos nextNode = currentPath.get(testIndex);
            if (canWalkDirectly(mc.thePlayer.getPositionVector(), nextNode)) {
                aimTarget = nextNode;
            } else {
                break;
            }
        }

        Vec3 targetCenter = new Vec3(aimTarget.getX() + 0.5, aimTarget.getY(), aimTarget.getZ() + 0.5);

        // 高度修正：如果目标在下方，看向脚下；如果在上方，看向头部高度
        if (aimTarget.getY() < mc.thePlayer.posY - 0.5) {
            targetCenter = new Vec3(targetCenter.xCoord, mc.thePlayer.posY - 1.0, targetCenter.zCoord);
        } else {
            targetCenter = targetCenter.addVector(0, mc.thePlayer.getEyeHeight() - 0.2, 0);
        }

        float[] rotations = RotationUtil.getRotations(targetCenter);
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - mc.thePlayer.rotationYaw));

        // 动态转速：角度差距大时转快点，小时转慢点
        float speed = yawDiff > 30 ? 60.0f : 40.0f;
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], speed);
        float smoothPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], speed);

        mc.thePlayer.rotationYaw = smoothYaw;
        mc.thePlayer.rotationPitch = smoothPitch;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);

        // 智能疾跑：角度调整过大时，暂时松开疾跑，防止冲过头
        boolean shouldSprint = true;
        if (yawDiff > 45.0f || mc.thePlayer.moveForward < 0) {
            shouldSprint = false;
        }
        mc.thePlayer.setSprinting(shouldSprint);

        // 智能跳跃：路径要求跳，或者被方块卡住脚了
        boolean jump = false;
        if (mc.thePlayer.onGround) {
            if (aimTarget.getY() > mc.thePlayer.getEntityBoundingBox().minY + 0.6) {
                jump = true;
            } else if (mc.thePlayer.isCollidedHorizontally) {
                // 如果水平方向有碰撞且我们还在移动，尝试跳跃越过障碍（类似 AutoJump）
                jump = true;
            }
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    private boolean canWalkDirectly(Vec3 startPos, BlockPos end) {
        Vec3 endCenter = new Vec3(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        Vec3 startEye = startPos.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        // 如果目标点在下方，视线检查点稍微上移，避免看地板被遮挡
        double endYOffset = (end.getY() < startPos.yCoord) ? 1.0 : 0.5;
        Vec3 endEye = new Vec3(end.getX() + 0.5, end.getY() + endYOffset, end.getZ() + 0.5);

        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startEye, endEye, false, true, false);
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos hitPos = result.getBlockPos();
            if (!hitPos.equals(end)) {
                // 允许视线穿过草和花
                if (!isCenterBlocked(hitPos)) {
                     // pass
                } else if (hitPos.getY() > startPos.yCoord) {
                    if (!canWalkThrough(hitPos)) return false;
                }
            }
        }
        if (!isPathSafe(startPos, endCenter)) return false;
        return true;
    }

    private boolean isPathSafe(Vec3 start, Vec3 end) {
        double dist = start.distanceTo(end);
        double stepSize = 0.5;
        double dx = (end.xCoord - start.xCoord) / dist;
        double dy = (end.yCoord - start.yCoord) / dist;
        double dz = (end.zCoord - start.zCoord) / dist;
        double currentX = start.xCoord;
        double currentY = start.yCoord;
        double currentZ = start.zCoord;

        for (double d = 0; d < dist; d += stepSize) {
            currentX += dx * stepSize;
            currentY += dy * stepSize;
            currentZ += dz * stepSize;
            BlockPos posToCheck = new BlockPos(currentX, currentY, currentZ);

            if (!isSafeToStep(posToCheck)) {
                // 允许空中路径（只要下方不是致死的）
                if (canSurviveFall(posToCheck)) continue;
                return false;
            }
        }
        return true;
    }

    private BlockPos findNearestDropSpot(BlockPos start) {
        BlockPos bestSpot = null;
        double minDistSq = 999.0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos candidate = start.add(x, 0, z);

                if (!canWalkThrough(candidate)) continue;

                // 检查是否是边缘（脚下是空的）
                BlockPos ground = candidate.down();
                Material mat = mc.theWorld.getBlockState(ground).getBlock().getMaterial();

                if ((mat == Material.air || mat == Material.water) && canSurviveFall(candidate)) {
                    double dSq = start.distanceSq(candidate);
                    if (dSq < minDistSq) {
                        minDistSq = dSq;
                        bestSpot = candidate;
                    }
                }
            }
        }
        return bestSpot;
    }

    private boolean canSurviveFall(BlockPos startPos) {
        if (startPos.getY() < 0) return false;
        // 简化版掉落检查：只看正下方是否有岩浆或虚空
        BlockPos checkPos = startPos.down();
        if (!mc.theWorld.getChunkProvider().chunkExists(checkPos.getX() >> 4, checkPos.getZ() >> 4)) return false;

        Block block = mc.theWorld.getBlockState(checkPos).getBlock();
        Material mat = block.getMaterial();

        if (mat == Material.lava || block == Blocks.cactus) return false;
        return true;
    }

    private static void runTerrainAnalysis(BlockPos target) {
        // 异步地形分析占位符
    }

    private static boolean isCenterBlocked(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block instanceof BlockCarpet || block instanceof BlockSnow || block instanceof BlockLilyPad ||
            block == Blocks.tallgrass || block == Blocks.double_plant || block == Blocks.deadbush ||
            block == Blocks.vine || block == Blocks.air) return false;
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, mc.theWorld.getBlockState(pos));
        return box != null;
    }

    private static double getBlockHeight(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, mc.theWorld.getBlockState(pos));
        if (box == null) return 0.0;
        return box.maxY - pos.getY();
    }

    private static boolean canWalkThrough(BlockPos pos) {
        if (isCenterBlocked(pos)) {
            if (getBlockHeight(pos) > 0.6) return false;
        }
        if (isCenterBlocked(pos.up())) {
            if (getBlockHeight(pos.up()) > 0.8) return false;
        }
        return true;
    }

    private boolean isSafeToStep(BlockPos pos) {
        if (!mc.theWorld.getChunkProvider().chunkExists(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockPos groundPos = pos.down();
        Block groundBlock = mc.theWorld.getBlockState(groundPos).getBlock();
        double groundHeight = getBlockHeight(groundPos);
        Material mat = groundBlock.getMaterial();

        if (groundHeight <= 0.0 && !mat.isLiquid()) return false;
        if (groundBlock == Blocks.cactus || groundBlock == Blocks.web || groundBlock == Blocks.lava) return false;
        return canWalkThrough(pos);
    }

    private void updatePathIndexAutomatically() {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        int searchRange = 8;
        int maxIndex = Math.min(currentPathIndex + searchRange, currentPath.size() - 1);
        for (int i = currentPathIndex; i <= maxIndex; i++) {
            BlockPos node = currentPath.get(i);
            // 放宽距离判定，只要进入了 1.0 格范围就算到达
            double distSq = playerPos.squareDistanceTo(new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5));
            if (distSq < 1.0) {
                currentPathIndex = i + 1;
            }
        }
    }

    private BlockPos findSafeY(int x, int z, int startY) {
        if (isSafeToStep(new BlockPos(x, startY, z))) return new BlockPos(x, startY, z);
        for (int y = startY + 1; y <= startY + 4; y++) {
            if (isSafeToStep(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        for (int y = startY - 1; y >= startY - 4; y--) {
            if (isSafeToStep(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        return null;
    }

    private float limitAngleChange(float current, float target, float maxChange) {
        float change = MathHelper.wrapAngleTo180_float(target - current);
        if (change > maxChange) change = maxChange;
        if (change < -maxChange) change = -maxChange;
        return current + change;
    }

    private static void resetKeys() {
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
            if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
        }
    }

    private static void debug(String msg) {
        if (DEBUG && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Debug] " + msg));
        }
    }

    private static void debugErr(String msg) {
        if (DEBUG && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Error] " + msg));
        }
    }

    private static class PathCache {
        private final Set<String> blacklistedRegions = new HashSet<>();
        private final ConcurrentHashMap<Long, Integer> densityMap = new ConcurrentHashMap<>();
        private final List<BlockPos> runtimeHistory = new LinkedList<>();
        private final File cacheFile;

        public PathCache() {
            this.cacheFile = new File(mc.mcDataDir, "ghost_path_blacklist.txt");
        }

        private String getRegionKeyStr(BlockPos pos) {
            int rx = pos.getX() / 4; // 稍微扩大坏区域的粒度
            int ry = pos.getY() / 4;
            int rz = pos.getZ() / 4;
            return rx + "," + ry + "," + rz;
        }

        private long getDensityKey(BlockPos pos) {
            long x = pos.getX() >> 3;
            long y = pos.getY() >> 3;
            long z = pos.getZ() >> 3;
            return (x & 0x3FFFFF) | ((z & 0x3FFFFF) << 22) | ((y & 0xFF) << 44);
        }

        public void load() {
            blacklistedRegions.clear();
            if (!cacheFile.exists()) return;
            try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    blacklistedRegions.add(line.trim());
                }
            } catch (IOException e) { e.printStackTrace(); }
        }

        public void save() {
            try (PrintWriter writer = new PrintWriter(cacheFile)) {
                for (String regionKey : blacklistedRegions) {
                    writer.println(regionKey);
                }
            } catch (IOException e) { e.printStackTrace(); }
        }

        public void markAsBadRegion(BlockPos pos) {
            if (blacklistedRegions.add(getRegionKeyStr(pos))) {
                save();
            }
        }

        public boolean isBadRegion(BlockPos pos) {
            return blacklistedRegions.contains(getRegionKeyStr(pos));
        }

        public void incrementDensity(BlockPos pos) {
            long key = getDensityKey(pos);
            densityMap.merge(key, 1, Integer::sum);
        }

        public int getDensity(BlockPos pos) {
            return densityMap.getOrDefault(getDensityKey(pos), 0);
        }

        public void clearDensity() {
            densityMap.clear();
        }

        public void clearRuntimeHistory() {
            runtimeHistory.clear();
        }
    }
}