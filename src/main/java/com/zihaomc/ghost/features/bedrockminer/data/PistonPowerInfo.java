package com.zihaomc.ghost.features.bedrockminer.data;

import com.zihaomc.ghost.features.bedrockminer.enums.PowerBlockType;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import java.util.Objects;

public final class PistonPowerInfo {
    public final BlockPos pistonPos;
    public final EnumFacing pistonFace;
    public final BlockPos powerBlockPos;
    public final EnumFacing powerBlockFace;
    private final PowerBlockType powerBlockType;

    public PistonPowerInfo(BlockPos pistonPos, EnumFacing pistonFace, BlockPos powerBlockPos, EnumFacing powerBlockFace, PowerBlockType powerBlockType) {
        this.pistonPos = Objects.requireNonNull(pistonPos);
        this.pistonFace = Objects.requireNonNull(pistonFace);
        this.powerBlockPos = Objects.requireNonNull(powerBlockPos);
        this.powerBlockFace = Objects.requireNonNull(powerBlockFace);
        this.powerBlockType = powerBlockType;
    }

    public PowerBlockType getPowerBlockType() { return powerBlockType; }
    public static PistonPowerInfo of(BlockPos pistonPos, EnumFacing pistonFace, BlockPos powerBlockPos, EnumFacing powerBlockFace, PowerBlockType powerBlockType) {
        return new PistonPowerInfo(pistonPos, pistonFace, powerBlockPos, powerBlockFace, powerBlockType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PistonPowerInfo that = (PistonPowerInfo) o;
        return pistonPos.equals(that.pistonPos) && pistonFace == that.pistonFace && powerBlockPos.equals(that.powerBlockPos) && powerBlockFace == that.powerBlockFace;
    }

    @Override
    public int hashCode() { return Objects.hash(pistonPos, pistonFace, powerBlockPos, powerBlockFace); }

    @Override
    public String toString() {
        return "PistonPowerInfo{" + "pistonPos=" + pistonPos + ", pistonFace=" + pistonFace + ", powerBlockPos=" + powerBlockPos + ", powerBlockFace=" + powerBlockFace + ", powerBlockType=" + powerBlockType + '}';
    }
}