package com.zihaomc.ghost.features.ghostblock.handlers;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.List;

public interface ICommandHandler {
    void processCommand(ICommandSender sender, WorldClient world, String[] args) throws CommandException;
    List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos);
}