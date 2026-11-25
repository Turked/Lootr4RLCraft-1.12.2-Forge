package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mechgaming.lootr4rlcraft.loot.PlayerCheckpoints;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Only the isolated external QA runner may terminate its own JVM. This fixture only saves game data. */
final class CrashRecoveryProbe {
    private static final Logger LOG = LogManager.getLogger("lootr-crash-probe");

    static void run(String command, WorldServer world, EntityPlayerMP[] actors) throws Exception {
        Path directory = world.getSaveHandler().getWorldDirectory().getCanonicalFile().toPath();
        if (!"CrashTrial".equals(directory.getFileName().toString())) {
            throw new IllegalArgumentException("Crash probe requires the disposable CrashTrial world");
        }
        Path phase = directory.resolve("data/lootr-crash-phase.txt");
        if ("crash-prime".equals(command)) {
            check(!Files.exists(phase), "Crash trial already started");
            for (int i = 0; i < 3; i++) {
                BlockPos p = position(world, i);
                int cx = p.getX() >> 4, cz = p.getZ() >> 4;
                for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) world.getChunkFromChunkCoords(cx + dx, cz + dz);
                MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(world.getChunkFromBlockCoords(p), actors[0]));
            }
            LOG.info("CRASH_PRIMED");
        } else if ("crash-baseline".equals(command)) {
            check(!Files.exists(phase) && !world.disableLevelSaving, "Baseline requires a new world with saving enabled");
            for (int i = 0; i < 3; i++) {
                BlockPos p = position(world, i);
                check(LockAccess.worldgenReady(world.getChunkFromBlockCoords(p)), "Locks not ready for baseline");
                check(world.isAirBlock(p) && world.isAirBlock(p.up()), "Crash fixture position is occupied");
            }
            for (int i = 0; i < 3; i++) {
                BlockPos p = position(world, i);
                world.setBlockState(p, Blocks.CHEST.getDefaultState());
                ((TileEntityChest) world.getTileEntity(p)).setInventorySlotContents(0, stack(Items.APPLE, 5, "tower ration"));
                if (i < 2) ContainerLoot.convert(world, p, true);
                actors[i].inventory.setInventorySlotContents(0, stack(Items.EMERALD, 2, "return deposit"));
                world.getSaveHandler().getPlayerNBTManager().writePlayerData(actors[i]);
            }
            world.saveAllChunks(true, null);
            world.flushToDisk();
            writePhase(phase, "baseline");
            LOG.info("CRASH_BASELINE saved two registered chests, one ordinary physical chest and three native player files");
        } else if ("crash-arm".equals(command)) {
            check("baseline".equals(readPhase(phase)) && world.disableLevelSaving, "Run baseline then save-off");
            for (int i = 0; i < 3; i++) {
                EntityPlayerMP actor = actors[i];
                check(world.getSaveHandler().getPlayerNBTManager().readPlayerData(actor) != null, "Missing baseline player");
                BlockPos p = position(world, i);
                if (i == 2) ContainerLoot.convert(world, p, true);
                IInventory chest = open(world, actor, p);
                check(ItemStack.areItemStacksEqual(chest.getStackInSlot(0), stack(Items.APPLE, 5, "tower ration")), "Wrong original chest");
                actor.openContainer.slotClick(chest.getSizeInventory() + 27, 0, ClickType.PICKUP, actor);
                actor.openContainer.slotClick(1, 0, ClickType.PICKUP, actor);
                actor.openContainer.slotClick(0, 0, ClickType.PICKUP, actor);
                check(ItemStack.areItemStacksEqual(actor.inventory.getItemStack(), stack(Items.APPLE, 5, "tower ration"))
                        && ItemStack.areItemStacksEqual(chest.getStackInSlot(1), stack(Items.EMERALD, 2, "return deposit")),
                        "Slot transfers failed");
                if (i == 1) {
                    world.getChunkProvider().chunkLoader.saveChunk(world, world.getChunkFromBlockCoords(p));
                    world.getChunkProvider().chunkLoader.flush();
                } else {
                    world.getSaveHandler().getPlayerNBTManager().writePlayerData(actor);
                }
            }
            writePhase(phase, "armed");
            LOG.info("CRASH_ARMED player-only / chunk-only / first-enrollment-player-only saved; terminate this disposable process");
        } else if ("crash-verify".equals(command)) {
            check("armed".equals(readPhase(phase)), "Expected hard-stop checkpoint");
            int passed = 0;
            for (int i = 0; i < 3; i++) {
                EntityPlayerMP actor = actors[i];
                BlockPos p = position(world, i);
                try {
                    check(world.getSaveHandler().getPlayerNBTManager().readPlayerData(actor) != null, "Missing player file");
                    PlayerCheckpoints.restoreCursor(actor);
                    boolean newerPlayer = i != 1;
                    int apples = count(actor.inventory, stack(Items.APPLE, 5, "tower ration"));
                    int emeralds = count(actor.inventory, stack(Items.EMERALD, 2, "return deposit"));
                    TileEntityChest tile = (TileEntityChest) world.getTileEntity(p);
                    boolean managed = ContainerLoot.isManaged(tile);
                    LOG.info("CRASH_STATE scenario={} managed={} physicalApples={} playerApples={} playerEmeralds={}",
                            i, managed, count(tile, stack(Items.APPLE, 5, "tower ration")), apples, emeralds);
                    check(managed, "Original physical chest reappeared");
                    IInventory chest = open(world, actor, p);
                    check(apples == (newerPlayer ? 5 : 0) && emeralds == (newerPlayer ? 0 : 2)
                            && apples + count(chest, stack(Items.APPLE, 5, "tower ration")) == 5
                            && emeralds + count(chest, stack(Items.EMERALD, 2, "return deposit")) == 2, "Item conservation failed");
                    passed++;
                } catch (RuntimeException failure) {
                    LOG.error("CRASH_FAIL scenario=" + i + " at " + p, failure);
                }
            }
            LOG.info("CRASH_RESULT passed={}/3", passed);
        }
    }

    private static BlockPos position(WorldServer world, int i) {
        BlockPos spawn = world.getSpawnPoint();
        return new BlockPos(((spawn.getX() >> 4) + 2 * i) * 16 + 8, 218, (spawn.getZ() >> 4) * 16 + 8);
    }

    private static IInventory open(WorldServer world, EntityPlayerMP actor, BlockPos p) {
        actor.setPosition(p.getX() + .5, p.getY(), p.getZ() + 1.5);
        actor.interactionManager.processRightClickBlock(actor, world, ItemStack.EMPTY, EnumHand.MAIN_HAND, p,
                EnumFacing.SOUTH, .5F, .5F, 1F);
        check(actor.openContainer instanceof ContainerChest, "Expected a chest window");
        IInventory inv = ((ContainerChest) actor.openContainer).getLowerChestInventory();
        check("lootr".equals(inv.getDisplayName().getUnformattedText()), "Expected private menu");
        return inv;
    }

    private static ItemStack stack(Item item, int quantity, String label) {
        ItemStack stack = new ItemStack(item, quantity);
        stack.setStackDisplayName(label);
        return stack;
    }

    private static int count(IInventory inv, ItemStack target) {
        int total = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (ItemStack.areItemsEqual(stack, target) && ItemStack.areItemStackTagsEqual(stack, target)) total += stack.getCount();
        }
        return total;
    }

    private static void writePhase(Path file, String phase) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, phase.getBytes(StandardCharsets.UTF_8));
    }

    private static String readPhase(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void check(boolean pass, String reason) {
        if (!pass) throw new IllegalStateException(reason);
    }
}
