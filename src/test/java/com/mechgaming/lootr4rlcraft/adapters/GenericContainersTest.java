package com.mechgaming.lootr4rlcraft.adapters;

import com.mechgaming.lootr4rlcraft.container.ContainerChunkData;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mechgaming.lootr4rlcraft.adapter.ContainerAdapters;
import com.mechgaming.lootr4rlcraft.adapter.InventoryAdapter;
import com.mechgaming.lootr4rlcraft.loot.ChestLootData;
import com.mechgaming.lootr4rlcraft.Settings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import net.minecraft.block.BlockFurnace;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityDispenser;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Native inventory API fixtures exercise private records without shipping third-party mod classes. */
public final class GenericContainersTest {
    private static final ResourceLocation TABLE = new ResourceLocation("lootr4rlcraft_test", "luck_aware");

    private GenericContainersTest() {}

    public static void verify(WorldServer world, EntityPlayerMP[] players) throws IOException {
        check(players.length == 3, "Generic container verification requires three player identities");
        initializeCapabilities();
        registerFixtureTiles();
        NativeLootTile.pendingCapabilityMaterializations = 0;
        writeLuckTable(world);
        for (int index = 0; index < players.length; index++) {
            players[index].getEntityAttribute(SharedMonsterAttributes.LUCK).setBaseValue(index);
        }
        Map<Chunk, StoredChunk> stores = new HashMap<>();
        List<Fixture> fixtures = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            BlockPos pos = new BlockPos(64 + (index % 10) * 3, 90, 64 + (index / 10) * 3);
            attach(world.getChunkFromBlockCoords(pos), stores);
            Fixture fixture = tableFixture(world, pos, index % 2);
            fixtures.add(fixture);
            check(ContainerLoot.supports(fixture.original), "Supported inventory API was rejected");
            check(ContainerLoot.registerGenerated(world, pos, Collections.singleton(pos)),
                    "A newly generated native loot container was not enrolled");
            check(ContainerLoot.isManaged(world.getTileEntity(pos)), "Conversion did not enroll an actual container");
            check(world.getTileEntity(pos) == fixture.original, "Enrollment replaced the original native container tile");
            check(NativeLootTile.pendingCapabilityMaterializations == 0,
                    "Inventory capability discovery consumed a native pending table without its player context");
            exerciseThreePlayers(world, fixture, players, index, 4);
        }

        BlockPos furnacePos = new BlockPos(100, 90, 64);
        attach(world.getChunkFromBlockCoords(furnacePos), stores);
        world.setBlockState(furnacePos, Blocks.FURNACE.getDefaultState(), 3);
        TileEntityFurnace furnace = (TileEntityFurnace) world.getTileEntity(furnacePos);
        furnace.setInventorySlotContents(0, new ItemStack(Items.APPLE, 4));
        Fixture furnaceFixture = new Fixture(furnacePos, furnace, 3, false);
        assertNotEnrolled(world, furnace);
        installLegacy(world, furnace);
        fixtures.add(furnaceFixture);
        exerciseThreePlayers(world, furnaceFixture, players, 100, 4);
        verifyFurnaceStateChanges(world, furnaceFixture, players);
        verifyServerMenuGuards(world, furnaceFixture, players[0]);

        BlockPos capabilityPos = new BlockPos(100, 90, 67);
        attach(world.getChunkFromBlockCoords(capabilityPos), stores);
        world.setBlockState(capabilityPos, Blocks.DROPPER.getDefaultState(), 3);
        CapabilityInventoryTile handlerTile = new CapabilityInventoryTile();
        handlerTile.handler.setStackInSlot(0, new ItemStack(Items.APPLE, 4));
        world.setTileEntity(capabilityPos, handlerTile);
        Fixture capabilityFixture = new Fixture(capabilityPos, handlerTile, 18, false);
        assertNotEnrolled(world, handlerTile);
        installLegacy(world, handlerTile);
        fixtures.add(capabilityFixture);
        exerciseThreePlayers(world, capabilityFixture, players, 101, 4);

        for (int type = 2; type <= 4; type++) {
            BlockPos pos = new BlockPos(99 + (type - 2) * 3, 90, 71);
            Fixture trap = tableFixture(world, pos, type);
            assertNotEnrolled(world, trap.original);
            check(((TileEntityLockableLoot) trap.original).getLootTable().equals(TABLE),
                    "Checking an unsupported structure inventory consumed its native source");
        }
        for (Fixture fixture : fixtures) {
            NBTTagCompound saved = world.getTileEntity(fixture.pos).writeToNBT(new NBTTagCompound());
            TileEntity restored = TileEntity.create(world, roundTrip(saved));
            check(restored != null, "Could not reload the original inventory tile");
            world.setTileEntity(fixture.pos, restored);
        }
        for (StoredChunk store : stores.values()) {
            store.reload();
        }
        for (int index = 0; index < fixtures.size(); index++) {
            Fixture fixture = fixtures.get(index);
            check(ContainerLoot.isManaged(world.getTileEntity(fixture.pos)), "Chunk data reload lost container ownership");
            IInventory first = open(world, fixture, players[0]);
            check(count(first, Items.APPLE) == 0, "Chunk reload rerolled an emptied player inventory");
            players[0].closeScreen();
            IInventory second = open(world, fixture, players[1]);
            check(second.getStackInSlot(fixture.size - 1).getDisplayName().equals("Private record " + index),
                    "Chunk reload lost the second player's private item metadata");
            players[1].closeScreen();
            IInventory third = open(world, fixture, players[2]);
            check(count(third, Items.APPLE) == (fixture.table ? 11 : 3), "Chunk reload lost the third player's withdrawal");
            players[2].closeScreen();
        }
        for (EntityPlayerMP player : players) {
            player.getEntityAttribute(SharedMonsterAttributes.LUCK).setBaseValue(0.0D);
        }
        System.out.println("Verified 100 vanilla chest fixtures with three concurrent players, persisted legacy generic records, and native machine exclusion");
    }

    private static void assertNotEnrolled(WorldServer world, TileEntity tile) {
        check(!ContainerLoot.supports(tile) && !ContainerLoot.eligibleGenerated(tile),
                "An unaudited structure machine qualified for automatic enrollment");
        NBTTagCompound before = tile.writeToNBT(new NBTTagCompound()).copy();
        try {
            ContainerLoot.convert(world, tile.getPos(), true);
            throw new AssertionError("Converted a machine with unknown interaction and loot semantics");
        } catch (IllegalArgumentException expected) {
            check(before.equals(tile.writeToNBT(new NBTTagCompound())), "Refusing a machine changed its native contents");
        }
    }

    /** Model a v0.2 on-disk record so tightened admission also tests old saves. */
    static void installLegacy(WorldServer world, TileEntity tile) {
        BlockPos pos = tile.getPos();
        InventoryAdapter adapter = ContainerAdapters.findGenerated(tile);
        IInventory physical = adapter.physical(tile);
        NBTTagCompound part = new NBTTagCompound();
        part.setLong("Position", pos.toLong());
        part.setString("Block", tile.getBlockType().getRegistryName().toString());
        part.setString("Adapter", adapter.id());
        part.setTag("Source", tile.writeToNBT(new NBTTagCompound()).copy());
        if (adapter.hasLootTable(tile)) {
            ChestLootData.createGenerated(part, () -> {}, physical.getSizeInventory());
        } else {
            NonNullList<ItemStack> items = NonNullList.withSize(physical.getSizeInventory(), ItemStack.EMPTY);
            for (int slot = 0; slot < items.size(); slot++) {
                items.set(slot, physical.getStackInSlot(slot).copy());
            }
            ChestLootData.createFixed(part, () -> {}, items);
        }
        NBTTagCompound record = new NBTTagCompound();
        UUID id = UUID.randomUUID();
        record.setInteger("Version", 1);
        record.setUniqueId("Id", id);
        record.setLong("Anchor", pos.toLong());
        NBTTagList parts = new NBTTagList();
        parts.appendTag(part);
        record.setTag("Parts", parts);
        ContainerChunkData data = world.getChunkFromBlockCoords(pos).getCapability(ContainerChunkData.CAPABILITY, null);
        Capability<ContainerChunkData> cap = ContainerChunkData.CAPABILITY;
        NBTTagCompound saved = (NBTTagCompound) cap.getStorage().writeNBT(cap, data, null);
        saved.getCompoundTag("Containers").setTag(id.toString(), record);
        cap.getStorage().readNBT(cap, data, null, saved);
        adapter.seal(tile);
        NBTTagCompound marker = new NBTTagCompound();
        marker.setInteger("Version", 1);
        marker.setUniqueId("Id", id);
        marker.setLong("Position", pos.toLong());
        marker.setLong("Anchor", pos.toLong());
        marker.setInteger("Dimension", world.provider.getDimension());
        tile.getTileData().setTag(ContainerLoot.MARKER, marker);
    }

    private static void verifyServerMenuGuards(WorldServer world, Fixture fixture, EntityPlayerMP player) {
        IInventory menu = open(world, fixture, player);
        ItemStack original = new ItemStack(Items.DIAMOND, 3);
        player.inventory.setItemStack(original.copy());
        int padding = fixture.size;
        check(!player.openContainer.getSlot(padding).isItemValid(original), "Padded chest slot accepts private items");
        player.openContainer.slotClick(padding, 0, ClickType.PICKUP, player);
        check(ItemStack.areItemStacksEqual(player.inventory.getItemStack(), original), "Padded chest slot consumed the cursor");
        Settings.privateLootEnabled = false;
        try {
            player.openContainer.slotClick(0, 0, ClickType.PICKUP, player);
            check(menu.getStackInSlot(0).isEmpty() && ItemStack.areItemStacksEqual(player.inventory.getItemStack(), original),
                    "A suspended private menu still accepted changes");
        } finally {
            Settings.privateLootEnabled = true;
            player.inventory.setItemStack(ItemStack.EMPTY);
            player.closeScreen();
        }
    }

    private static void verifyFurnaceStateChanges(WorldServer world, Fixture fixture, EntityPlayerMP[] players) {
        TileEntityFurnace furnace = (TileEntityFurnace) fixture.original;
        NBTTagCompound marker = furnace.getTileData().getCompoundTag(ContainerLoot.MARKER).copy();
        furnace.setInventorySlotContents(0, new ItemStack(Blocks.IRON_ORE, 2));
        furnace.setInventorySlotContents(1, new ItemStack(Items.COAL));
        for (boolean burning : new boolean[] { true, false }) {
            BlockFurnace.setState(burning, world, fixture.pos);
            check(world.getBlockState(fixture.pos).getBlock() == (burning ? Blocks.LIT_FURNACE : Blocks.FURNACE),
                    "Native furnace did not change its physical block state");
            check(world.getTileEntity(fixture.pos) == furnace
                    && marker.equals(furnace.getTileData().getCompoundTag(ContainerLoot.MARKER)),
                    "Native furnace state change replaced its tile or managed identity");
            IInventory first = open(world, fixture, players[0]);
            check(count(first, Items.APPLE) == 0, "Furnace state change rerolled an emptied private inventory");
            players[0].closeScreen();
            IInventory second = open(world, fixture, players[1]);
            check(second.getStackInSlot(2).getDisplayName().equals("Private record 100"),
                    "Furnace state change lost saved private item metadata");
            players[1].closeScreen();
            IInventory third = open(world, fixture, players[2]);
            check(count(third, Items.APPLE) == 3, "Furnace state change lost a saved private withdrawal");
            players[2].closeScreen();
            check(furnace.getStackInSlot(0).getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.IRON_ORE)
                    && furnace.getStackInSlot(0).getCount() == 2 && furnace.getStackInSlot(1).getItem() == Items.COAL,
                    "Private furnace menus altered the physical automation inventory");
        }
    }

    private static Fixture tableFixture(WorldServer world, BlockPos pos, int type) {
        TileEntityLockableLoot tile;
        if (type == 0 || type == 1) {
            world.setBlockState(pos, (type == 0 ? Blocks.CHEST : Blocks.TRAPPED_CHEST).getDefaultState(), 3);
            tile = (TileEntityLockableLoot) world.getTileEntity(pos);
        } else if (type == 2) {
            world.setBlockState(pos, Blocks.DISPENSER.getDefaultState(), 3);
            tile = (TileEntityDispenser) world.getTileEntity(pos);
        } else {
            world.setBlockState(pos, Blocks.DROPPER.getDefaultState(), 3);
            tile = new NativeLootTile(type == 3 ? 18 : 54);
            world.setTileEntity(pos, tile);
        }
        tile.setLootTable(TABLE, 42L);
        return new Fixture(pos, tile, tile.getSizeInventory(), true);
    }

    private static void exerciseThreePlayers(WorldServer world, Fixture fixture, EntityPlayerMP[] players,
            int index, int initialCount) {
        IInventory first = open(world, fixture, players[0]);
        IInventory second = open(world, fixture, players[1]);
        IInventory third = open(world, fixture, players[2]);
        check(first != second && second != third && first != third, "Players share the same inventory object");
        int secondCount = fixture.table ? initialCount * 2 : initialCount;
        int thirdCount = fixture.table ? initialCount * 3 : initialCount;
        check(count(first, Items.APPLE) == initialCount && count(second, Items.APPLE) == secondCount
                && count(third, Items.APPLE) == thirdCount, "Native loot generation lost guaranteed items or the opener's luck");
        for (int slot = 0; slot < first.getSizeInventory(); slot++) {
            first.setInventorySlotContents(slot, ItemStack.EMPTY);
        }
        ItemStack privateBook = new ItemStack(Items.BOOK);
        privateBook.setStackDisplayName("Private record " + index);
        second.setInventorySlotContents(fixture.size - 1, privateBook);
        for (int slot = 0; slot < third.getSizeInventory(); slot++) {
            if (third.getStackInSlot(slot).getItem() == Items.APPLE) {
                third.decrStackSize(slot, 1);
                break;
            }
        }
        check(count(first, Items.APPLE) == 0 && count(third, Items.APPLE) == thirdCount - 1,
                "Concurrent private inventories affected one another");
        for (EntityPlayerMP player : players) {
            player.closeScreen();
        }
        check(!hasPrivateData(world.getTileEntity(fixture.pos).getUpdateTag()),
                "Private container data leaked into the tile update packet");
    }

    private static IInventory open(WorldServer world, Fixture fixture, EntityPlayerMP player) {
        player.setPosition(fixture.pos.getX() + 0.5D, fixture.pos.getY(), fixture.pos.getZ() - 1.5D);
        check(ContainerLoot.open(player, fixture.pos), "Managed inventory failed to open");
        Container container = player.openContainer;
        check(container instanceof ContainerChest, "Managed inventory did not create a chest menu");
        IInventory inventory = ((ContainerChest) container).getLowerChestInventory();
        int paddedSize = ((fixture.size + 8) / 9) * 9;
        check(inventory.getSizeInventory() == paddedSize, "Generic menu does not pad its native slots to whole rows");
        check(inventory.getDisplayName().getUnformattedText().equals("lootr"), "Generic menu title is incorrect");
        return inventory;
    }

    private static int count(IInventory inventory, net.minecraft.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean hasPrivateData(NBTTagCompound tag) {
        if (tag.hasKey("Players") || tag.hasKey("Containers")) {
            return true;
        }
        for (String key : tag.getKeySet()) {
            if (tag.hasKey(key, 10) && hasPrivateData(tag.getCompoundTag(key))) {
                return true;
            }
        }
        return false;
    }

    private static NBTTagCompound roundTrip(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, bytes);
        return CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void writeLuckTable(WorldServer world) throws IOException {
        Path file = world.getSaveHandler().getWorldDirectory().toPath()
                .resolve("data/loot_tables/lootr4rlcraft_test/luck_aware.json");
        Files.createDirectories(file.getParent());
        String json = "{\"pools\":[{\"name\":\"luck_fixture\",\"rolls\":1,\"bonus_rolls\":1,\"entries\":["
                + "{\"type\":\"item\",\"name\":\"minecraft:apple\",\"weight\":1,\"functions\":["
                + "{\"function\":\"set_count\",\"count\":4}]}]}]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static void initializeCapabilities() {
        try {
            Field field = CapabilityManager.class.getDeclaredField("providers");
            field.setAccessible(true);
            Map<String, Capability<?>> providers = (Map<String, Capability<?>>) field.get(CapabilityManager.INSTANCE);
            if (!providers.containsKey(ContainerChunkData.class.getName().intern())) {
                ContainerChunkData.register();
            }
            ContainerChunkData.CAPABILITY = (Capability<ContainerChunkData>) providers.get(ContainerChunkData.class.getName().intern());
            if (!providers.containsKey(IItemHandler.class.getName().intern())) {
                CapabilityItemHandler.register();
            }
            CapabilityItemHandler.ITEM_HANDLER_CAPABILITY = (Capability<IItemHandler>) providers.get(IItemHandler.class.getName().intern());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not initialize the isolated native capability fixtures", error);
        }
    }

    public static void attachChunk(Chunk chunk) {
        if (!chunk.hasCapability(ContainerChunkData.CAPABILITY, null)) {
            new StoredChunk(chunk).install(ContainerChunkData.attach(chunk));
        }
    }

    private static void registerFixtureTiles() {
        if (TileEntity.getKey(NativeLootTile.class) == null) {
            GameRegistry.registerTileEntity(NativeLootTile.class, new ResourceLocation("lootr4rlcraft_test", "native_loot"));
        }
        if (TileEntity.getKey(CapabilityInventoryTile.class) == null) {
            GameRegistry.registerTileEntity(CapabilityInventoryTile.class,
                    new ResourceLocation("lootr4rlcraft_test", "capability_inventory"));
        }
    }

    private static void attach(Chunk chunk, Map<Chunk, StoredChunk> stores) {
        if (!stores.containsKey(chunk)) {
            StoredChunk store = new StoredChunk(chunk);
            store.install(ContainerChunkData.attach(chunk));
            stores.put(chunk, store);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture {
        private final BlockPos pos;
        private final TileEntity original;
        private final int size;
        private final boolean table;

        private Fixture(BlockPos pos, TileEntity original, int size, boolean table) {
            this.pos = pos;
            this.original = original;
            this.size = size;
            this.table = table;
        }
    }

    private static final class StoredChunk {
        private final Chunk chunk;
        private final CapabilityDispatcher original;
        private ContainerChunkData.Provider provider;

        private StoredChunk(Chunk chunk) {
            this.chunk = chunk;
            original = chunk.getCapabilities();
        }

        private void install(ContainerChunkData.Provider next) {
            provider = next;
            try {
                Field field = Chunk.class.getDeclaredField("capabilities");
                field.setAccessible(true);
                field.set(chunk, new CapabilityDispatcher(java.util.Collections.singletonMap(
                        new ResourceLocation("lootr4rlcraft", "containers"), next), original));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not attach isolated chunk storage", error);
            }
        }

        private void reload() throws IOException {
            NBTTagCompound saved = roundTrip(provider.serializeNBT());
            ContainerChunkData.Provider next = ContainerChunkData.attach(chunk);
            next.deserializeNBT(saved);
            install(next);
        }
    }

    public static final class NativeLootTile extends TileEntityLockableLoot {
        private static int pendingCapabilityMaterializations;
        private int size = 18;
        private NonNullList<ItemStack> contents = NonNullList.withSize(size, ItemStack.EMPTY);

        public NativeLootTile() {}

        private NativeLootTile(int size) {
            this.size = size;
            contents = NonNullList.withSize(size, ItemStack.EMPTY);
        }

        @Override public int getSizeInventory() { return size; }
        @Override public int getInventoryStackLimit() { return 64; }
        @Override public boolean isEmpty() { return contents.stream().allMatch(ItemStack::isEmpty); }
        @Override protected NonNullList<ItemStack> getItems() { return contents; }
        @Override public String getName() { return "Fixture loot inventory"; }
        @Override public boolean hasCustomName() { return false; }
        @Override public String getGuiID() { return "minecraft:chest"; }
        @Override public Container createContainer(InventoryPlayer inventory, EntityPlayer player) {
            return new ContainerChest(inventory, this, player);
        }

        @Override
        @Nullable
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
            if (size == 18 && capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && getLootTable() != null) {
                // Rustic inventories materialize their pending table when this capability is requested.
                pendingCapabilityMaterializations++;
                fillWithLoot(null);
            }
            return super.getCapability(capability, facing);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            size = tag.hasKey("FixtureSize") ? tag.getInteger("FixtureSize") : 18;
            contents = NonNullList.withSize(size, ItemStack.EMPTY);
            super.readFromNBT(tag);
            if (!checkLootAndRead(tag)) {
                ItemStackHelper.loadAllItems(tag, contents);
            }
        }

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound tag) {
            super.writeToNBT(tag);
            tag.setInteger("FixtureSize", size);
            if (!checkLootAndWrite(tag)) {
                ItemStackHelper.saveAllItems(tag, contents);
            }
            return tag;
        }
    }

    public static final class CapabilityInventoryTile extends TileEntity {
        private final ItemStackHandler handler = new ItemStackHandler(18);

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound tag) {
            super.writeToNBT(tag);
            tag.setTag("FixtureItems", handler.serializeNBT());
            return tag;
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            super.readFromNBT(tag);
            handler.deserializeNBT(tag.getCompoundTag("FixtureItems"));
        }

        @Override
        public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
            return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
        }

        @Override
        @Nullable
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
            return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                    ? CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(handler) : super.getCapability(capability, facing);
        }
    }
}
