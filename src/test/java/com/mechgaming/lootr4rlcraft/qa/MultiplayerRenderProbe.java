package com.mechgaming.lootr4rlcraft.qa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Three full clients use real packets against a private dedicated test server. No access to its world objects. */
public final class MultiplayerRenderProbe {
    private static final String[] NAMES = {"RenderOne", "RenderTwo", "RenderThree"};
    private static final Logger LOG = LogManager.getLogger("LootrMultiplayerProbe");
    private static final BlockPos CHEST = new BlockPos(72,160,72);
    private final File directory = new File(System.getProperty("lootr.multiplayerProbe"));
    private final String name = Minecraft.getMinecraft().getSession().getUsername();
    private final int take = Arrays.asList(NAMES).indexOf(name) + 1;
    private String phase = "title", hud = "";
    private boolean returning = Boolean.getBoolean("lootr.afterRestart");
    private boolean completedTravel;
    private final FamilySweep families = Boolean.getBoolean("lootr.familySweep") ? new FamilySweep(directory, name, take) : null;
    private String afterSweep;
    private int ticks;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void hud(RenderGameOverlayEvent.Text event) { hud = String.join(" ", event.getLeft()); }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || "done".equals(phase)) return;
        Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        try {
            if ("title".equals(phase)) {
                if (mc.currentScreen != null && ticks > 40) {
                    check(take > 0, "Unexpected QA player " + name);
                    mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), mc, "127.0.0.1", 25583));
                    next("join");
                }
                return;
            }
            if (ticks > 20 * 1200) throw new IllegalStateException("Timed out during " + phase);
            if (mc.player == null || mc.world == null) return;
            RayTraceResult hit = mc.objectMouseOver;
            boolean targeting = hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && CHEST.equals(hit.getBlockPos());
            switch (phase) {
                case "join":
                    if (ticks < 30) break;
                    mc.displayGuiScreen(null);
                    mc.player.sendChatMessage("/gamemode creative " + name);
                    returnToChest(mc);
                    next("approach");
                    break;
                case "approach":
                    if (ticks < 40 || !targeting) break;
                    check(hud.contains(returning ? "Opened" : "Unopened"), "Unexpected target HUD on approach: " + hud);
                    capture(mc, returning ? "return" : "unopened");
                    if (!returning) mark("ready", hud);
                    next("wait-to-open");
                    break;
                case "wait-to-open":
                    if (ticks % 10 != 0 || !targeting || (!returning && !everyone("ready"))) break;
                    if (!returning && take > 1) {
                        if (!new File(directory, "RenderOne-menu.txt").isFile()) break;
                        check(hud.contains("Unopened"), "Another player's open changed this viewer's HUD: " + hud);
                        capture(mc, "other-player-opened");
                    }
                    mc.playerController.processRightClickBlock(mc.player, mc.world, CHEST, hit.sideHit, hit.hitVec, EnumHand.MAIN_HAND);
                    next("inventory");
                    break;
                case "inventory":
                    if (ticks < 20 || !(mc.player.openContainer instanceof ContainerChest)) break;
                    IInventory inventory = ((ContainerChest) mc.player.openContainer).getLowerChestInventory();
                    int expected = returning ? 3 - take : 3;
                    check(count(inventory) == expected, "Expected " + expected + " diamonds in slot zero but found " + inventory.getStackInSlot(0));
                    capture(mc, returning ? "verified-return-inventory" : "first-inventory");
                    if (returning) {
                        mc.player.closeScreen();
                        check(diamonds(mc) == take, "Returning player inventory contains " + diamonds(mc));
                        if (!completedTravel) {
                            mark("verified-rejoin", "reopened slot 0 = " + expected + ", player inventory diamonds = " + diamonds(mc));
                            if (Boolean.getBoolean("lootr.verifyFamilyRestart")) familyPass("server-restart", "verified");
                            else familyPass("initial", "travel");
                        } else {
                            familyPass("reconnect", "verified");
                        }
                    } else {
                        mark("menu", "initial slot 0 = " + expected);
                        next("take");
                    }
                    break;
                case "take":
                    if (ticks % 10 != 0 || !everyone("menu")) break;
                    ContainerChest menu = (ContainerChest) mc.player.openContainer;
                    int slot = menu.getLowerChestInventory().getSizeInventory();
                    while (slot < menu.inventorySlots.size() && menu.getSlot(slot).getHasStack()) slot++;
                    check(slot < menu.inventorySlots.size(), "No empty player inventory slot");
                    mc.playerController.windowClick(menu.windowId, 0, 0, ClickType.PICKUP, mc.player);
                    for (int i = 0; i < 3 - take; i++)
                        mc.playerController.windowClick(menu.windowId, 0, 1, ClickType.PICKUP, mc.player);
                    mc.playerController.windowClick(menu.windowId, slot, 0, ClickType.PICKUP, mc.player);
                    next("check-change");
                    break;
                case "check-change":
                    if (ticks < 40) break;
                    int actual = count(((ContainerChest) mc.player.openContainer).getLowerChestInventory());
                    check(actual == 3 - take && diamonds(mc) == take, "Transfer mismatch: slots " + actual + ", player " + diamonds(mc));
                    mark("changed", "remaining " + actual + ", player inventory " + diamonds(mc));
                    mc.player.closeScreen();
                    next("travel");
                    break;
                case "travel":
                    if (ticks < 35 || (!returning && !everyone("changed"))) break;
                    check(targeting && hud.contains("Opened"), "Expected gray state before travel " + hud);
                    capture(mc, "opened");
                    mc.player.sendChatMessage("/tp " + name + " 360.5 160 72.5 0 0");
                    next("away");
                    break;
                case "away":
                    if (ticks < 40 || mc.world.getChunkProvider().getLoadedChunk(CHEST.getX() >> 4, CHEST.getZ() >> 4) != null
                            || (families != null && !families.allUnloaded(mc))) break;
                    mark("unload", "target chunk unloaded");
                    returnToChest(mc);
                    next("reloaded");
                    break;
                case "reloaded":
                    if (ticks < 40 || !targeting) break;
                    check(hud.contains("Opened"), "Opened state missing after chunk reload " + hud);
                    capture(mc, "chunk-reload");
                    familyPass("chunk-reload", "leave-dimension");
                    break;
                case "leave-dimension":
                    mc.player.sendChatMessage("/forge setdimension " + name + " -1 72 100 72");
                    next("nether");
                    break;
                case "nether":
                    if (mc.player.dimension != -1 || ticks < 50) break;
                    check(!hud.contains("Lootr"), "Overworld HUD leaked into Nether " + hud);
                    mark("dimension", "entered Nether");
                    mc.player.sendChatMessage("/forge setdimension " + name + " 0 72 160 69");
                    next("overworld");
                    break;
                case "overworld":
                    if (mc.player.dimension != 0 || ticks < 40) break;
                    returnToChest(mc);
                    next("dimension-return");
                    break;
                case "dimension-return":
                    if (ticks < 30 || !targeting) break;
                    check(hud.contains("Opened"), "Opened state missing after return from Nether " + hud);
                    capture(mc, "dimension-return");
                    familyPass("dimension-return", "disconnect");
                    break;
                case "disconnect":
                    mc.world.sendQuittingDisconnectingPacket();
                    mc.loadWorld(null);
                    mc.displayGuiScreen(new GuiMainMenu());
                    returning = true;
                    completedTravel = true;
                    next("title");
                    break;
                case "families":
                    try {
                        if (!families.tick(mc, hud)) break;
                    } catch (RuntimeException failure) {
                        families.failed(mc, failure);
                        break;
                    }
                    if ("travel".equals(afterSweep)) returnToChest(mc);
                    next(afterSweep);
                    break;
                case "verified":
                    mark("verified", "reopened slot 0 = " + (3 - take) + ", player inventory diamonds = " + diamonds(mc));
                    next("done");
                    mc.shutdown();
                    break;
                default: break;
            }
        } catch (Exception failure) {
            LOG.error("QA failure for {} during {}", name, phase, failure);
            try { mark("failed", phase + " " + failure); } catch (IOException ignored) { LOG.error("Cannot save QA failure marker"); }
            next("done");
            mc.shutdown();
        }
    }

    private int count(IInventory inventory) {
        ItemStack stack = inventory.getStackInSlot(0);
        check(stack.isEmpty() || stack.getItem() == Items.DIAMOND, "Wrong item in fixture " + stack);
        return stack.getCount();
    }

    private void familyPass(String pass, String after) {
        if (families == null) {
            next(after);
        } else {
            afterSweep = after;
            families.begin(pass);
            next("families");
        }
    }

    private void returnToChest(Minecraft mc) {
        double offset = (take - 2) * 1.3;
        double yaw = Math.toDegrees(Math.atan2(offset, 3.3));
        mc.player.sendChatMessage("/tp " + name + " " + (72.5 + offset) + " 160 69.2 " + yaw + " 18");
    }

    private int diamonds(Minecraft mc) {
        return mc.player.inventory.mainInventory.stream().filter(s -> s.getItem() == Items.DIAMOND).mapToInt(ItemStack::getCount).sum();
    }

    private void mark(String step, String message) throws IOException {
        Files.write(new File(directory, name + "-" + step + ".txt").toPath(), message.getBytes(StandardCharsets.UTF_8));
        LOG.info("{} {} {}", name, step, message);
    }

    private boolean everyone(String step) {
        for (String other : NAMES) if (!new File(directory, other + "-" + step + ".txt").isFile()) return false;
        return true;
    }

    private void capture(Minecraft mc, String stage) {
        LOG.info("Camera {} {} {}, HUD [{}], {}", mc.player.posX, mc.player.posY, mc.player.posZ, hud,
                ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + "-" + stage + ".png", mc.displayWidth, mc.displayHeight,
                        mc.getFramebuffer()).getUnformattedText());
    }

    private void next(String step) { phase = step; ticks = 0; }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
