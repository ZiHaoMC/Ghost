package com.zihaomc.ghost.features.bedrockminer.task;

import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.features.bedrockminer.data.PistonPowerInfo;
import com.zihaomc.ghost.features.bedrockminer.data.Pair;
import com.zihaomc.ghost.features.bedrockminer.enums.PowerBlockType;
import com.zihaomc.ghost.features.bedrockminer.enums.TaskState;
import com.zihaomc.ghost.features.bedrockminer.finder.BlockFinder;
import com.zihaomc.ghost.features.bedrockminer.utils.BlockUtils;
import com.zihaomc.ghost.features.bedrockminer.utils.InventoryUtils;
import com.zihaomc.ghost.features.bedrockminer.utils.RotationUtils;
import com.zihaomc.ghost.mixins.accessors.PlayerControllerMPAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class Task implements Comparable<Task> {
    public final BlockPos targetPos;
    private int pistonIndex, dependBlockIndex, redstoneTorchIndex, leverIndex, pickaxeIndex;
    private PistonPowerInfo pistonPowerInfo;
    private int waitTicks = 0;
    private float blockBreakingDelta;
    private boolean isMining = false;
    public TaskState state = TaskState.Start;

    public Task(BlockPos targetPos) { this.targetPos = Objects.requireNonNull(targetPos); }

    private int getWaitTicksAfterDecrement() { return --waitTicks; }
    private void setWaitTicks(int ticks) {
        if (this.waitTicks > 0) throw new IllegalStateException("still waiting");
        this.waitTicks = ticks + GhostConfig.getPingSpikeThreshold();
    }

    public boolean tick(TaskManager manager) {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityPlayerSP player = mc.thePlayer;
        final WorldClient world = mc.theWorld;
        if (player == null || world == null) return false;

        final PlayerControllerMP interactionManager = mc.playerController;
        final RotationUtils rotationUtils = manager.getRotationUtils();
        final InventoryPlayer inventory = player.inventory;

        loop:
        while (true) {
            switch (state) {
                case Start: {
                    if (player.openContainer != player.inventoryContainer) { break loop; }
                    if (!BlockUtils.playerCanTouchBlock(player, targetPos, false)) { break loop; }

                    pistonIndex = InventoryUtils.findFirstItemInHotbar(inventory, Item.getItemFromBlock(Blocks.piston));
                    if (pistonIndex == -1 && GhostConfig.isHeadlessPistonMode()) {
                        pistonIndex = InventoryUtils.findFirstItemInHotbar(inventory, Item.getItemFromBlock(Blocks.sticky_piston));
                    }
                    if (pistonIndex == -1) break loop;

                    dependBlockIndex = InventoryUtils.findFirstItemInHotbar(inventory, (stack) -> GhostConfig.getDependBlockWhitelist().contains(Block.getBlockFromItem(stack.getItem())));
                    pickaxeIndex = InventoryUtils.findBestItemInHotbar(inventory, InventoryUtils::isPickaxe,
                            Comparator.comparingDouble(stack -> InventoryUtils.getBlockBreakingDelta(player, Blocks.piston.getDefaultState(), stack)));
                    
                    boolean canInstantMinePiston = pickaxeIndex != -1 && InventoryUtils.getBlockBreakingDelta(player, Blocks.piston.getDefaultState(), inventory.getStackInSlot(pickaxeIndex)) >= 0.7;

                    redstoneTorchIndex = canInstantMinePiston ? InventoryUtils.findFirstItemInHotbar(inventory, Item.getItemFromBlock(Blocks.redstone_torch)) : -1;
                    leverIndex = InventoryUtils.findFirstItemInHotbar(inventory, Item.getItemFromBlock(Blocks.lever));
                    
                    PowerBlockType powerBlockUsage = PowerBlockType.of(redstoneTorchIndex != -1, leverIndex != -1);
                    if (powerBlockUsage == null) break loop;

                    ArrayList<Pair<BlockPos, EnumFacing>> pistonList = new ArrayList<>();
                    BlockFinder.findStablePistons(world, targetPos, pistonList);
                    ArrayList<PistonPowerInfo> pistonPowerInfos = new ArrayList<>();
                    BlockFinder.findPowerBlockForPiston(world, powerBlockUsage, pistonList, pistonPowerInfos, dependBlockIndex != -1);

                    if (pistonPowerInfos.isEmpty()) break loop;
                    
                    this.pistonPowerInfo = pistonPowerInfos.get(0);
                    state = TaskState.PlaceBlocksWithoutChecks;
                    continue loop;
                }
                case PlaceBlocksWithoutChecks: {
                    if (player.openContainer != player.inventoryContainer) { retry(); break loop; }
                    
                    // **核心修正**: 所有放置操作都使用 interactWithBlock
                    interactWithBlock(interactionManager, player, world, pistonIndex, pistonPowerInfo.pistonPos, EnumFacing.DOWN);
                    
                    BlockPos dependBlockPos = pistonPowerInfo.powerBlockPos.offset(pistonPowerInfo.powerBlockFace.getOpposite());
                    if (BlockUtils.isReplaceable(world.getBlockState(dependBlockPos), world, dependBlockPos)) {
                        if(dependBlockIndex == -1) { retry(); break loop; }
                        interactWithBlock(interactionManager, player, world, dependBlockIndex, dependBlockPos, EnumFacing.DOWN);
                    }
                    
                    if (pistonPowerInfo.getPowerBlockType().isRedstoneTorch() && redstoneTorchIndex != -1) {
                        leverIndex = -1;
                        interactWithBlock(interactionManager, player, world, redstoneTorchIndex, pistonPowerInfo.powerBlockPos, pistonPowerInfo.powerBlockFace);
                    } else if (pistonPowerInfo.getPowerBlockType().isLever() && leverIndex != -1) {
                        redstoneTorchIndex = -1;
                        interactWithBlock(interactionManager, player, world, leverIndex, pistonPowerInfo.powerBlockPos, pistonPowerInfo.powerBlockFace);
                        interactWithBlock(interactionManager, player, world, pistonPowerInfo.powerBlockPos, pistonPowerInfo.powerBlockFace);
                    } else { shouldNotReachHere(); }
                    state = TaskState.SelectPickaxeAndReadyMine;
                    continue loop;
                }
                case SelectPickaxeAndReadyMine: {
                    if (pickaxeIndex != -1) {
                        inventory.currentItem = pickaxeIndex;
                        ((PlayerControllerMPAccessor) interactionManager).invokeSyncCurrentPlayItem();
                    }
                    
                    blockBreakingDelta = InventoryUtils.getBlockBreakingDelta(player, world.getBlockState(pistonPowerInfo.pistonPos), inventory.getCurrentItem());
                    if (blockBreakingDelta < 0.7 && redstoneTorchIndex != -1) { retry(); break loop; }
                    
                    if (BlockUtils.playerCanTouchBlock(player, pistonPowerInfo.pistonPos, true) && !isMining) {
                        isMining = true;
                        interactionManager.clickBlock(pistonPowerInfo.pistonPos, EnumFacing.DOWN);
                    } else { break loop; }
                    
                    rotationUtils.markKeepRotation();
                    state = TaskState.WaitForPistonExtend;
                    setWaitTicks(blockBreakingDelta >= 0.7 ? 1 : (int) Math.ceil(0.7 / blockBreakingDelta) + 1);
                    break loop;
                }
                case WaitForPistonExtend: {
                    if (getWaitTicksAfterDecrement() > 0) {
                        rotationUtils.markKeepRotation();
                        if (pickaxeIndex != -1) inventory.currentItem = pickaxeIndex;
                        break loop;
                    }
                    state = TaskState.Execute;
                    continue loop;
                }
                case Execute: {
                    if (pickaxeIndex != -1) inventory.currentItem = pickaxeIndex;
                    if (!BlockUtils.playerCanTouchBlock(player, pistonPowerInfo.pistonPos, true)) break loop;

                    if (redstoneTorchIndex != -1) {
                        interactionManager.onPlayerDamageBlock(pistonPowerInfo.powerBlockPos, EnumFacing.DOWN);
                    } else {
                        interactWithBlock(interactionManager, player, world, pistonPowerInfo.powerBlockPos, pistonPowerInfo.powerBlockFace);
                    }

                    if (blockBreakingDelta >= 1.0F) {
                        interactionManager.onPlayerDamageBlock(pistonPowerInfo.pistonPos, EnumFacing.DOWN);
                        if (!world.isAirBlock(pistonPowerInfo.pistonPos)) {
                            world.setBlockToAir(pistonPowerInfo.pistonPos);
                        }
                    } else {
                        PlayerControllerMPAccessor controllerAccessor = (PlayerControllerMPAccessor) interactionManager;
                        while (controllerAccessor.getCurBlockDamageMP() < 1.0F) {
                            interactionManager.onPlayerDamageBlock(pistonPowerInfo.pistonPos, EnumFacing.DOWN);
                        }
                    }
                    isMining = false;

                    if (GhostConfig.isHeadlessPistonMode()) {
                         if (redstoneTorchIndex != -1) {
                            interactWithBlock(interactionManager, player, world, redstoneTorchIndex, pistonPowerInfo.powerBlockPos, pistonPowerInfo.powerBlockFace);
                        } else {
                            interactWithBlock(interactionManager, player, world, pistonPowerInfo.powerBlockPos, pistonPowerInfo.powerBlockFace);
                        }
                    }

                    interactWithBlock(interactionManager, player, world, pistonIndex, pistonPowerInfo.pistonPos, EnumFacing.DOWN);

                    state = TaskState.Finished;
                    return true;
                }
                case Finished:
                default:
                    return false;
            }
        }
        return false;
    }

    /**
     * **核心修正**：
     * 这是模拟放置和交互的唯一正确方法。
     * 它会临时切换玩家手中的物品，然后调用 onPlayerRightClick。
     */
    private void interactWithBlock(PlayerControllerMP controller, EntityPlayerSP player, WorldClient world, int slot, BlockPos pos, EnumFacing side) {
        if (slot < 0 || slot >= InventoryPlayer.getHotbarSize()) return;
        
        int originalSlot = player.inventory.currentItem;
        player.inventory.currentItem = slot;
        ItemStack stackToUse = player.inventory.getStackInSlot(slot);

        // 使用正确的 onPlayerRightClick 方法
        controller.onPlayerRightClick(player, world, stackToUse, pos, side, new Vec3(0.5, 0.5, 0.5));
        
        player.inventory.currentItem = originalSlot;
    }
    
    // **核心修正**: 重载一个不切换物品的 interactWithBlock 方法，用于与已放置的方块交互（如拉杆）
    private void interactWithBlock(PlayerControllerMP controller, EntityPlayerSP player, WorldClient world, BlockPos pos, EnumFacing side) {
        controller.onPlayerRightClick(player, world, player.inventory.getCurrentItem(), pos, side, new Vec3(0.5, 0.5, 0.5));
    }

    private void retry() {
        this.state = TaskState.Start;
        if (isMining) {
            isMining = false;
            Minecraft.getMinecraft().playerController.resetBlockRemoving();
        }
    }

    private static void shouldNotReachHere() { throw new AssertionError(); }

    @Override public int compareTo(Task o) { return this.targetPos.compareTo(o.targetPos); }
    @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; Task task = (Task) o; return targetPos.equals(task.targetPos); }
    @Override public int hashCode() { return Objects.hash(targetPos); }
}