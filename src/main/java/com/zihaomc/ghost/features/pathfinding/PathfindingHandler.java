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
 * 路径导航管理器
 * 结合了长距离分段启发式搜索(Heuristic Segmenting)与局部路径计算。
 * 包含热力图(Density Map)系统以避免重复路径，以及拟人化的移动控制。
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
    private static final int MAX_CALC_NODES = 10000; // 增加节点上限以匹配原始代码
    private static final int MAX_SEGMENT_LENGTH = 50;

    // 卡死检测变量
    private static int stuckTimer = 0;
    private static int collisionTimer = 0;
    private static Vec3 lastCheckPos = null;

    // --- 公共 API ---

    public static void setGlobalTarget(BlockPos target) {
        // 如果新旧目标距离过大，可视情况清理热力图缓存
        // if (globalTarget == null || globalTarget.distanceSq(target) > 10000) {
        //     cache.clearDensity(); 
        // }
        globalTarget = target;
        currentPath.clear();
        currentPathIndex = 0;
        isPathfinding = true;
        isCalculating = false;
        resetStuckTimers();
        cache.load(); // 每次设定目标时加载黑名单和密度数据
        debug(">>> 全局目标设定: " + target);
        generateNextSegment(); // 立即触发路径计算
    }

    public static void stop() {
        if (isPathfinding && DEBUG) debug("<<< 寻路停止");
        isPathfinding = false;
        currentPath.clear();
        globalTarget = null;
        isCalculating = false;
        resetKeys();
        cache.save(); // 停止时保存黑名单和密度数据
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static BlockPos getGlobalTarget() { return globalTarget; }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || mc.thePlayer == null || !isPathfinding) return;

        // 1. 更新区域热力图 (记录足迹)
        cache.incrementDensity(new BlockPos(mc.thePlayer));

        // 2. 终点到达检查
        if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) < 2.0) {
            stop();
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[Ghost] 已到达目的地！"));
            return;
        }

        // 3. 路径衔接管理
        // 如果当前没有路径，或路径即将走完，预加载下一段路径
        if (!isCalculating && (currentPath.isEmpty() || currentPathIndex >= currentPath.size() - 2)) {
            if (globalTarget != null && mc.thePlayer.getDistanceSq(globalTarget) > 2.0) {
                generateNextSegment();
            }
        }

        // 4. 卡死与异常检测
        if (checkStuck()) {
            // 标记当前死胡同并强制重算
            cache.markAsBadRegion(new BlockPos(mc.thePlayer).offset(mc.thePlayer.getHorizontalFacing()));
            currentPath.clear();
            generateNextSegment();
            return;
        }

        // 5. 执行具体的移动控制
        if (!currentPath.isEmpty() && currentPathIndex < currentPath.size()) {
            executeMovement();
        } else if (isCalculating) { // 计算中且无路径时，重置按键防止意外移动
            resetKeys();
        }
    }

    /**
     * 核心逻辑：生成下一段路径
     * 采用混合策略：
     * 1. 短距离直接使用 PathCalculator 寻路。
     * 2. 长距离使用射线扫描寻找中继点(Waypoints)，再对中继点进行 PathCalculator 验证。
     */
    private static void generateNextSegment() {
        if (isCalculating || globalTarget == null) return;
        isCalculating = true;

        final BlockPos startPos = new BlockPos(mc.thePlayer);
        final BlockPos finalTarget = globalTarget;

        pathPool.execute(() -> {
            try {
                List<BlockPos> bestSegment = null;
                double distToGlobal = startPos.distanceSq(finalTarget);

                // 策略 A: 距离目标较近时 (或可容忍的范围内)，直接尝试 PathCalculator 寻路到终点
                // 稍微增加范围，给 PathCalculator 更多机会一次性找到终点
                if (distToGlobal < (MAX_SEGMENT_LENGTH + 20) * (MAX_SEGMENT_LENGTH + 20)) {
                    bestSegment = PathCalculator.compute(startPos, finalTarget, MAX_CALC_NODES);
                }

                // 策略 B: 距离较远或直连失败时，使用启发式扫描寻找中继点
                if (bestSegment == null || bestSegment.isEmpty()) {
                    // 扫描周围可行的落脚点
                    List<BlockPos> candidates = scanForCandidates(startPos, finalTarget, distToGlobal);
                    
                    // 基于热力图和距离进行评分排序
                    sortCandidatesByHeuristic(candidates, startPos, finalTarget);

                    // 尝试评分最高的候选点，直到找到可行路径或尝试次数用尽
                    int tries = 0;
                    for (BlockPos candidate : candidates) {
                        if (tries++ > 5) break; // 仅尝试评分最高的 5 个候选点
                        
                        // 验证从当前位置是否能到达该中继点
                        List<BlockPos> segment = PathCalculator.compute(startPos, candidate, MAX_CALC_NODES / 2); // 中继点路径计算可以节点少一些
                        if (segment != null && !segment.isEmpty()) {
                            bestSegment = segment;
                            if(DEBUG) debug("选中中继点: " + candidate + " (区域热度: " + cache.getDensity(candidate) + ")");
                            break;
                        } else {
                            // 无法到达的候选点标记为无效，避免重复计算
                            cache.markAsBadRegion(candidate);
                        }
                    }
                }

                // C. 应用计算结果
                final List<BlockPos> finalPath = bestSegment;
                mc.addScheduledTask(() -> {
                    if (isPathfinding) {
                        if (finalPath != null && !finalPath.isEmpty()) {
                            currentPath = new CopyOnWriteArrayList<>(finalPath); // 替换当前路径
                            smoothPath(currentPath); // 对新路径进行平滑处理
                            currentPathIndex = 0;
                            resetStuckTimers(); // 重置卡死计时器
                        } else {
                            debugErr("路径计算失败 (可能被地形困住或无法抵达目标)");
                            // TODO: 可在此处添加随机漫步逻辑以尝试脱困
                        }
                    }
                    isCalculating = false; // 标记计算完成
                });

            } catch (Exception e) {
                e.printStackTrace();
                mc.addScheduledTask(() -> isCalculating = false); // 异常时也标记计算完成
            }
        });
    }

    // --- 地形扫描与评估系统 ---
    
    private static List<BlockPos> scanForCandidates(BlockPos start, BlockPos end, double distToGlobalSq) {
        List<BlockPos> candidates = new ArrayList<>();
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double baseAngle = Math.atan2(dz, dx);
        
        // 在目标方向的扇形区域内发射射线
        int[] angles = {0, 10, -10, 25, -25, 45, -45, 60, -60, 90, -90};
        
        for (int angleOffset : angles) {
            double rad = baseAngle + Math.toRadians(angleOffset);
            double lookDist = Math.min(Math.sqrt(distToGlobalSq), MAX_SEGMENT_LENGTH); // 搜索距离不超过分段长度
            
            BlockPos candidate = raycastFindFloor(start, rad, lookDist);
            if (candidate != null && !cache.isBadRegion(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    // 简单的射线检测，寻找指定方向远处的地面
    private static BlockPos raycastFindFloor(BlockPos start, double angle, double dist) {
        double nx = Math.cos(angle);
        double nz = Math.sin(angle);
        // 从最远处倒序回溯寻找可行点
        for (double d = dist; d > 5; d -= 4) { // 以 4 格为步长回溯
            int tx = (int)(start.getX() + nx * d);
            int tz = (int)(start.getZ() + nz * d);
            // 搜索 Y 轴寻找落脚点
            for (int yDiff = -10; yDiff <= 10; yDiff++) { // 在垂直方向 +/-10 格范围内搜索
                BlockPos p = new BlockPos(tx, start.getY() + yDiff, tz);
                if (PathCalculator.isWalkable(p)) return p;
            }
        }
        return null;
    }

    private static void sortCandidatesByHeuristic(List<BlockPos> candidates, BlockPos start, BlockPos target) {
        // 使用 Lambda 表达式和 Comparator.comparingDouble 进行排序
        candidates.sort(Comparator.comparingDouble(p -> calculateScore(p, start, target)));
    }

    // 评分公式：距离代价 + 探索惩罚(热力图) + 高度奖励
    private static double calculateScore(BlockPos pos, BlockPos start, BlockPos target) {
        double distCost = pos.distanceSq(target); // 距离目标的平方距离
        // 探索惩罚：去过的地方权重急剧增加，迫使机器人探索新区域
        double penalty = cache.getDensity(pos) * 1000.0;
        // 高度差权重：稍微倾向于高度接近目标的点
        double hDiff = Math.abs(pos.getY() - target.getY());
        return distCost + penalty + (hDiff * 10.0);
    }

    // 路径平滑处理：移除靠近玩家的冗余节点
    private static void smoothPath(List<BlockPos> path) {
        if (mc.thePlayer == null) return;
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        while (path.size() > 1) {
            BlockPos next = path.get(0);
            // 如果玩家非常接近第一个节点 (水平距离小于1格)，则移除该节点，平滑过渡到下一个
            if (playerPos.squareDistanceTo(new Vec3(next.getX()+0.5, next.getY(), next.getZ()+0.5)) < 1.0) {
                path.remove(0);
            } else {
                break;
            }
        }
    }

    // --- 移动执行系统 ---
    
    // 执行具体的移动操作和视角控制
    private static void executeMovement() {
        // 1. 更新当前路径索引
        updatePathIndex();
        if (currentPathIndex >= currentPath.size()) return;

        BlockPos nextNode = currentPath.get(currentPathIndex);
        
        // 2. 环境状态判断
        boolean inWater = mc.thePlayer.isInWater();
        boolean onLadder = isOnLadder();

        // 3. 拟人化视角控制 (Natural Looking)
        // 计算目标视点，包含微小的随机抖动 (Jitter) 以模拟人类操作
        Vec3 lookTarget;
        double jitterX = (rng.nextDouble() - 0.5) * 0.2; // +/- 0.1 格的随机偏移
        double jitterZ = (rng.nextDouble() - 0.5) * 0.2;
        double targetX = nextNode.getX() + 0.5 + jitterX;
        double targetZ = nextNode.getZ() + 0.5 + jitterZ;
        
        if (onLadder) {
            // 在梯子上视线看上方或下方
            lookTarget = new Vec3(targetX, nextNode.getY() > mc.thePlayer.posY ? nextNode.getY() + 1 : nextNode.getY(), targetZ);
        } else {
            double yDiff = nextNode.getY() - mc.thePlayer.posY;
            if (yDiff > 0.5) { // 上台阶/跳跃时稍微看高一点 (方块顶部上方)
                lookTarget = new Vec3(targetX, nextNode.getY() + 1.2, targetZ);
            } else if (yDiff < -0.5) { // 下落时看脚下 (方块表面)
                lookTarget = new Vec3(targetX, nextNode.getY() - 0.5, targetZ);
            } else { // 平地行走看前方 (玩家眼睛高度)
                lookTarget = new Vec3(targetX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), targetZ);
            }
        }

        float[] rotations = RotationUtil.getRotations(lookTarget);
        float smoothFactor = 40f + rng.nextFloat() * 10f; // 随机平滑因子，增加拟人感
        mc.thePlayer.rotationYaw = limitAngleChange(mc.thePlayer.rotationYaw, rotations[0], smoothFactor);
        mc.thePlayer.rotationPitch = limitAngleChange(mc.thePlayer.rotationPitch, rotations[1], smoothFactor);

        // 4. 键盘输入模拟
        boolean jump = false;
        boolean forward = true;
        boolean sprint = true;

        if (onLadder) {
            sprint = false;
            // 接近梯子目标高度时停止前进，避免冲过头
            if (Math.abs(nextNode.getY() - mc.thePlayer.posY) < 0.2) forward = false;
        } else if (inWater) {
            sprint = false;
            // 水中向上游
            if (nextNode.getY() >= mc.thePlayer.posY) jump = true;
        } else {
            if (mc.thePlayer.onGround) {
                // 跳跃判定：目标比玩家高超过 0.6 格
                if (nextNode.getY() > mc.thePlayer.posY + 0.6) {
                    jump = true;
                } else if (nextNode.getY() == Math.floor(mc.thePlayer.posY)) {
                    // 跑酷判定：如果水平距离远且目标点下方悬空，则跳跃疾跑
                    double dist = Math.sqrt(Math.pow(nextNode.getX() + 0.5 - mc.thePlayer.posX, 2) + Math.pow(nextNode.getZ() + 0.5 - mc.thePlayer.posZ, 2));
                    if (dist > 1.0) { // 超过 1 格水平距离可能是跑酷
                         BlockPos belowNext = nextNode.down();
                         if (!PathCalculator.isSolid(belowNext) && !PathCalculator.isLadder(belowNext)) { // 目标点下方是空的
                             jump = true;
                             sprint = true;
                         }
                    }
                }
                
                // 辅助防卡：撞墙自动跳
                if (mc.thePlayer.isCollidedHorizontally && forward) {
                    collisionTimer++;
                    if (collisionTimer > 3) jump = true; // 持续撞墙 3 tick 后跳跃
                } else {
                    collisionTimer = 0;
                }
            }
        }
        
        // 视角角度偏差过大时停止疾跑，确保转向精度
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - rotations[0]));
        if (yawDiff > 40) sprint = false;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
        mc.thePlayer.setSprinting(sprint);
    }

    // 自动更新路径索引，处理玩家实际位置与路径节点的关系
    private static void updatePathIndex() {
        Vec3 playerPos = mc.thePlayer.getPositionVector();
        int bestIndex = currentPathIndex;
        double minDistance = Double.MAX_VALUE;

        // 向前搜索最近的路径节点，防止索引滞后 (搜索当前到未来 6 个节点)
        for (int i = currentPathIndex; i < Math.min(currentPathIndex + 6, currentPath.size()); i++) {
            BlockPos node = currentPath.get(i);
            double dist = playerPos.squareDistanceTo(new Vec3(node.getX() + 0.5, node.getY() + 0.5, node.getZ() + 0.5));
            if (dist < minDistance) {
                minDistance = dist;
                bestIndex = i;
            }
        }
        currentPathIndex = bestIndex;

        // 如果非常接近当前节点 (水平距离小于 0.8 格)，就自动切换到下一个节点
        if (currentPathIndex < currentPath.size()) {
            BlockPos current = currentPath.get(currentPathIndex);
            double hDist = Math.pow(current.getX() + 0.5 - playerPos.xCoord, 2) + Math.pow(current.getZ() + 0.5 - playerPos.zCoord, 2);
            if (hDist < 0.8 * 0.8) {
                currentPathIndex++;
            }
        }
    }

    // 卡死检测：检测玩家是否长时间未移动
    private static boolean checkStuck() {
        stuckTimer++;
        if (stuckTimer > 40) { // 40 tick (2秒) 无明显位移视为卡死
            stuckTimer = 0;
            Vec3 currentPos = mc.thePlayer.getPositionVector();
            if (lastCheckPos != null) {
                double moved = currentPos.distanceTo(lastCheckPos);
                if (moved < 0.5) { // 2秒内移动距离小于0.5格
                    debugErr("检测到移动卡死，请求重算...");
                    return true;
                }
            }
            lastCheckPos = currentPos; // 更新上次检查位置
        }
        return false;
    }

    // 重置所有卡死相关的计时器和状态
    private static void resetStuckTimers() { stuckTimer = 0; collisionTimer = 0; lastCheckPos = null; }

    // 判断玩家是否在梯子或藤蔓上
    private static boolean isOnLadder() {
        if(mc.thePlayer == null) return false;
        BlockPos pos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.ladder || block == Blocks.vine;
    }

    // 限制角度变化，实现平滑旋转
    private static float limitAngleChange(float current, float target, float maxChange) { 
        float change = MathHelper.wrapAngleTo180_float(target - current); // 计算角度差并规范化到-180到180
        change = MathHelper.clamp_float(change, -maxChange, maxChange); // 限制最大变化量
        return current + change;
    }

    // 重置所有按键状态
    private static void resetKeys() { 
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
    }

    // 调试信息输出
    private static void debug(String msg) { if (DEBUG && mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "[Path] " + msg)); }
    private static void debugErr(String msg) { if (DEBUG && mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[Path] " + msg)); }

    // ==========================================
    //           PathCache (密度热力图系统)
    // ==========================================
    private static class PathCache {
        private final Set<String> blacklistedRegions = new HashSet<>(); // 无法通过区域的黑名单
        private final ConcurrentHashMap<Long, Integer> densityMap = new ConcurrentHashMap<>(); // 区域访问密度热力图
        private final File cacheFile = new File(mc.mcDataDir, "ghost_path_blacklist.txt"); // 黑名单文件

        // 计算区域ID (用于黑名单，3x3x3 的大区域)
        private String getRegionKeyStr(BlockPos pos) { return (pos.getX()/3) + "," + (pos.getY()/3) + "," + (pos.getZ()/3); }
        
        // 使用位运算生成区块 Key (用于热力图，8x8x8 的区域，更精细)
        private long getDensityKey(BlockPos pos) { 
            long x = pos.getX() >> 3; // 除以 8
            long y = pos.getY() >> 3; 
            long z = pos.getZ() >> 3;
            // 简单Hash，将 x, y, z 编码到一个 long 中
            return (x & 0x1FFFFF) | ((z & 0x1FFFFF) << 21) | ((y & 0xFF) << 42); 
        }

        // 从文件加载黑名单
        public void load() { 
            blacklistedRegions.clear();
            if (!cacheFile.exists()) return;
            try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) { 
                String l; while ((l = r.readLine()) != null) blacklistedRegions.add(l.trim()); 
            } catch (IOException e) { e.printStackTrace(); } 
        }

        // 保存黑名单到文件
        public void save() { 
            try (PrintWriter w = new PrintWriter(cacheFile)) { 
                for (String rk : blacklistedRegions) w.println(rk); 
            } catch (IOException e) { e.printStackTrace(); } 
        }

        // 标记一个区域为“坏区域” (无法通过)
        public void markAsBadRegion(BlockPos pos) { blacklistedRegions.add(getRegionKeyStr(pos)); }
        // 检查一个区域是否为“坏区域”
        public boolean isBadRegion(BlockPos pos) { return blacklistedRegions.contains(getRegionKeyStr(pos)); }

        // 增加区域的访问密度
        public void incrementDensity(BlockPos pos) { densityMap.merge(getDensityKey(pos), 1, Integer::sum); }
        // 获取区域的访问密度
        public int getDensity(BlockPos pos) { return densityMap.getOrDefault(getDensityKey(pos), 0); }
        // 清空热力图 (可选，用于新任务开始时)
        public void clearDensity() { densityMap.clear(); }
    }


    // =======================================================
    //            路径计算器 (最终修复版，支持门/栅栏门)
    // =======================================================
    private static class PathCalculator {
        // 移动代价常量
        private static final double COST_WALK = 1.0;
        private static final double COST_DIAGONAL = 1.414;
        private static final double COST_JUMP = 2.0;
        private static final double COST_FALL = 1.0;
        private static final double COST_PARKOUR = 4.0; // 跑酷代价更高
        private static final double COST_LADDER = 1.2; // 梯子移动代价

        /**
         * 计算从起点到终点的最佳路径 (基于 A* 算法)
         * @param start 起始方块位置
         * @param end 目标方块位置
         * @param limit 最大搜索节点数，防止计算耗时过长
         * @return 路径方块列表，如果无法到达则返回 null
         */
        public static List<BlockPos> compute(BlockPos start, BlockPos end, int limit) {
            PriorityQueue<Node> openSet = new PriorityQueue<>(); // 待探索节点
            Map<BlockPos, Node> allNodes = new HashMap<>(); // 已探索节点

            Node startNode = new Node(start, null, 0, getHeuristic(start, end));
            openSet.add(startNode);
            allNodes.put(start, startNode);

            int iterations = 0;
            Node bestNode = startNode; // 记录离目标最近的节点

            while (!openSet.isEmpty() && iterations < limit) {
                iterations++;
                Node current = openSet.poll();

                // 更新最近节点：如果当前节点离终点更近，更新 bestNode
                if (getHeuristic(current.pos, end) < getHeuristic(bestNode.pos, end)) {
                    bestNode = current;
                }

                // 到达终点条件：水平距离小于2格
                if (current.pos.distanceSq(end) < 2.0) {
                    return reconstructPath(current);
                }

                // 探索邻居节点
                for (Node neighborProto : getNeighbors(current.pos)) { // 注意：这里不再传入end，启发式成本在Node构造时计算
                    double newGCost = current.gCost + neighborProto.gCost; // 计算从起点到邻居的总实际成本

                    Node existingNode = allNodes.get(neighborProto.pos);

                    // 如果邻居节点未被探索，或找到了更短的路径
                    if (existingNode == null || newGCost < existingNode.gCost) {
                        double hCost = getHeuristic(neighborProto.pos, end); // 启发式成本
                        if (existingNode == null) {
                            Node newNode = new Node(neighborProto.pos, current, newGCost, hCost);
                            allNodes.put(neighborProto.pos, newNode);
                            openSet.add(newNode);
                        } else {
                            // 更新现有节点的成本和父节点
                            openSet.remove(existingNode); // 从优先队列中移除旧的
                            existingNode.parent = current;
                            existingNode.gCost = newGCost;
                            existingNode.hCost = hCost;
                            existingNode.fCost = newGCost + hCost;
                            openSet.add(existingNode); // 重新加入，确保排序正确
                        }
                    }
                }
            }
            // 如果计算耗尽仍未到达终点，返回当前能到达的离终点最近的路径
            return (bestNode != startNode) ? reconstructPath(bestNode) : null;
        }

        // 从终点节点回溯重建路径
        private static List<BlockPos> reconstructPath(Node node) {
            List<BlockPos> path = new ArrayList<>();
            while (node != null) { 
                path.add(node.pos); 
                node = node.parent; 
            }
            Collections.reverse(path); // 路径从起点到终点
            return path;
        }

        // 启发式函数 (曼哈顿距离或欧几里得距离)
        private static double getHeuristic(BlockPos pos, BlockPos target) {
            // 使用欧几里得距离 (直线距离) 通常效果更好
            return Math.sqrt(pos.distanceSq(target));
        }

        /**
         * 获取给定位置的所有可行邻居节点
         * 包含了平走、跳跃、下落、跑酷、对角线以及梯子等复杂移动逻辑。
         */
        private static List<Node> getNeighbors(BlockPos pos) {
            List<Node> moves = new ArrayList<>();

            // A. 水平方向 (N, S, E, W)
            for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                BlockPos offset = pos.offset(dir); // 水平偏移一格

                // 1. 平走
                if (isWalkable(offset)) {
                    moves.add(new Node(offset, null, COST_WALK, 0));
                }
                // 2. 跳跃 (向上1格)
                // 检查当前位置是否有足够的头部空间来起跳
                else if (isWalkable(offset.up()) && canGoThrough(pos.up(2))) {
                    moves.add(new Node(offset.up(), null, COST_JUMP, 0));
                }
                // 3. 下落 (最多3格)
                else {
                    for (int i = 1; i <= 3; i++) { // 尝试向下最多 3 格
                        BlockPos down = offset.down(i);
                        if (isWalkable(down)) {
                            boolean blocked = false;
                            // 检查下落路径是否有阻挡
                            for (int j = 0; j < i; j++) if (!canGoThrough(offset.down(j))) blocked = true;
                            if (!blocked) {
                                moves.add(new Node(down, null, COST_FALL, 0));
                                break; // 找到第一个可落脚的就停止向下搜索
                            }
                        }
                    }
                }

                // 4. 跑酷 (水平跳过1格空隙)
                // 同样检查起跳的头部空间
                if (canGoThrough(offset) && canGoThrough(offset.up())) { // 目标水平一格是空，且头部空间足够
                    BlockPos far = offset.offset(dir); // 目标水平两格
                    if (isWalkable(far) && canGoThrough(pos.up(2))) { // 目标水平两格可站立，且起跳头部空间足够
                        moves.add(new Node(far, null, COST_PARKOUR, 0));
                    }
                }
            }

            // B. 对角线方向
            BlockPos[] diags = {pos.add(1,0,1), pos.add(1,0,-1), pos.add(-1,0,1), pos.add(-1,0,-1)};
            for (BlockPos diag : diags) {
                if (isWalkable(diag)) {
                    // 确保对角线移动不会被中间的墙角卡住 (即通过两个相邻的水平方块都得是可穿过的)
                    if (canGoThrough(new BlockPos(diag.getX(), pos.getY(), pos.getZ())) ||
                        canGoThrough(new BlockPos(pos.getX(), pos.getY(), diag.getZ()))) {
                        moves.add(new Node(diag, null, COST_DIAGONAL, 0));
                    }
                }
            }

            // C. 梯子/藤蔓逻辑 (垂直移动及附着)
            if (isLadder(pos)) {
                // 如果在梯子上，可以向上或向下移动 (目标点是梯子或可站立)
                if (isLadder(pos.up()) || isWalkable(pos.up())) moves.add(new Node(pos.up(), null, COST_LADDER, 0));
                if (isLadder(pos.down()) || isWalkable(pos.down())) moves.add(new Node(pos.down(), null, COST_LADDER, 0));
            } else {
                // 如果不在梯子上，检查旁边是否有梯子可以附着
                for (EnumFacing dir : EnumFacing.HORIZONTALS) {
                    BlockPos offset = pos.offset(dir);
                    if (isLadder(offset)) moves.add(new Node(offset, null, COST_WALK, 0)); // 附着到梯子上视为平走代价
                }
            }

            return moves;
        }
        
        // --- 方块状态判断 (Helper Methods) ---

        // 判断一个方块位置是否可供玩家站立和移动
        public static boolean isWalkable(BlockPos pos) { 
            return isSolid(pos.down())      // 脚下必须是实体方块
                   && canGoThrough(pos)     // 自身位置必须可穿过
                   && canGoThrough(pos.up()) // 头部位置必须可穿过 (两格高)
                   && isSafe(pos);          // 位置必须安全 (无岩浆、仙人掌等)
        }

        // 判断一个方块位置是否为实体方块 (可落脚)
        public static boolean isSolid(BlockPos pos) { 
            IBlockState state = mc.theWorld.getBlockState(pos);
            Block block = state.getBlock();
            // 液体、梯子、藤蔓不算实体方块
            if (block instanceof BlockLiquid || block == Blocks.ladder || block == Blocks.vine) return false;
            // 检查方块材质是否为固体，且是否可碰撞
            return block.getMaterial().isSolid() && block.isCollidable(); 
        }
        
        /**
         * 判断一个方块位置是否可供玩家穿过 (空气，或无碰撞箱)
         * [关键修复] 增加了对门、栅栏门、活板门 "OPEN" 状态的检查
         */
        public static boolean canGoThrough(BlockPos pos) {
            IBlockState state = mc.theWorld.getBlockState(pos);
            Block block = state.getBlock();

            // 优先处理可开启方块的特殊情况
            if (block instanceof BlockFenceGate && state.getValue(BlockFenceGate.OPEN)) {
                return true; // 栅栏门打开时可穿过
            }
            if (block instanceof BlockDoor && state.getValue(BlockDoor.OPEN)) {
                return true; // 门打开时可穿过
            }
            if (block instanceof BlockTrapDoor && state.getValue(BlockTrapDoor.OPEN)) {
                return true; // 活板门打开时可穿过
            }
            
            // 沿用之前的通用判断逻辑：
            // 1. 无碰撞箱 (如空气，草，花)
            // 2. 是空气方块
            // 3. 是梯子或藤蔓 (可攀爬但不阻碍通行)
            // 4. 是液体
            // 5. 非固体材质 (如蜘蛛网)
            return block.getCollisionBoundingBox(mc.theWorld, pos, state) == null 
                || block == Blocks.air 
                || block == Blocks.ladder 
                || block == Blocks.vine 
                || block instanceof BlockLiquid 
                || !block.getMaterial().isSolid();
        }
        
        // 判断一个方块位置是否为梯子或藤蔓
        public static boolean isLadder(BlockPos pos) { 
            Block block = mc.theWorld.getBlockState(pos).getBlock(); 
            return block == Blocks.ladder || block == Blocks.vine; 
        }

        // 判断一个方块位置是否安全 (无岩浆、仙人掌、火焰等致命方块)
        public static boolean isSafe(BlockPos pos) { 
            Block in = mc.theWorld.getBlockState(pos).getBlock(); // 自身位置的方块
            Block down = mc.theWorld.getBlockState(pos.down()).getBlock(); // 脚下位置的方块
            return !(in == Blocks.lava || in == Blocks.fire || down == Blocks.lava || down == Blocks.cactus); // 岩浆、火、仙人掌不安全
        }

        // A* 算法的节点类
        private static class Node implements Comparable<Node> {
            BlockPos pos;       // 节点位置
            Node parent;        // 父节点，用于回溯路径
            double gCost;       // 从起点到当前节点的实际代价
            double hCost;       // 从当前节点到终点的启发式代价
            double fCost;       // 总评估代价 (gCost + hCost)

            public Node(BlockPos p, Node pa, double g, double h) { 
                pos = p; 
                parent = pa; 
                gCost = g; 
                hCost = h; 
                fCost = g + h; 
            }

            // 根据 fCost 进行比较，fCost 相同则比较 hCost (更倾向于离终点近的)
            @Override public int compareTo(Node o) { 
                int cmp = Double.compare(this.fCost, o.fCost); 
                return (cmp == 0) ? Double.compare(this.hCost, o.hCost) : cmp; 
            }
        }
    }
}