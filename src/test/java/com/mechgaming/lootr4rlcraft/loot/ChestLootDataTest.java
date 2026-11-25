package com.mechgaming.lootr4rlcraft.loot;

import com.mechgaming.lootr4rlcraft.chest.InstancedChestTileTest;
import com.mechgaming.lootr4rlcraft.adapter.ContainerAdaptersTest;
import com.mechgaming.lootr4rlcraft.client.ResourceLanguageTest;
import com.mechgaming.lootr4rlcraft.network.ChestViewsTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;

/** Storage regression checks on the mapped Forge development runtime. */
public final class ChestLootDataTest {
    private static final ResourceLocation TABLE = new ResourceLocation("minecraft", "chests/simple_dungeon");
    private static final UUID FIRST = new UUID(0L, 1L);
    private static final UUID SECOND = new UUID(0L, 2L);
    private static final UUID THIRD = new UUID(0L, 3L);

    private ChestLootDataTest() {}

    public static void main(String[] args) throws IOException {
        Bootstrap.register();
        for (int container = 1; container <= 100; container++) {
            verifyPlayerIsolationAndReload(container);
        }
        verifyFailedRollCanRetry();
        verifyFixedInventories(27);
        verifyFixedInventories(54);
        verifyLegacyTableRecord();
        verifyGeneratedInventory();
        verifyUnsupportedRecordsArePreserved();
        InstancedChestTileTest.verify(new ItemStack(Items.APPLE));
        ChestViewsTest.verify();
        ResourceLanguageTest.verify();
        ContainerAdaptersTest.verify();
        System.out.println("Verified save/reload and three-player isolation for 100 loot records");
        System.out.println("Verified fixed inventory templates and legacy table records");
        System.out.println("Verified private tile persistence, update-tag isolation and inert physical inventory");
        System.out.println("Verified indicator packet round-trip and validation");
        System.out.println("Verified packaged Minecraft 1.12 locale metadata and chest names");
        System.out.println("Verified generated-mode persistence, capability adapters and native source isolation");
    }

    private static void verifyPlayerIsolationAndReload(long seed) throws IOException {
        NBTTagCompound owner = new NBTTagCompound();
        owner.setString("OtherModData", "keep");
        AtomicInteger dirtyCount = new AtomicInteger();
        ChestLootData data = ChestLootData.create(owner, dirtyCount::incrementAndGet, TABLE, seed, 27);
        check(!data.hasOpened(FIRST) && !data.hasOpened(SECOND) && !data.hasOpened(THIRD),
                "New chest has opened state");

        AtomicInteger rolls = new AtomicInteger();
        Consumer<IInventory> fill = inventory -> {
            rolls.incrementAndGet();
            inventory.setInventorySlotContents(0, new ItemStack(Items.APPLE, 5));
            ItemStack book = new ItemStack(Items.BOOK);
            book.setStackDisplayName("Traveler's notes");
            inventory.setInventorySlotContents(26, book);
        };
        IInventory first = open(data, FIRST, fill);
        IInventory second = open(data, SECOND, fill);
        IInventory third = open(data, THIRD, fill);
        check(first == open(data, FIRST, ChestLootDataTest::unexpectedRoll), "Concurrent views lost shared state");
        first.decrStackSize(0, 2);
        second.clear();
        third.removeStackFromSlot(26);
        check(rolls.get() == 3, "Initial loot was rolled more than once per player");
        check(dirtyCount.get() >= 7, "Inventory changes did not mark the owner dirty");

        NBTTagCompound onDisk = roundTrip(owner);
        check("keep".equals(onDisk.getString("OtherModData")), "Unrelated data changed");
        ChestLootData reloaded = ChestLootData.read(onDisk, dirtyCount::incrementAndGet);
        check(reloaded != null && reloaded.getId().equals(data.getId())
                && reloaded.getLootTable().equals(TABLE) && reloaded.getSeed() == seed
                && reloaded.getSize() == 27, "Container identity or loot parameters changed");
        check(reloaded.hasOpened(FIRST) && reloaded.hasOpened(SECOND) && reloaded.hasOpened(THIRD),
                "Opened state was lost during reload");
        first = open(reloaded, FIRST, ChestLootDataTest::unexpectedRoll);
        second = open(reloaded, SECOND, ChestLootDataTest::unexpectedRoll);
        third = open(reloaded, THIRD, ChestLootDataTest::unexpectedRoll);
        check(first.getStackInSlot(0).getCount() == 3, "First player's withdrawal was lost");
        check("Traveler's notes".equals(first.getStackInSlot(26).getDisplayName()), "Item metadata was lost");
        check(second.isEmpty(), "An exhausted inventory refilled");
        check(third.getStackInSlot(0).getCount() == 5 && third.getStackInSlot(26).isEmpty(),
                "A different player's changes leaked into the third player's inventory");
    }

    private static void verifyFailedRollCanRetry() {
        NBTTagCompound owner = new NBTTagCompound();
        AtomicInteger dirtyCount = new AtomicInteger();
        ChestLootData data = ChestLootData.create(owner, dirtyCount::incrementAndGet, TABLE, 0L, 27);
        RuntimeException failedRoll = new IllegalArgumentException("Test failure during loot generation");
        try {
            open(data, FIRST, inventory -> {
                inventory.setInventorySlotContents(0, new ItemStack(Items.DIAMOND));
                throw failedRoll;
            });
            throw new AssertionError("Loot generation failure was ignored");
        } catch (RuntimeException exception) {
            check(exception == failedRoll, "Loot generation failed unexpectedly");
        }
        check(dirtyCount.get() == 1 && !data.hasOpened(FIRST), "A failed roll committed partial loot");
        IInventory retry = open(data, FIRST, inventory -> {});
        check(retry.isEmpty() && data.hasOpened(FIRST), "A fresh retry inherited partial loot");
    }

    private static void verifyFixedInventories(int size) throws IOException {
        NBTTagCompound owner = new NBTTagCompound();
        NonNullList<ItemStack> template = NonNullList.withSize(size, ItemStack.EMPTY);
        template.set(0, new ItemStack(Items.APPLE, 5));
        ItemStack namedBook = new ItemStack(Items.BOOK);
        namedBook.setStackDisplayName("Fixed treasure");
        template.set(size - 1, namedBook);
        ChestLootData data = ChestLootData.createFixed(owner, () -> {}, template);
        template.get(0).setCount(1);
        template.get(size - 1).setStackDisplayName("Changed source");
        IInventory first = open(data, FIRST, ChestLootDataTest::unexpectedRoll);
        IInventory second = open(data, SECOND, ChestLootDataTest::unexpectedRoll);
        check(data.isFixed() && data.getLootTable() == null, "Fixed mode incorrectly retained a loot table");
        check(first.getStackInSlot(0).getCount() == 5
                && "Fixed treasure".equals(first.getStackInSlot(size - 1).getDisplayName()),
                "Source changes modified the saved fixed template");
        first.decrStackSize(0, 2);
        first.getStackInSlot(size - 1).setStackDisplayName("First player's edits");
        first.removeStackFromSlot(size - 1);
        second.clear();
        ChestLootData reloaded = ChestLootData.read(roundTrip(owner), () -> {});
        check(reloaded != null && reloaded.isFixed() && reloaded.getSize() == size,
                "Fixed template mode or size changed on reload");
        first = open(reloaded, FIRST, ChestLootDataTest::unexpectedRoll);
        second = open(reloaded, SECOND, ChestLootDataTest::unexpectedRoll);
        IInventory third = open(reloaded, THIRD, ChestLootDataTest::unexpectedRoll);
        check(first.getStackInSlot(0).getCount() == 3 && first.getStackInSlot(size - 1).isEmpty(),
                "Fixed inventory edits were lost on reload");
        check(second.isEmpty(), "An empty fixed inventory refilled");
        check(third.getStackInSlot(0).getCount() == 5
                && "Fixed treasure".equals(third.getStackInSlot(size - 1).getDisplayName()),
                "A later player inherited another player's fixed inventory changes");
        check(third.getStackInSlot(1).isEmpty(), "Empty fixed slots gained invented loot");
    }

    private static void verifyLegacyTableRecord() {
        NBTTagCompound owner = new NBTTagCompound();
        ChestLootData.create(owner, () -> {}, TABLE, 55L, 27);
        owner.getCompoundTag(ChestLootData.KEY).removeTag("Mode");
        ChestLootData legacy = ChestLootData.read(owner, () -> {});
        check(legacy != null && !legacy.isFixed() && TABLE.equals(legacy.getLootTable())
                && legacy.getSeed() == 55L, "A previously saved native table record became unreadable");
    }

    private static void verifyGeneratedInventory() throws IOException {
        NBTTagCompound owner = new NBTTagCompound();
        ChestLootData data = ChestLootData.createGenerated(owner, () -> {}, 18);
        AtomicInteger nativeRolls = new AtomicInteger();
        Consumer<IInventory> generator = inventory -> {
            nativeRolls.incrementAndGet();
            inventory.setInventorySlotContents(17, new ItemStack(Items.BOOK, 4));
        };
        IInventory first = open(data, FIRST, generator);
        IInventory second = open(data, SECOND, generator);
        first.decrStackSize(17, 1);
        check(data.isGenerated() && !data.isFixed() && data.getLootTable() == null && nativeRolls.get() == 2,
                "Native adapter mode did not allocate once per player");
        ChestLootData restored = ChestLootData.read(roundTrip(owner), () -> {});
        check(restored != null && restored.isGenerated() && restored.getSize() == 18,
                "Native adapter mode was lost during persistence");
        first = open(restored, FIRST, ChestLootDataTest::unexpectedRoll);
        second = open(restored, SECOND, ChestLootDataTest::unexpectedRoll);
        check(first.getStackInSlot(17).getCount() == 3 && second.getStackInSlot(17).getCount() == 4,
                "Native adapter inventory changes crossed players or rerolled on reopen");
    }

    private static void verifyUnsupportedRecordsArePreserved() {
        NBTTagCompound owner = new NBTTagCompound();
        check(ChestLootData.read(owner, () -> {}) == null, "Unmanaged inventory has a loot record");
        ChestLootData.create(owner, () -> {}, TABLE, 1L, 27);
        owner.getCompoundTag(ChestLootData.KEY).setInteger("Version", 2);
        NBTTagCompound backup = owner.copy();
        try {
            ChestLootData.read(owner, () -> {});
            throw new AssertionError("Unsupported loot data was accepted");
        } catch (IllegalStateException expected) {
            check(backup.equals(owner), "Reading unsupported data modified it");
        }
    }

    private static IInventory open(ChestLootData data, UUID playerId, Consumer<IInventory> fill) {
        return data.open(playerId, new TextComponentTranslation("container.chest"),
                player -> true, player -> {}, player -> {}, fill);
    }

    private static NBTTagCompound roundTrip(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, bytes);
        return CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void unexpectedRoll(IInventory inventory) {
        throw new AssertionError("Loot rerolled on reopen");
    }

    private static void check(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }
}
