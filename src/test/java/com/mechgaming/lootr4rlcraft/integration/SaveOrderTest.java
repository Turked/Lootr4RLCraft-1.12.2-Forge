package com.mechgaming.lootr4rlcraft.integration;

import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import com.mechgaming.lootr4rlcraft.loot.PlayerCheckpoints;
import com.mechgaming.lootr4rlcraft.container.GuardedChestContainer;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;

/** Restore independent native player and container files saved on opposite sides of an inventory transfer. */
public final class SaveOrderTest {
    private SaveOrderTest() {}

    public static void verify(WorldServer world) throws IOException {
        Path evidence = Files.createTempDirectory("Lootr4RLCraft-save-orders-");
        for (boolean legacy : new boolean[] {false, true}) {
            for (boolean hasCheckpoint : new boolean[] {false, true}) {
                runScenario(world, evidence, legacy, hasCheckpoint);
            }
        }
        verifyCursor(world, evidence);
        verifyFullInventoryClose(world, evidence);
        System.out.println("Verified both native player/container save orders, first-open rollback, legacy baselines and cursor recovery");
    }

    private static void runScenario(WorldServer world, Path evidence, boolean legacy, boolean hasCheckpoint)
            throws IOException {
        UUID id = UUID.randomUUID();
        EntityPlayerMP original = actor(world, id);
        NBTTagCompound owner = new NBTTagCompound();
        ChestLootData.createGenerated(owner, () -> {}, 9);
        int initialApples = legacy ? 3 : 7;
        if (legacy) {
            // Pre-checkpoint version 1 saved only the most recent slots of a returning player.
            NonNullList<ItemStack> slots = NonNullList.withSize(9, ItemStack.EMPTY);
            slots.set(0, new ItemStack(Items.APPLE, initialApples));
            owner.getCompoundTag(ChestLootData.KEY).getCompoundTag("Players").setTag(id.toString(),
                    ItemStackHelper.saveAllItems(new NBTTagCompound(), slots));
        }
        ChestLootData record = ChestLootData.read(owner, () -> {});
        int[] rolls = {0};
        original.inventory.setInventorySlotContents(1, namedEmeralds());
        IInventory live = null;
        if (hasCheckpoint) {
            live = open(world, original, record, rolls);
            live.setInventorySlotContents(0, new ItemStack(Items.APPLE, initialApples - 1));
            original.inventory.setInventorySlotContents(2, new ItemStack(Items.APPLE));
            initialApples--;
        }
        String suffix = (legacy ? "legacy" : "new") + (hasCheckpoint ? "-returning" : "-first");
        Path oldPlayer = save(evidence, suffix + "-player-before", original.writeToNBT(new NBTTagCompound()));
        Path oldChunk = save(evidence, suffix + "-chunk-before", owner);
        if (live == null) live = open(world, original, record, rolls);
        check(live.getStackInSlot(0).getCount() == initialApples, "Opening used the wrong historical allocation");
        // Withdraw two apples and deposit a named stack. Together these test both directions of item transfer.
        original.inventory.setInventorySlotContents(3, live.decrStackSize(0, 2));
        live.setInventorySlotContents(1, original.inventory.removeStackFromSlot(1));
        Path newPlayer = save(evidence, suffix + "-player-after", original.writeToNBT(new NBTTagCompound()));
        Path newChunk = save(evidence, suffix + "-chunk-after", owner);
        check(rolls[0] == (legacy ? 0 : 1), "Original generated allocation ran the wrong number of times");
        verifyRestored(world, id, oldPlayer, newChunk, initialApples, false);
        verifyRestored(world, id, newPlayer, oldChunk, initialApples - 2, true);
    }

    private static void verifyRestored(WorldServer world, UUID id, Path playerFile, Path chunkFile,
            int expectedApples, boolean transferred) throws IOException {
        EntityPlayerMP player = actor(world, id);
        player.readFromNBT(load(playerFile));
        ChestLootData record = ChestLootData.read(load(chunkFile), () -> {});
        int[] rolls = {0};
        IInventory contents = open(world, player, record, rolls);
        check(rolls[0] == 0, "Recovery rerolled generated loot");
        ItemStack recovered = contents.getStackInSlot(0);
        check(recovered.getCount() == expectedApples && (recovered.isEmpty() || recovered.getItem() == Items.APPLE),
                "Recovered chest disagrees with player file for " + playerFile);
        check(player.inventory.getStackInSlot(3).getCount() == (transferred ? 2 : 0), "Player withdrawal changed");
        ItemStack deposited = transferred ? contents.getStackInSlot(1) : player.inventory.getStackInSlot(1);
        check(ItemStack.areItemStacksEqual(deposited, namedEmeralds()), "Deposited NBT-bearing stack lost");
        check((transferred ? player.inventory.getStackInSlot(1) : contents.getStackInSlot(1)).isEmpty(), "Deposit duplicated");
    }

    private static IInventory open(WorldServer world, EntityPlayerMP player, ChestLootData data, int[] rolls) {
        return data.openGenerated(world, player, new TextComponentString("lootr"), ignored -> true,
                ignored -> {}, ignored -> {}, inventory -> {
                    rolls[0]++;
                    inventory.setInventorySlotContents(0, new ItemStack(Items.APPLE, 7));
                });
    }

    private static void verifyCursor(WorldServer world, Path evidence) throws IOException {
        UUID id = UUID.randomUUID();
        EntityPlayerMP original = actor(world, id);
        InventoryBasic source = new InventoryBasic("fixture", false, 9);
        source.setInventorySlotContents(0, namedEmeralds());
        original.openContainer = new GuardedChestContainer(original.inventory, source, original);
        original.openContainer.slotClick(0, 0, ClickType.PICKUP, original);
        check(ItemStack.areItemStacksEqual(original.inventory.getItemStack(), namedEmeralds()), "No cursor pickup");
        Path saved = save(evidence, "player-cursor", original.writeToNBT(new NBTTagCompound()));
        EntityPlayerMP restored = actor(world, id);
        restored.readFromNBT(load(saved));
        PlayerCheckpoints.restoreCursor(restored);
        PlayerCheckpoints.restoreCursor(restored);
        check(restored.inventory.getItemStack().isEmpty(), "Login left restored cursor unsaved on the pointer");
        int total = 0;
        for (ItemStack stack : restored.inventory.mainInventory) {
            if (stack.getItem() == Items.EMERALD) {
                check(stack.getDisplayName().equals("Traveller's deposit"), "Restoring cursor lost item NBT");
                total += stack.getCount();
            }
        }
        check(total == 2, "Repeated login recovery lost or duplicated the carried stack");
    }

    private static void verifyFullInventoryClose(WorldServer world, Path evidence) throws IOException {
        EntityPlayerMP first = actor(world, UUID.randomUUID());
        for (int slot = 0; slot < first.inventory.mainInventory.size(); slot++) {
            first.inventory.setInventorySlotContents(slot, new ItemStack(Items.STICK, 64));
        }
        first.inventory.setItemStack(namedEmeralds());
        PlayerCheckpoints.captureCursor(first);
        EntityPlayerMP full = actor(world, first.getUniqueID());
        full.readFromNBT(load(save(evidence, "full-cursor", first.writeToNBT(new NBTTagCompound()))));
        PlayerCheckpoints.restoreCursor(full);
        check(full.inventory.getItemStack().isEmpty(), "Full-inventory login replaced the active cursor");
        InventoryBasic source = new InventoryBasic("fixture", false, 9);
        full.inventory.setItemStack(new ItemStack(Items.APPLE));
        PlayerCheckpoints.openedMenu(full);
        check(full.inventory.getItemStack().getItem() == Items.APPLE, "Recovery overwrote an occupied cursor");
        full.inventory.setItemStack(ItemStack.EMPTY);
        full.openContainer = new GuardedChestContainer(full.inventory, source, full);
        check(ItemStack.areItemStacksEqual(full.inventory.getItemStack(), namedEmeralds()), "Full inventory lost reserved cursor");
        int droppedBefore = droppedEmeralds(world);
        full.openContainer.slotClick(-999, 0, ClickType.PICKUP, full);
        check(ItemStack.areItemStacksEqual(full.inventory.getItemStack(), namedEmeralds()), "Outside-window drop escaped player checkpoint");
        source.setInventorySlotContents(0, namedEmeralds());
        full.openContainer.slotClick(0, 1, ClickType.THROW, full);
        check(ItemStack.areItemStacksEqual(source.getStackInSlot(0), namedEmeralds()), "Throw click sent private items to a world entity");
        full.closeContainer();
        check(droppedEmeralds(world) == droppedBefore, "Private cursor close wrote a world item in a separate save");
        EntityPlayerMP relog = actor(world, first.getUniqueID());
        relog.readFromNBT(load(save(evidence, "closed-cursor", full.writeToNBT(new NBTTagCompound()))));
        PlayerCheckpoints.restoreCursor(relog);
        relog.openContainer = new GuardedChestContainer(relog.inventory, source, relog);
        check(ItemStack.areItemStacksEqual(relog.inventory.getItemStack(), namedEmeralds()), "Ordinary close lost full-inventory overflow");
        for (ItemStack stack : relog.inventory.mainInventory) {
            check(stack.getItem() == Items.STICK && stack.getCount() == 64, "Full-inventory recovery changed an occupied slot");
        }
        relog.inventory.setInventorySlotContents(4, ItemStack.EMPTY);
        relog.closeContainer();
        EntityPlayerMP finalLogin = actor(world, first.getUniqueID());
        finalLogin.readFromNBT(load(save(evidence, "closed-with-space", relog.writeToNBT(new NBTTagCompound()))));
        PlayerCheckpoints.restoreCursor(finalLogin);
        finalLogin.openContainer = new GuardedChestContainer(finalLogin.inventory, source, finalLogin);
        check(ItemStack.areItemStacksEqual(finalLogin.inventory.getStackInSlot(4), namedEmeralds())
                && finalLogin.inventory.getItemStack().isEmpty(), "Close with available space lost or duplicated held stack");
    }

    private static int droppedEmeralds(WorldServer world) {
        return world.loadedEntityList.stream().filter(entity -> entity instanceof EntityItem)
                .map(entity -> ((EntityItem) entity).getItem()).filter(stack -> stack.getItem() == Items.EMERALD
                        && "Traveller's deposit".equals(stack.getDisplayName())).mapToInt(ItemStack::getCount).sum();
    }

    private static ItemStack namedEmeralds() {
        ItemStack stack = new ItemStack(Items.EMERALD, 2);
        stack.setStackDisplayName("Traveller's deposit");
        return stack;
    }

    private static EntityPlayerMP actor(WorldServer world, UUID id) {
        EntityPlayerMP player = new EntityPlayerMP(world.getMinecraftServer(), world, new GameProfile(id, "SaveOrder"),
                new PlayerInteractionManager(world));
        new NetHandlerPlayServer(world.getMinecraftServer(), new NetworkManager(EnumPacketDirection.SERVERBOUND), player) {
            @Override public void sendPacket(Packet<?> packet) { /* This server-side player has no remote connection. */ }
        };
        player.interactionManager.setGameType(GameType.SURVIVAL);
        player.setPosition(8.5D, 90.0D, 8.5D);
        return player;
    }

    private static Path save(Path dir, String name, NBTTagCompound tag) throws IOException {
        Path target = dir.resolve(name + ".dat");
        try (OutputStream out = Files.newOutputStream(target)) {
            CompressedStreamTools.writeCompressed(tag, out);
        }
        return target;
    }

    private static NBTTagCompound load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return CompressedStreamTools.readCompressed(in);
        }
    }

    private static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }
}
