package com.zihaomc.ghost.features.autocraft;

import com.zihaomc.ghost.LangUtil;
import com.zihaomc.ghost.config.GhostConfig;
import com.zihaomc.ghost.utils.LogUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 自动合成功能的核心处理器。
 * 使用状态机来管理从获取材料到合成物品的全过程。
 */
public class AutoCraftHandler {

    private enum State {
        IDLE,
        STARTING,
        CHECK_SUPPLIES,
        GET_SUPPLIES,
        WAITING_FOR_STASH_MESSAGE, // 等待服务器/pickupstash命令的确认消息
        CHECK_SUPPLIES_AFTER_STASH, // 收到消息后进行最终检查
        OPEN_MENU,
        WAIT_FOR_MENU_GUI,
        CLICK_CRAFT_TABLE,
        WAIT_FOR_CRAFT_GUI,
        DO_CRAFT,
        WAIT_FOR_ITEM_PLACEMENT,
        WAIT_FOR_CRAFT_RESULT,
        TAKE_PRODUCT,
        WAIT_FOR_SLOT_CLEAR
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean active = false;
    private static State currentState = State.IDLE;
    private static State lastState = State.IDLE;
    private static int tickCounter = 0;
    private static int timeoutCounter = 0;
    
    /**
     * 追踪当前循环中已经放入合成槽的材料数量。
     */
    private static int ingredientsPlaced = 0;

    private static int slotToWatch = -1;
    private static int stackSizeToWatch = -1;
    
    private static int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    /**
     * 当前正在执行的合成配方。
     */
    private static AutoCraftRecipe activeRecipe = null;

    private static final String CRAFT_GUI_NAME = "Craft Item";
    private static final String SKYBLOCK_MENU_NAME = "SkyBlock Menu";

    private static final int CRAFT_RESULT_SLOT = 23;
    private static final int CRAFT_TABLE_SLOT = 31;
    private static final int WAIT_TIMEOUT_TICKS = 60;
    private static final int PLACEMENT_TIMEOUT_TICKS = 40;
    private static final int STASH_MESSAGE_TIMEOUT_TICKS = 80; // 等待/pickupstash消息的超时时间，4秒
    private static final int INVENTORY_SYNC_TIMEOUT_TICKS = 60; // 取出物品后等待库存同步的最大时间，3秒

    /**
     * 根据指定的配方启动自动合成。
     * @param recipe 要执行的配方。
     */
    public static void start(AutoCraftRecipe recipe) {
        if (currentState != State.IDLE) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.status.running")));
            return;
        }
        active = true;
        activeRecipe = recipe;
        retryCount = 0;
        ingredientsPlaced = 0;
        setState(State.STARTING);
        tickCounter = 0;
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.autocraft.status.enabled_for", activeRecipe.recipeKey)));
        LogUtil.info("[AutoCraft] Service started for recipe: " + activeRecipe.recipeKey);
    }

    /**
     * 停止自动合成。
     */
    public static void stop() {
        if (currentState == State.IDLE) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.status.not_running")));
            return;
        }
        if (mc.currentScreen != null) {
            mc.thePlayer.closeScreen();
        }
        active = false;
        activeRecipe = null;
        setState(State.IDLE);
        tickCounter = 0;
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.autocraft.status.disabled")));
        LogUtil.info("[AutoCraft] Service stopped.");
    }

    private static void setState(State newState) {
        lastState = currentState;
        currentState = newState;
        if (lastState != newState) {
             LogUtil.info("[AutoCraft] State change: " + lastState + " -> " + newState);
        }
    }

    private static void handleRecoverableError(String reason) {
        retryCount++;
        LogUtil.warn("[AutoCraft] Recoverable error: " + reason + ". Retry attempt " + retryCount + "/" + MAX_RETRIES);
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "[AutoCraft] An error occurred: " + reason + ". Attempting to recover... (" + retryCount + "/" + MAX_RETRIES + ")"));

        if (retryCount > MAX_RETRIES) {
            LogUtil.error("[AutoCraft] Max retries reached. Stopping service.");
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "[AutoCraft] Recovery failed. Stopping."));
            stop();
            return;
        }

        if (mc.currentScreen != null) {
            mc.thePlayer.closeScreen();
        }
        setDelay(20);
        setState(State.CHECK_SUPPLIES);
    }
    
    /**
     * 检查一个物品栈是否是当前配方所需的输入材料。
     * @param stack 要检查的物品栈。
     * @return 如果是输入材料则返回 true。
     */
    private static boolean isIngredient(ItemStack stack) {
        if (activeRecipe == null || stack == null) return false;
        String displayName = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        return activeRecipe.ingredientDisplayName.equals(displayName);
    }

    private static boolean isBarrier(ItemStack stack) {
        return stack != null && stack.getItem() == Item.getItemFromBlock(Blocks.barrier);
    }

    /**
     * 监听聊天消息事件，用于捕捉/pickupstash的完成信号。
     */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (currentState != State.WAITING_FOR_STASH_MESSAGE) {
            return;
        }
        String message = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (message.contains("You picked up") && message.contains("from your material stash")) {
            LogUtil.info("[AutoCraft] Stash pickup confirmation message received.");
            // 收到消息后，重置超时计数器，并设置短暂延迟后进入检查状态
            // 这里给予足够的时间（如3秒）让客户端同步库存
            timeoutCounter = INVENTORY_SYNC_TIMEOUT_TICKS; 
            setDelay(5); 
            setState(State.CHECK_SUPPLIES_AFTER_STASH);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active || mc.thePlayer == null || mc.theWorld == null || activeRecipe == null) {
            return;
        }
        
        // setState(currentState); // 移除此行，避免日志刷屏

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        try {
            switch (currentState) {
                case STARTING:
                    setState(State.CHECK_SUPPLIES);
                    break;

                case CHECK_SUPPLIES:
                    if (mc.currentScreen != null) { 
                        LogUtil.info("[AutoCraft] Found open screen, closing it before checking supplies.");
                        mc.thePlayer.closeScreen(); 
                        setDelay(10); 
                        break; 
                    }
                    int ingredientCount = getIngredientCount();
                    LogUtil.info("[AutoCraft] Checking supplies for " + activeRecipe.ingredientDisplayName + ". Found " + ingredientCount + ". Required: " + activeRecipe.requiredAmount);
                    if (ingredientCount >= activeRecipe.requiredAmount) {
                        setState(State.OPEN_MENU);
                    } else {
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.error.insufficient_ingredient", activeRecipe.ingredientDisplayName)));
                        setState(State.GET_SUPPLIES);
                    }
                    break;

                case GET_SUPPLIES:
                    LogUtil.info("[AutoCraft] Sending /pickupstash command.");
                    mc.thePlayer.sendChatMessage("/pickupstash");
                    timeoutCounter = STASH_MESSAGE_TIMEOUT_TICKS; // 设置超时
                    setState(State.WAITING_FOR_STASH_MESSAGE);
                    break;

                case WAITING_FOR_STASH_MESSAGE:
                    timeoutCounter--;
                    if (timeoutCounter <= 0) {
                        LogUtil.error("[AutoCraft] Timed out waiting for /pickupstash confirmation message.");
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.autocraft.error.stash_timeout")));
                        stop();
                    }
                    break;

                case CHECK_SUPPLIES_AFTER_STASH:
                    // 这是一个轮询检查状态，直到超时或者材料充足
                    int finalIngredientCount = getIngredientCount();
                    
                    if (finalIngredientCount >= activeRecipe.requiredAmount) {
                        LogUtil.info("[AutoCraft] Supplies confirmed after sync: " + finalIngredientCount + ". Proceeding.");
                        setState(State.OPEN_MENU);
                    } else {
                        if (timeoutCounter > 0) {
                            // 如果还没超时，说明可能还在同步中，继续等待
                            int waitInterval = 5; // 每5 ticks检查一次
                            timeoutCounter -= waitInterval;
                            setDelay(waitInterval);
                            LogUtil.debug("[AutoCraft] Supplies insufficient (" + finalIngredientCount + "/" + activeRecipe.requiredAmount + "). Waiting for inventory sync... " + timeoutCounter + " ticks left.");
                        } else {
                            // 超时了，说明真的不够
                            LogUtil.info("[AutoCraft] Final supply check failed. Found " + finalIngredientCount + ". Required: " + activeRecipe.requiredAmount);
                            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.autocraft.error.insufficient_after_stash", activeRecipe.ingredientDisplayName)));
                            LogUtil.error("[AutoCraft] Insufficient " + activeRecipe.ingredientDisplayName + " after picking up stash (timed out). Stopping.");
                            stop();
                        }
                    }
                    break;

                case OPEN_MENU:
                    ItemStack ninthSlot = mc.thePlayer.inventory.getStackInSlot(8);
                    if (ninthSlot != null && ninthSlot.getItem() == Items.nether_star) {
                        LogUtil.info("[AutoCraft] Found Nether Star in slot 9. Using item...");
                        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, ninthSlot);
                        setDelay(GhostConfig.AutoCraft.autoCraftMenuOpenDelayTicks);
                        setState(State.WAIT_FOR_MENU_GUI);
                    } else {
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + LangUtil.translate("ghost.autocraft.error.no_nether_star")));
                        LogUtil.error("[AutoCraft] Nether Star not found in slot 9!");
                        stop();
                    }
                    break;

                case WAIT_FOR_MENU_GUI:
                    if (isCorrectGuiOpen(SKYBLOCK_MENU_NAME)) {
                        setState(State.CLICK_CRAFT_TABLE);
                    } else {
                        setState(State.OPEN_MENU);
                    }
                    break;

                case CLICK_CRAFT_TABLE:
                    if (isCorrectGuiOpen(SKYBLOCK_MENU_NAME)) {
                        LogUtil.info("[AutoCraft] Clicking on craft table slot: " + CRAFT_TABLE_SLOT);
                        mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, CRAFT_TABLE_SLOT, 0, 0, mc.thePlayer);
                        setDelay(GhostConfig.AutoCraft.autoCraftTableOpenDelayTicks);
                        setState(State.WAIT_FOR_CRAFT_GUI);
                    } else {
                        setState(State.OPEN_MENU);
                    }
                    break;

                case WAIT_FOR_CRAFT_GUI:
                     if (isCorrectGuiOpen(CRAFT_GUI_NAME)) {
                        LogUtil.info("[AutoCraft] Craft Item GUI opened. Resetting placed ingredients count.");
                        ingredientsPlaced = 0; 
                        setState(State.DO_CRAFT);
                    } else {
                        setState(State.CLICK_CRAFT_TABLE);
                    }
                    break;

                case DO_CRAFT:
                    if (!isCorrectGuiOpen(CRAFT_GUI_NAME)) { 
                        LogUtil.warn("[AutoCraft] Craft GUI is not open! Returning to OPEN_MENU state.");
                        setState(State.OPEN_MENU); 
                        break; 
                    }
                    LogUtil.info("[AutoCraft] In DO_CRAFT state. Placed: " + ingredientsPlaced + "/" + activeRecipe.requiredAmount);

                    if (ingredientsPlaced < activeRecipe.requiredAmount) {
                        int ingredientSlotId = findAnyIngredientSlot();
                        // LogUtil.info("[AutoCraft] Searching for " + activeRecipe.ingredientDisplayName + "... Found in actual container slot: " + ingredientSlotId);
                        if (ingredientSlotId != -1) {
                            ItemStack stackBeforeClick = mc.thePlayer.openContainer.getSlot(ingredientSlotId).getStack();
                            if (stackBeforeClick != null) {
                                slotToWatch = ingredientSlotId;
                                stackSizeToWatch = stackBeforeClick.stackSize;
                                LogUtil.info("[AutoCraft] Performing SHIFT+CLICK on slot " + slotToWatch + " which has " + stackSizeToWatch + " items.");
                                mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, slotToWatch, 0, 1, mc.thePlayer);
                                timeoutCounter = PLACEMENT_TIMEOUT_TICKS;
                                setState(State.WAIT_FOR_ITEM_PLACEMENT);
                            } else {
                                LogUtil.warn("[AutoCraft] Slot " + ingredientSlotId + " became empty before click. Retrying search.");
                            }
                        } else {
                            LogUtil.warn("[AutoCraft] No " + activeRecipe.ingredientDisplayName + " found in inventory mid-craft! Returning to CHECK_SUPPLIES.");
                            setState(State.CHECK_SUPPLIES); 
                        }
                    } else {
                        LogUtil.info("[AutoCraft] All required ingredients placed. Moving to wait for craft result.");
                        timeoutCounter = WAIT_TIMEOUT_TICKS;
                        setState(State.WAIT_FOR_CRAFT_RESULT);
                    }
                    break;
                
                case WAIT_FOR_ITEM_PLACEMENT:
                    timeoutCounter--;
                    if (!isCorrectGuiOpen(CRAFT_GUI_NAME)) {
                        handleRecoverableError("GUI closed unexpectedly");
                        break;
                    }
                    ItemStack currentStack = mc.thePlayer.openContainer.getSlot(slotToWatch).getStack();
                    int currentStackSize = (currentStack == null) ? 0 : currentStack.stackSize;

                    // LogUtil.info("[AutoCraft] Waiting for placement confirmation on slot " + slotToWatch + ". Original size: " + stackSizeToWatch + ", Current size: " + currentStackSize + ". Timeout in: " + timeoutCounter);

                    if (currentStack == null || currentStackSize < stackSizeToWatch) {
                        int amountPlaced = stackSizeToWatch - currentStackSize;
                        ingredientsPlaced += amountPlaced;
                        LogUtil.info("[AutoCraft] Placement confirmed. " + amountPlaced + " items placed. Total placed: " + ingredientsPlaced);
                        slotToWatch = -1;
                        stackSizeToWatch = -1;
                        setDelay(GhostConfig.AutoCraft.autoCraftPlacementDelayTicks);
                        setState(State.DO_CRAFT);
                    } else if (timeoutCounter <= 0) {
                        LogUtil.error("[AutoCraft] Timed out waiting for item placement from slot " + slotToWatch + ".");
                        handleRecoverableError("Item placement timeout");
                    }
                    break;

                case WAIT_FOR_CRAFT_RESULT:
                    timeoutCounter--;
                    if (!isCorrectGuiOpen(CRAFT_GUI_NAME)) { setState(State.OPEN_MENU); break; }
                    
                    ItemStack resultStack = mc.thePlayer.openContainer.getSlot(CRAFT_RESULT_SLOT).getStack();
                    if (resultStack != null && !isBarrier(resultStack)) {
                        LogUtil.info("[AutoCraft] Crafting result detected in output slot!");
                        setState(State.TAKE_PRODUCT);
                    } else if (timeoutCounter <= 0) {
                        handleRecoverableError("Crafting result timeout");
                    }
                    break;

                case TAKE_PRODUCT:
                    if (!isCorrectGuiOpen(CRAFT_GUI_NAME)) { setState(State.OPEN_MENU); break; }
                    LogUtil.info("[AutoCraft] Taking product from slot " + CRAFT_RESULT_SLOT);
                    mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, CRAFT_RESULT_SLOT, 0, 1, mc.thePlayer);
                    retryCount = 0;
                    LogUtil.info("[AutoCraft] Craft cycle successful. Retry counter reset.");
                    timeoutCounter = WAIT_TIMEOUT_TICKS;
                    setState(State.WAIT_FOR_SLOT_CLEAR);
                    break;

                case WAIT_FOR_SLOT_CLEAR:
                    timeoutCounter--;
                    if (!isCorrectGuiOpen(CRAFT_GUI_NAME)) { setState(State.OPEN_MENU); break; }

                    ItemStack slotContent = mc.thePlayer.openContainer.getSlot(CRAFT_RESULT_SLOT).getStack();
                    if (isBarrier(slotContent)) {
                        LogUtil.info("[AutoCraft] Slot cleared. Checking supplies for next cycle.");
                        
                        // 优化：在进入下一次合成循环前立即检查材料是否充足
                        if (getIngredientCount() < activeRecipe.requiredAmount) {
                            LogUtil.info("[AutoCraft] Insufficient supplies for next cycle. Triggering supply check.");
                            if (mc.currentScreen != null) {
                                mc.thePlayer.closeScreen();
                            }
                            setState(State.CHECK_SUPPLIES);
                        } else {
                            LogUtil.info("[AutoCraft] Supplies sufficient. Pausing before next cycle.");
                            setDelay(GhostConfig.AutoCraft.autoCraftCycleDelayTicks);
                            ingredientsPlaced = 0;
                            setState(State.DO_CRAFT);
                        }
                    } else if (timeoutCounter <= 0) {
                        handleRecoverableError("Pickup slot clear timeout");
                    }
                    break;
            }
        } catch (Exception e) {
            LogUtil.error("[AutoCraft] An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
            stop();
        }
    }

    private static int getIngredientCount() {
        if (activeRecipe == null) return 0;
        int count = 0;
        InventoryPlayer inventory = mc.thePlayer.inventory;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (isIngredient(stack)) {
                count += stack.stackSize;
            }
        }
        return count;
    }
    
    private static int findAnyIngredientSlot() {
        if (mc.thePlayer.openContainer == null || activeRecipe == null) {
            return -1;
        }
        for (Slot slot : mc.thePlayer.openContainer.inventorySlots) {
            if (slot.getHasStack() && isIngredient(slot.getStack()) && slot.inventory instanceof InventoryPlayer) {
                return slot.slotNumber;
            }
        }
        return -1;
    }

    private static boolean isCorrectGuiOpen(String guiName) {
        String currentGuiName = getGuiName();
        return currentGuiName != null && currentGuiName.contains(guiName);
    }
    
    private static String getGuiName() {
        if (mc.currentScreen instanceof GuiChest) {
            GuiChest chest = (GuiChest) mc.currentScreen;
            ContainerChest container = (ContainerChest) chest.inventorySlots;
            return container.getLowerChestInventory().getDisplayName().getUnformattedText();
        }
        return null;
    }

    private static void setDelay(int ticks) {
        tickCounter = ticks;
    }
}