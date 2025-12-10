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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PathfindingHandler {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static boolean isPathfinding = false;
    private static List<BlockPos> currentPath = null;
    private static int currentPathIndex = 0;
    private static BlockPos globalTarget = null;
    private static boolean isCalculating = false;

    // 参数
    private static final int MAX_SEGMENT_LENGTH = 60; 
    private static final int MIN_SEGMENT_LENGTH = 2;

    private static final boolean DEBUG = true; 

    // --- 缓存系统 ---
    private static final PathCache cache = new PathCache();

    public static void setGlobalTarget(BlockPos target) {
        if (globalTarget == null || !globalTarget.equals(target)) {
            cache.clearRuntimeHistory(); 
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
        resetKeys();
        cache.save();
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static BlockPos getGlobalTarget() { return globalTarget; }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        BlockPos currentPos = new BlockPos(mc.thePlayer);
        cache.markVisited(currentPos); // 记录足迹

        double distToTarget = mc.thePlayer.getDistanceSq(globalTarget);
        boolean verticalMatch = Math.abs(mc.thePlayer.posY - globalTarget.getY()) < 2.0;

        if (globalTarget != null && distToTarget < 4.0 && verticalMatch) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        if (!isCalculating) {
            boolean needsNewPath = (currentPath == null) || (currentPathIndex >= currentPath.size() - 4);
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

    private void generateNextSegment() {
        isCalculating = true;
        final BlockPos startPos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);

        new Thread(() -> {
            try {
                if (globalTarget == null) return;

                List<BlockPos> pathSegment = null;
                boolean forceManualDrop = false;

                double dx = globalTarget.getX() - startPos.getX();
                double dz = globalTarget.getZ() - startPos.getZ();
                double distToGlobalSq = dx * dx + dz * dz;

                // 1. 优先检查是否可以直接垂直下落到达
                if (distToGlobalSq < 4.0 && globalTarget.getY() < startPos.getY()) { 
                    BlockPos dropSpot = findNearestDropSpot(startPos);
                    if (dropSpot != null) {
                        pathSegment = new ArrayList<>();
                        pathSegment.add(dropSpot);
                        forceManualDrop = true;
                    }
                }

                // 2. 如果不需要下落，开始多候选点路径计算
                if (pathSegment == null) {
                    double baseAngle = Math.atan2(dz, dx);
                    
                    List<BlockPos> candidates = scanForCandidates(startPos, baseAngle, Math.sqrt(distToGlobalSq));
                    
                    if (candidates.isEmpty()) {
                         if(DEBUG) debug("常规扫描无果（可能周围都被拉黑了），启动逃逸模式...");
                         candidates = scanEscapeCandidates(startPos);
                    }
                    
                    // 终点永远是最高优先级，除非终点本身就在黑名单区域里
                    if (distToGlobalSq < MAX_SEGMENT_LENGTH * MAX_SEGMENT_LENGTH) {
                        if (!cache.isBadRegion(globalTarget)) {
                            candidates.add(0, globalTarget);
                        }
                    }

                    int tryCount = 0;
                    for (BlockPos candidate : candidates) {
                        if (candidate.equals(startPos)) continue;
                        tryCount++;
                        if (tryCount > 8) break;

                        List<BlockPos> testPath = Pathfinder.computePath(startPos, candidate, 6000);
                        
                        if (testPath != null && !testPath.isEmpty()) {
                            pathSegment = testPath;
                            if(DEBUG) debug("选中第 " + tryCount + " 个候选点: " + candidate);
                            break;
                        } else {
                            // [核心修改]：拉黑时，不再只拉黑一个点，而是拉黑所在的 3x3 区域
                            cache.markAsBadRegion(candidate);
                            if(DEBUG) debug("区域不可达，已拉黑周边: " + candidate);
                        }

                        if (canWalkDirectly(mc.thePlayer.getPositionVector(), candidate)) {
                            pathSegment = new ArrayList<>();
                            pathSegment.add(candidate);
                            if(DEBUG) debug("直线强制模式 -> " + candidate);
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
                    if (DEBUG) debugErr("寻路陷入僵局，尝试随机漫步以重置状态...");
                    mc.addScheduledTask(() -> isCalculating = false);
                }

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false);
            }
        }).start();
    }

    // --- 增强的扫描逻辑 ---

    private List<BlockPos> scanForCandidates(BlockPos startPos, double baseAngle, double distToGlobal) {
        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>(); 
        
        int[] angles = {0, 5, -5, 15, -15, 30, -30, 45, -45, 60, -60, 90, -90, 120, -120, 135, -135};
        
        for (int angleOffset : angles) {
            double searchDist = Math.min(distToGlobal + 5.0, MAX_SEGMENT_LENGTH);
            double rad = baseAngle + Math.toRadians(angleOffset);
            
            BlockPos spot = raycastFindValidSpot(startPos, rad, searchDist);
            
            if (spot != null && !visited.contains(spot)) {
                // 过滤逻辑升级：
                // 1. 检查该点所在的区域是否在黑名单中 (isBadRegion)
                if (cache.isBadRegion(spot)) continue; 
                
                // 2. 检查是否在最近走过的足迹附近 (hasRecentlyVisitedNear)
                if (cache.hasRecentlyVisitedNear(spot, 4.0)) continue;

                if (spot.distanceSq(startPos) > 4.0 || distToGlobal < 10.0) {
                    candidates.add(spot);
                    visited.add(spot);
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(pos -> pos.distanceSq(globalTarget)));
        
        if (candidates.size() > 10) {
            return candidates.subList(0, 10);
        }
        return candidates;
    }

    private List<BlockPos> scanEscapeCandidates(BlockPos startPos) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30);
            BlockPos spot = raycastFindValidSpot(startPos, angle, MAX_SEGMENT_LENGTH);
            // 逃逸也要避开黑名单区域
            if (spot != null && spot.distanceSq(startPos) > 16.0 && !cache.isBadRegion(spot)) {
                candidates.add(spot);
            }
        }
        candidates.sort((p1, p2) -> Double.compare(p2.distanceSq(startPos), p1.distanceSq(startPos)));
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
                if (candidate != null) {
                    return candidate;
                }
            }
            checkDist -= 3.0; 
        }
        return null;
    }

    private void applyNewPath(List<BlockPos> newPath) {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        while (!newPath.isEmpty()) {
            BlockPos node = newPath.get(0);
            Vec3 nodeCenter = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
            double distSq = (playerPos.xCoord - nodeCenter.xCoord) * (playerPos.xCoord - nodeCenter.xCoord) + 
                            (playerPos.zCoord - nodeCenter.zCoord) * (playerPos.zCoord - nodeCenter.zCoord);
            if (distSq < 2.0 && newPath.size() > 1) { 
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
        int lookAheadDist = 4; 
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
        if (aimTarget.getY() < mc.thePlayer.posY - 0.5) {
            targetCenter = new Vec3(targetCenter.xCoord, mc.thePlayer.posY, targetCenter.zCoord);
        } else {
            targetCenter = targetCenter.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        }
        
        float[] rotations = RotationUtil.getRotations(targetCenter);
        float smoothYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], 50.0f); 
        float smoothPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], 50.0f);

        mc.thePlayer.rotationYaw = smoothYaw;
        if (aimTarget.getY() < mc.thePlayer.posY - 1.0) {
            mc.thePlayer.rotationPitch = 0.0f;
        } else {
            mc.thePlayer.rotationPitch = smoothPitch;
        }

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
        mc.thePlayer.setSprinting(true);

        boolean jump = false;
        if (mc.thePlayer.isCollidedHorizontally && mc.thePlayer.onGround) jump = true;
        if (aimTarget.getY() > mc.thePlayer.getEntityBoundingBox().minY + 0.5) jump = true;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    private boolean canWalkDirectly(Vec3 startPos, BlockPos end) {
        Vec3 endCenter = new Vec3(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        Vec3 startEye = startPos.addVector(0, mc.thePlayer.getEyeHeight(), 0);
        double endYOffset = (end.getY() < startPos.yCoord) ? 1.0 : 0.5;
        Vec3 endEye = new Vec3(end.getX() + 0.5, end.getY() + endYOffset, end.getZ() + 0.5);

        MovingObjectPosition result = mc.theWorld.rayTraceBlocks(startEye, endEye, false, true, false);
        if (result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos hitPos = result.getBlockPos();
            if (!hitPos.equals(end)) {
                if (hitPos.getY() > startPos.yCoord) {
                    if (!canWalkThrough(hitPos)) return false; 
                }
            }
        }
        if (!isPathSafe(startPos, endCenter)) return false; 
        return true;
    }

    private boolean isPathSafe(Vec3 start, Vec3 end) {
        double dist = start.distanceTo(end);
        double dx = (end.xCoord - start.xCoord) / dist;
        double dy = (end.yCoord - start.yCoord) / dist;
        double dz = (end.zCoord - start.zCoord) / dist;
        double stepSize = 0.4;
        
        double currentX = start.xCoord;
        double currentY = start.yCoord; 
        double currentZ = start.zCoord;

        for (double d = 0; d < dist; d += stepSize) {
            currentX += dx * stepSize;
            currentY += dy * stepSize;
            currentZ += dz * stepSize;

            BlockPos posToCheck = new BlockPos(currentX, currentY, currentZ);
            
            if (!isSafeToStep(posToCheck)) {
                if (!isSafeToStep(posToCheck.down())) {
                    boolean isTargetBelow = end.yCoord < start.yCoord;
                    if (isTargetBelow && canSurviveFall(posToCheck)) continue; 
                    return false;
                }
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
                BlockPos ground = candidate.down();
                double groundH = getBlockHeight(ground);
                Material mat = mc.theWorld.getBlockState(ground).getBlock().getMaterial();
                if (groundH <= 0.0 && !mat.isLiquid()) {
                     if (canSurviveFall(candidate)) {
                        double dSq = start.distanceSq(candidate);
                        if (dSq < minDistSq) {
                            minDistSq = dSq;
                            bestSpot = candidate;
                        }
                    }
                }
            }
        }
        return bestSpot;
    }

    private boolean canSurviveFall(BlockPos startPos) {
        float currentHealth = mc.thePlayer.getHealth();
        float minHealthToKeep = 6.0f; 
        if (currentHealth <= minHealthToKeep) return false;

        for (int i = 0; i <= 20; i++) {
            BlockPos checkPos = startPos.down(i);
            if (!mc.theWorld.getChunkProvider().chunkExists(checkPos.getX() >> 4, checkPos.getZ() >> 4)) return false;
            Block block = mc.theWorld.getBlockState(checkPos).getBlock();
            Material mat = block.getMaterial();

            if (mat.isSolid() || mat.isLiquid()) {
                if (mat == Material.water) return true;
                if (mat == Material.lava || block == Blocks.cactus) return false;
                float damage = Math.max(0, (i - 1) - 3.0f); 
                return (currentHealth - damage) > minHealthToKeep;
            }
        }
        return false;
    }

    private static void runTerrainAnalysis(BlockPos target) {
        final BlockPos start = mc.thePlayer.getPosition();
        new Thread(() -> {
            try {
                int minX = Math.min(start.getX(), target.getX()) - 2;
                int maxX = Math.max(start.getX(), target.getX()) + 2;
                int minY = Math.min(start.getY(), target.getY()) - 2;
                int maxY = Math.max(start.getY(), target.getY()) + 5; 
                int minZ = Math.min(start.getZ(), target.getZ()) - 2;
                int maxZ = Math.max(start.getZ(), target.getZ()) + 2;

                File dumpFile = new File(mc.mcDataDir, "ghost_terrain_scan.txt");
                try (PrintWriter writer = new PrintWriter(dumpFile, "UTF-8")) {
                    writer.println("=== Ghost 地形自动扫描报告 ===");
                    writer.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                    writer.println("起点: " + start);
                    writer.println("终点: " + target);
                    writer.println("扫描范围: X[" + minX + "~" + maxX + "] Y[" + minY + "~" + maxY + "] Z[" + minZ + "~" + maxZ + "]");
                    writer.println("--------------------------------------------------");
                    writer.println("格式: [X, Y, Z] | 方块名称 | 碰撞高度 | 材质 | 阻挡判定");
                    writer.println("--------------------------------------------------");

                    int count = 0;
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                if (!mc.theWorld.getChunkProvider().chunkExists(x >> 4, z >> 4)) continue;
                                BlockPos pos = new BlockPos(x, y, z);
                                Block block = mc.theWorld.getBlockState(pos).getBlock();
                                if (block.getMaterial() == Material.air && !pos.equals(target)) continue;

                                double h = getBlockHeight(pos);
                                String name = block.getLocalizedName();
                                String status = "通行";
                                if (isCenterBlocked(pos)) {
                                    if (h > 0.6) status = "!!!阻挡(脚)!!!";
                                    if (h > 0.0 && y == start.getY() + 1) status = "!!!阻挡(头)!!!";
                                }
                                if (block.getMaterial().isLiquid()) status = "液体";
                                writer.printf("[%d, %d, %d] | %-15s | H:%.2f | %s\n", x, y, z, name, h, status);
                                count++;
                            }
                        }
                    }
                    writer.println("--------------------------------------------------");
                    writer.println("共记录 " + count + " 个非空气方块。");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static boolean isCenterBlocked(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block instanceof BlockCarpet || block instanceof BlockSnow || block instanceof BlockLilyPad || 
            block == Blocks.tallgrass || block == Blocks.double_plant || block == Blocks.deadbush) return false;
        
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, pos, mc.theWorld.getBlockState(pos));
        if (box == null) return false; 

        double centerXMin = pos.getX() + 0.3;
        double centerXMax = pos.getX() + 0.7;
        double centerZMin = pos.getZ() + 0.3;
        double centerZMax = pos.getZ() + 0.7;

        boolean xIntersect = (box.maxX > centerXMin && box.minX < centerXMax);
        boolean zIntersect = (box.maxZ > centerZMin && box.minZ < centerZMax);
        return xIntersect && zIntersect;
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
        int searchRange = 10;
        int maxIndex = Math.min(currentPathIndex + searchRange, currentPath.size() - 1);
        for (int i = currentPathIndex; i <= maxIndex; i++) {
            BlockPos node = currentPath.get(i);
            double distSq = playerPos.squareDistanceTo(new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5));
            if (distSq < 0.8 * 0.8) {
                currentPathIndex = i + 1; 
            }
        }
    }

    private BlockPos findSafeY(int x, int z, int startY) {
        if (isSafeToStep(new BlockPos(x, startY, z))) return new BlockPos(x, startY, z);
        for (int y = startY + 1; y <= startY + 5; y++) {
            if (isSafeToStep(new BlockPos(x, y, z))) return new BlockPos(x, y, z);
        }
        for (int y = startY - 1; y >= startY - 5; y--) {
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

    /**
     * 修改后的缓存逻辑：支持区域拉黑和模糊足迹
     */
    private static class PathCache {
        // 存储的是 "区域ID"，一个ID代表一个 3x3x3 的空间
        private final Set<String> blacklistedRegions = new HashSet<>();
        private final List<BlockPos> runtimeHistory = new LinkedList<>(); // 使用List以保留顺序，方便限制大小
        
        private final File cacheFile;

        public PathCache() {
            this.cacheFile = new File(mc.mcDataDir, "ghost_path_blacklist.txt");
        }

        // 计算区域ID：将坐标除以3，实现网格化
        private String getRegionKey(BlockPos pos) {
            int rx = pos.getX() / 3;
            int ry = pos.getY() / 3;
            int rz = pos.getZ() / 3;
            return rx + "," + ry + "," + rz;
        }

        public void load() {
            blacklistedRegions.clear();
            if (!cacheFile.exists()) return;
            try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    blacklistedRegions.add(line.trim());
                }
                debug("已加载 " + blacklistedRegions.size() + " 个死胡同区域。");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void save() {
            try (PrintWriter writer = new PrintWriter(cacheFile)) {
                for (String regionKey : blacklistedRegions) {
                    writer.println(regionKey);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 拉黑整个 3x3x3 区域
        public void markAsBadRegion(BlockPos pos) {
            String key = getRegionKey(pos);
            if (blacklistedRegions.add(key)) {
                // 每次新增死胡同也自动保存一次，防止游戏崩溃数据丢失
                save(); 
            }
        }

        public boolean isBadRegion(BlockPos pos) {
            return blacklistedRegions.contains(getRegionKey(pos));
        }

        public void markVisited(BlockPos pos) {
            // 防止列表无限膨胀
            if (runtimeHistory.size() > 100) {
                runtimeHistory.remove(0);
            }
            runtimeHistory.add(pos);
        }

        // 模糊检查：是否最近去过 radius 范围内的点
        public boolean hasRecentlyVisitedNear(BlockPos pos, double radius) {
            double rSq = radius * radius;
            // 倒序检查，优先检查最近的足迹
            for (int i = runtimeHistory.size() - 1; i >= 0; i--) {
                BlockPos visited = runtimeHistory.get(i);
                if (visited.distanceSq(pos) < rSq) {
                    return true;
                }
            }
            return false;
        }

        public void clearRuntimeHistory() {
            runtimeHistory.clear();
        }
    }
}