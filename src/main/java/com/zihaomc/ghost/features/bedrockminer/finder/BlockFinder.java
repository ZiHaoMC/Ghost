package com.zihaomc.ghost.features.bedrockminer.finder;

import com.zihaomc.ghost.features.bedrockminer.data.Pair;
import com.zihaomc.ghost.features.bedrockminer.data.PistonPowerInfo;
import com.zihaomc.ghost.features.bedrockminer.enums.PowerBlockType;
import com.zihaomc.ghost.features.bedrockminer.utils.BlockUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import java.util.*;

import java.util.*;

public final class BlockFinder {
    private BlockFinder() {}

    public static final EnumFacing[] DIRECTIONS = EnumFacing.values();
    public static final EnumFacing[][] DIRECTIONS_WITHOUT;
    
    static {
        DIRECTIONS_WITHOUT = new EnumFacing[6][5];
        for (EnumFacing facing : DIRECTIONS) {
            List<EnumFacing> others = new ArrayList<>(Arrays.asList(DIRECTIONS));
            others.remove(facing);
            DIRECTIONS_WITHOUT[facing.getIndex()] = others.toArray(new EnumFacing[0]);
        }
    }

    public static void findStablePistons(World world, BlockPos targetPos, List<Pair<BlockPos, EnumFacing>> possiblePistonLocations) {
        for (EnumFacing pistonDirection : DIRECTIONS) {
            BlockPos pistonPos = targetPos.offset(pistonDirection.getOpposite());
            
            if (!world.isBlockLoaded(pistonPos) || !BlockUtils.isReplaceable(world.getBlockState(pistonPos), world, pistonPos)) {
                continue;
            }

            if (isPistonPlaceSafe(world, pistonPos, pistonDirection)) {
                possiblePistonLocations.add(new Pair<>(pistonPos, pistonDirection));
            }
        }
    }

    public static boolean isPistonPlaceSafe(World world, BlockPos pistonPos, EnumFacing pistonFace) {
        for (EnumFacing facing : DIRECTIONS) {
            if (facing != pistonFace && world.isSidePowered(pistonPos.offset(facing), facing)) {
                return false;
            }
        }
        return !world.isBlockPowered(pistonPos);
    }

    public static void findPowerBlockForPiston(World world, PowerBlockType powerBlockUsage, List<Pair<BlockPos, EnumFacing>> possiblePistonLocations, List<PistonPowerInfo> possiblePistonPowerInfos, boolean hasDependBlock) {
        final boolean isRedstoneTorch = powerBlockUsage.isRedstoneTorch();
        final boolean isLever = powerBlockUsage.isLever();
        final LinkedHashMap<PistonPowerInfo, PistonPowerInfo> results = new LinkedHashMap<>();

        for (Pair<BlockPos, EnumFacing> pistonLocation : possiblePistonLocations) {
            BlockPos pistonPos = pistonLocation.first;
            EnumFacing pistonFace = pistonLocation.second;

            for (EnumFacing facing : DIRECTIONS) {
                // QC Powering check
                BlockPos powerSourcePos = pistonPos.offset(facing).up();
                findPowerOptions(world, powerSourcePos, pistonPos, pistonFace, isRedstoneTorch, isLever, results, hasDependBlock);

                // Direct Powering check
                powerSourcePos = pistonPos.offset(facing);
                findPowerOptions(world, powerSourcePos, pistonPos, pistonFace, isRedstoneTorch, isLever, results, hasDependBlock);
            }
        }
        possiblePistonPowerInfos.addAll(results.values());
    }

    private static void findPowerOptions(World world, BlockPos posToPower, BlockPos pistonPos, EnumFacing pistonFace, boolean canUseTorch, boolean canUseLever, Map<PistonPowerInfo, PistonPowerInfo> results, boolean hasDependBlock) {
        if (!world.isBlockLoaded(posToPower) || !BlockUtils.isReplaceable(world.getBlockState(posToPower), world, posToPower)) {
            return;
        }

        for (EnumFacing attachFace : DIRECTIONS) {
            BlockPos attachBlock = posToPower.offset(attachFace.getOpposite());
            if (!attachBlock.equals(pistonPos) && world.isBlockLoaded(attachBlock)) {
                
                IBlockState attachState = world.getBlockState(attachBlock);
                boolean canAttach = attachState.getBlock().isSideSolid(world, attachBlock, attachFace);

                if (!canAttach && hasDependBlock && BlockUtils.isReplaceable(attachState, world, attachBlock)) {
                    canAttach = true; // We can place a depend block here
                }
                
                if (canAttach) {
                    boolean torchValid = canUseTorch && attachFace != EnumFacing.DOWN; // Torch can't be on the ceiling
                    boolean leverValid = canUseLever;
                    
                    if (torchValid || leverValid) {
                        PowerBlockType type = PowerBlockType.of(torchValid, leverValid);
                        PistonPowerInfo info = new PistonPowerInfo(pistonPos, pistonFace, posToPower, attachFace, type);
                        if (!results.containsKey(info)) {
                           results.put(info, info);
                        }
                    }
                }
            }
        }
    }
}