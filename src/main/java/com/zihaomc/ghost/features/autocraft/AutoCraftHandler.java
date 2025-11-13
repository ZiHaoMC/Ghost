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
        WAIT_FOR_SUPPLIES,
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
    private static int clickCount = 0;

    private static int slotToWatch = -1;
    private static int stackSizeToWatch = -1;
    
    private static int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    private static final int REQUIRED_MITHRIL_AMOUNT = 320;
    private static final Item MITHRIL_ITEM = Items.prismarine_crystals;
    private static final String CRAFT_GUI_NAME = "Craft Item";
    private static final String SKYBLOCK_MENU_NAME = "SkyBlock Menu";

    private static final int CRAFT_RESULT_SLOT = 23;
    private static final int CRAFT_TABLE_SLOT = 31;
    private static final int WAIT_TIMEOUT_TICKS = 60;
    private static final int PLACEMENT_TIMEOUT_TICKS = 40;

    public static void toggle() {
        if (active) {
            stop();
        } else {
            start();
        }
    }

    private static void start() {
        if (currentState != State.IDLE) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.status.running")));
            return;
        }
        active = true;
        retryCount = 0;
        setState(State.STARTING);
        tickCounter = 0;
        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + LangUtil.translate("ghost.autocraft.status.enabled")));
        LogUtil.info("[AutoCraft] Service started.");
    }

    private static void stop() {
        if (currentState == State.IDLE) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.status.not_running")));
            return;
        }
        if (mc.currentScreen != null) {
            mc.thePlayer.closeScreen();
        }
        active = false;
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

    private static boolean isMithril(ItemStack stack) {
        if (stack == null || stack.getItem() != MITHRIL_ITEM) return false;
        String displayName = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        return "Mithril".equals(displayName);
    }

    private static boolean isEnchantedMithril(ItemStack stack) {
        if (stack == null || stack.getItem() != MITHRIL_ITEM) return false;
        String displayName = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        return "Enchanted Mithril".equals(displayName);
    }

    private static boolean isBarrier(ItemStack stack) {
        return stack != null && stack.getItem() == Item.getItemFromBlock(Blocks.barrier);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        setState(currentState);

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
                    int mithrilCount = getMithrilCount();
                    LogUtil.info("[AutoCraft] Checking supplies. Found " + mithrilCount + " Mithril. Required: " + REQUIRED_MITHRIL_AMOUNT);
                    if (mithrilCount >= REQUIRED_MITHRIL_AMOUNT) {
                        setState(State.OPEN_MENU);
                    } else {
                        mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + LangUtil.translate("ghost.autocraft.error.no_mithril")));
                        setState(State.GET_SUPPLIES);
                    }
                    break;

                case GET_SUPPLIES:
                    LogUtil.info("[AutoCraft] Sending /pickupstash command.");
                    mc.thePlayer.sendChatMessage("/pickupstash");
                    setDelay(GhostConfig.AutoCraft.autoCraftPickupStashWaitTicks);
                    setState(State.WAIT_FOR_SUPPLIES);
                    break;

                case WAIT_FOR_SUPPLIES:
                    LogUtil.info("[AutoCraft] Waiting for supplies to be picked up.");
                    setState(State.CHECK_SUPPLIES);
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
                        LogUtil.info("[AutoCraft] Craft Item GUI opened. Resetting click counter.");
                        clickCount = 0; 
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
                    LogUtil.info("[AutoCraft] In DO_CRAFT state. Click count: " + clickCount + "/5");

                    if (clickCount < 5) {
                        int mithrilSlotId = findAnyMithrilSlot();
                        LogUtil.info("[AutoCraft] Searching for Mithril... Found in actual container slot: " + mithrilSlotId);
                        if (mithrilSlotId != -1) {
                            ItemStack stackBeforeClick = mc.thePlayer.openContainer.getSlot(mithrilSlotId).getStack();
                            if (stackBeforeClick != null) {
                                slotToWatch = mithrilSlotId;
                                stackSizeToWatch = stackBeforeClick.stackSize;
                                LogUtil.info("[AutoCraft] Performing SHIFT+CLICK on slot " + slotToWatch + " which has " + stackSizeToWatch + " items.");
                                mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, slotToWatch, 0, 1, mc.thePlayer);
                                timeoutCounter = PLACEMENT_TIMEOUT_TICKS;
                                setState(State.WAIT_FOR_ITEM_PLACEMENT);
                            } else {
                                LogUtil.warn("[AutoCraft] Slot " + mithrilSlotId + " became empty before click. Retrying search.");
                            }
                        } else {
                            LogUtil.warn("[AutoCraft] No Mithril found in inventory mid-craft! Returning to CHECK_SUPPLIES.");
                            setState(State.CHECK_SUPPLIES); 
                        }
                    } else {
                        LogUtil.info("[AutoCraft] 5 clicks completed. Moving to wait for craft result.");
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

                    LogUtil.info("[AutoCraft] Waiting for placement confirmation on slot " + slotToWatch + ". Original size: " + stackSizeToWatch + ", Current size: " + currentStackSize + ". Timeout in: " + timeoutCounter);

                    if (currentStack == null || currentStackSize < stackSizeToWatch) {
                        LogUtil.info("[AutoCraft] Placement confirmed for click #" + (clickCount + 1));
                        clickCount++;
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
                    if (isEnchantedMithril(resultStack)) {
                        LogUtil.info("[AutoCraft] Enchanted Mithril detected!");
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
                        LogUtil.info("[AutoCraft] Slot cleared. Pausing before next cycle.");
                        setDelay(GhostConfig.AutoCraft.autoCraftCycleDelayTicks);
                        clickCount = 0;
                        setState(State.DO_CRAFT);
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

    private static int getMithrilCount() {
        int count = 0;
        InventoryPlayer inventory = mc.thePlayer.inventory;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (isMithril(stack)) {
                count += stack.stackSize;
            }
        }
        return count;
    }
    
    private static int findAnyMithrilSlot() {
        if (mc.thePlayer.openContainer == null) {
            return -1;
        }
        for (Slot slot : mc.thePlayer.openContainer.inventorySlots) {
            if (slot.getHasStack() && isMithril(slot.getStack()) && slot.inventory instanceof InventoryPlayer) {
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