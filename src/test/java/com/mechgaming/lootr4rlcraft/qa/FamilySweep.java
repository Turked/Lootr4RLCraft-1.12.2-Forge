package com.mechgaming.lootr4rlcraft.qa;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Real camera, HUD and server-backed menus for the row made by the dedicated-server fixture. */
final class FamilySweep {
    private static final String[] TYPES = {"single", "double", "trapped", "quark-spruce", "quark-birch",
            "rustic-cabinet", "rustic-barrel", "charm-barrel", "variedcommodities-barrel", "minecart",
            "charm-crate", "charm-bookshelf", "locked-single", "double-cabinet"};
    private static final Logger LOG = LogManager.getLogger("LootrFamilyProbe");
    private final File markers;
    private final String name;
    private final int take;
    private String pass, step;
    private int index, age;

    FamilySweep(File markers, String name, int take) {
        this.markers = markers;
        this.name = name;
        this.take = take;
    }

    void begin(String passName) {
        pass = passName;
        index = 0;
        enter("move");
    }

    boolean allUnloaded(Minecraft mc) {
        for (int n = 0; n < TYPES.length; n++) {
            if (mc.world.getChunkProvider().getLoadedChunk((88 + n * 4) >> 4, 72 >> 4) != null) return false;
        }
        return true;
    }

    boolean tick(Minecraft mc, String hud) throws IOException {
        if (index == TYPES.length) return true;
        String family = TYPES[index];
        boolean initial = "initial".equals(pass);
        boolean verifyInventory = "reconnect".equals(pass) || "server-restart".equals(pass);
        int x = 88 + index * 4;
        BlockPos pos = new BlockPos(x, 160, 72);
        RayTraceResult hit = mc.objectMouseOver;
        boolean pointing = hit != null && ("minecart".equals(family)
                ? hit.entityHit instanceof EntityMinecartChest && Math.abs(hit.entityHit.posX - (x + 0.5)) < 1
                : hit.typeOfHit == RayTraceResult.Type.BLOCK && pos.equals(hit.getBlockPos()));
        if (++age > 2400) throw new IllegalStateException("Timed out at " + pass + "/" + family + "/" + step
                + " hit=" + hit + ", HUD=" + hud + ", menu=" + mc.currentScreen);
        switch (step) {
            case "move":
                double offset = (take - 2) * 1.3;
                mc.player.sendChatMessage("/tp " + name + " " + (x + 0.5 + offset) + " 160 69.2 "
                        + Math.toDegrees(Math.atan2(offset, 3.3)) + " 18");
                enter("look");
                break;
            case "look":
                if (age < 25 || !pointing) break;
                if (initial && take > 1 && !new File(markers, family + "-RenderOne-opened.txt").isFile()
                        && !new File(markers, family + "-RenderOne-initial-failed.txt").isFile()) break;
                require(hud.contains(initial ? "Unopened" : "Opened"), "Unexpected " + pass + "/" + family + " HUD " + hud);
                capture(mc, family, initial ? "unopened" : pass, hud);
                if (initial || verifyInventory) {
                    if (hit.entityHit != null) mc.playerController.interactWithEntity(mc.player, hit.entityHit, EnumHand.MAIN_HAND);
                    else mc.playerController.processRightClickBlock(mc.player, mc.world, pos, hit.sideHit, hit.hitVec, EnumHand.MAIN_HAND);
                    enter("menu");
                } else {
                    index++;
                    enter("move");
                }
                break;
            case "menu":
                if (age < 20 || !(mc.player.openContainer instanceof ContainerChest)) break;
                ContainerChest menu = (ContainerChest) mc.player.openContainer;
                IInventory lower = menu.getLowerChestInventory();
                int expected = initial ? 3 : 3 - take;
                require(books(lower) == expected, pass + "/" + family + " expected " + expected + " books but got " + books(lower));
                LOG.info("{} {} {} has {} private slots, {} books", name, family, pass, lower.getSizeInventory(), expected);
                if (initial) {
                    int source = 0;
                    while (source < lower.getSizeInventory() && lower.getStackInSlot(source).getItem() != Items.BOOK) source++;
                    require(source < lower.getSizeInventory() && lower.getStackInSlot(source).getCount() == 3,
                            "Fixture lacks a single stack of 3 books in " + family);
                    int dest = lower.getSizeInventory();
                    while (dest < menu.inventorySlots.size() && menu.getSlot(dest).getHasStack()) dest++;
                    require(dest < menu.inventorySlots.size(), "No empty player slot");
                    mc.playerController.windowClick(menu.windowId, source, 0, ClickType.PICKUP, mc.player);
                    for (int i = 0; i < 3 - take; i++) mc.playerController.windowClick(menu.windowId, source, 1, ClickType.PICKUP, mc.player);
                    mc.playerController.windowClick(menu.windowId, dest, 0, ClickType.PICKUP, mc.player);
                    enter("transfer");
                } else {
                    mc.player.closeScreen();
                    enter("leave");
                }
                break;
            case "transfer":
                if (age < 25) break;
                int remaining = books(((ContainerChest) mc.player.openContainer).getLowerChestInventory());
                require(remaining == 3 - take, "Transfer did not update " + family);
                mc.player.closeScreen();
                enter("opened");
                break;
            case "opened":
                if (age < 18 || !pointing) break;
                require(hud.contains("Opened"), "Missing opened HUD for " + family);
                capture(mc, family, "opened", hud);
                Files.write(new File(markers, family + "-" + name + "-opened.txt").toPath(),
                        ("remaining=" + (3 - take)).getBytes(StandardCharsets.UTF_8));
                enter("leave");
                break;
            case "leave":
                if (age < 4) break;
                index++;
                enter("move");
                break;
            default: throw new IllegalStateException(step);
        }
        return index == TYPES.length;
    }

    private void capture(Minecraft mc, String family, String stage, String hud) {
        LOG.info("{} {} {} HUD [{}] capture {}", name, family, stage, hud,
                ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + "-" + family + "-" + stage + ".png",
                        mc.displayWidth, mc.displayHeight, mc.getFramebuffer()).getUnformattedText());
    }

    void failed(Minecraft mc, RuntimeException failure) throws IOException {
        String label = TYPES[index] + "-" + name + "-" + pass + "-failed";
        LOG.error("Family check failed {} at {}", label, step, failure);
        Files.write(new File(markers, label + ".txt").toPath(), failure.toString().getBytes(StandardCharsets.UTF_8));
        if (mc.player.openContainer != mc.player.inventoryContainer) mc.player.closeScreen();
        index++;
        enter("move");
    }

    private static int books(IInventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.getItem() == Items.BOOK) count += stack.getCount();
        }
        return count;
    }

    private void enter(String next) { step = next; age = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
