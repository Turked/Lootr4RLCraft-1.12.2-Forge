package com.mechgaming.lootr4rlcraft.integration;

import com.mechgaming.lootr4rlcraft.adapter.ContainerAdapters;
import com.mechgaming.lootr4rlcraft.adapter.InventoryAdapter;
import com.mechgaming.lootr4rlcraft.adapters.GenericContainersTest;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/** Optional test against installed, development-remapped mod classes supplied only on the test classpath. */
public final class InstalledContainerTest {
    private static final ResourceLocation TABLE = new ResourceLocation("lootr4rlcraft_test", "apples");
    private static final String[] TYPES = {
        "vazkii.quark.decoration.tile.TileCustomChest",
        "rustic.common.tileentity.TileEntityBarrel",
        "rustic.common.tileentity.TileEntityCabinet",
        "svenhjol.charm.crafting.tile.TileBarrel",
        "svenhjol.charm.crafting.tile.TileCrate",
        "svenhjol.charm.crafting.tile.TileBookshelfChest",
        "noppes.vc.blocks.tiles.TileBarrel"
    };

    private InstalledContainerTest() {}

    public static void verify(WorldServer world, EntityPlayerMP player) {
        for (int index = 0; index < TYPES.length; index++) {
            try {
                verifyType(world, player, TYPES[index], index);
            } catch (Throwable failure) {
                throw new AssertionError("Installed container verification failed for " + TYPES[index], failure);
            }
        }
        System.out.println("Verified capture, seal and native-table replay for seven actual installed mod container classes");
        if (Boolean.getBoolean("lootr4rlcraft.testInstalledPairs")) {
            verifyInstalledPairs(world, player);
        }
    }

    private static void verifyInstalledPairs(WorldServer world, EntityPlayerMP player) {
        try {
            Class<?> quarkType = Class.forName("vazkii.quark.decoration.block.BlockCustomChest");
            Block quark = (Block) quarkType.getConstructor(String.class, BlockChest.Type.class)
                    .newInstance("lootr_test_chest", BlockChest.Type.BASIC);
            registerBlock(quark);
            BlockPos left = new BlockPos(97, 96, 49);
            prepareChunks(world, left);
            world.setBlockState(left, quark.getDefaultState(), 2);
            world.setBlockState(left.east(), quark.getDefaultState(), 2);
            TileEntity first = world.getTileEntity(left);
            TileEntity second = world.getTileEntity(left.east());
            setQuarkVariant(first);
            setQuarkVariant(second);
            List<TileEntity> pair = ContainerAdapters.findGenerated(first).parts(world, left);
            check(pair.size() == 2 && pair.get(0) == first && pair.get(1) == second,
                    "Actual Quark spruce blocks did not resolve their two ordered chest halves");
            List<TileEntity> opposite = ContainerAdapters.findGenerated(second).parts(world, left.east());
            check(pair.equals(opposite), "Quark pair order depends on the clicked half");
            Field variant = second.getClass().getField("chestType");
            variant.set(second, variant.getType().getField("BIRCH").get(null));
            check(ContainerAdapters.findGenerated(first).parts(world, left).size() == 1,
                    "Different Quark wood variants incorrectly merged");
            setQuarkVariant(second);
            verifyPrivatePair(world, player, first, second);
            System.out.println("Verified actual Quark spruce pairing and unlike-variant exclusion");

            Class<?> cabinetType = Class.forName("rustic.common.blocks.BlockCabinet");
            Block cabinet = (Block) cabinetType.getConstructor().newInstance();
            registerBlock(cabinet);
            BlockPos bottom = new BlockPos(101, 96, 49);
            world.setBlockState(bottom, cabinet.getDefaultState(), 2);
            @SuppressWarnings("unchecked")
            IProperty<Boolean> top = (IProperty<Boolean>) cabinetType.getField("TOP").get(null);
            world.setBlockState(bottom.up(), cabinet.getDefaultState().withProperty(top, true), 2);
            TileEntity lower = world.getTileEntity(bottom);
            TileEntity upper = world.getTileEntity(bottom.up());
            pair = ContainerAdapters.findGenerated(lower).parts(world, bottom);
            opposite = ContainerAdapters.findGenerated(upper).parts(world, bottom.up());
            check(pair.size() == 2 && pair.contains(lower) && pair.contains(upper) && pair.equals(opposite),
                    "Actual Rustic cabinet blocks did not resolve one consistent pair");
            int slots = 0;
            for (TileEntity tile : pair) {
                slots += ContainerAdapters.findGenerated(tile).physical(tile).getSizeInventory();
            }
            check(slots == 54, "Actual double cabinet has the wrong total slot count");
            verifyPrivatePair(world, player, lower, upper);
            System.out.println("Verified actual Rustic cabinet pair reflection and 54 native slots");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not initialize the actual installed pair block implementations", failure);
        }
    }

    private static void verifyPrivatePair(WorldServer world, EntityPlayerMP player, TileEntity first, TileEntity second)
            throws ReflectiveOperationException {
        GenericContainersTest.initializeCapabilities();
        GenericContainersTest.attachChunk(world.getChunkFromBlockCoords(first.getPos()));
        GenericContainersTest.attachChunk(world.getChunkFromBlockCoords(second.getPos()));
        ((TileEntityLockableLoot) first).setLootTable(TABLE, 19L);
        ((TileEntityLockableLoot) second).setLootTable(TABLE, 19L);
        ContainerLoot.convert(world, first.getPos(), false);
        check(world.getTileEntity(first.getPos()) == first && world.getTileEntity(second.getPos()) == second,
                "Private enrollment replaced an installed mod tile");
        Method createPlayer = NativeChestIntegrationTest.class.getDeclaredMethod("player", player.getServer().getClass(),
                WorldServer.class, String.class, long.class);
        createPlayer.setAccessible(true);
        EntityPlayerMP original = null;
        for (int index = 0; index < 3; index++) {
            EntityPlayerMP viewer = (EntityPlayerMP) createPlayer.invoke(null, player.getServer(), world,
                    "InstalledPair" + index, 500L + index);
            if (index == 0) {
                original = viewer;
            }
            viewer.setPosition(first.getPos().getX() + 0.5D, first.getPos().getY(), first.getPos().getZ() - 1.5D);
            check(ContainerLoot.open(viewer, second.getPos()), "An installed pair could not open its private menu");
            Container menu = viewer.openContainer;
            check(menu.inventorySlots.size() == 90, "An installed pair did not expose 54 private loot slots");
            check(countApples(menu, 54) == 8, "An installed pair changed native loot or shared another player's state");
            if (index == 0) {
                for (int slot = 0; slot < 54; slot++) {
                    menu.getSlot(slot).putStack(ItemStack.EMPTY);
                }
            }
            viewer.closeScreen();
        }
        check(ContainerLoot.open(original, first.getPos()), "An installed pair could not reopen from its opposite half");
        check(countApples(original.openContainer, 54) == 0, "An installed pair rerolled its emptied private inventory");
        original.closeScreen();
        System.out.println("Verified three independent 54-slot player inventories on " + first.getClass().getName());
    }

    private static int countApples(Container menu, int slots) {
        int count = 0;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = menu.getSlot(slot).getStack();
            if (stack.getItem() == Items.APPLE) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void prepareChunks(WorldServer world, BlockPos center) {
        for (int x = (center.getX() >> 4) - 1; x <= (center.getX() >> 4) + 1; x++) {
            for (int z = (center.getZ() >> 4) - 1; z <= (center.getZ() >> 4) + 1; z++) {
                world.getChunkProvider().provideChunk(x, z);
            }
        }
    }

    private static void registerBlock(Block block) {
        if (ForgeRegistries.BLOCKS.getValue(block.getRegistryName()) != block) {
            ForgeRegistries.BLOCKS.register(block);
        }
        int id = Block.getIdFromBlock(block);
        for (IBlockState state : block.getBlockState().getValidStates()) {
            Block.BLOCK_STATE_IDS.put(state, id << 4 | block.getMetaFromState(state));
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyType(WorldServer world, EntityPlayerMP player, String name, int index) throws Exception {
        Class<? extends TileEntity> type = (Class<? extends TileEntity>) Class.forName(name).asSubclass(TileEntity.class);
        Constructor<? extends TileEntity> constructor = type.getConstructor();
        if (TileEntity.getKey(type) == null) {
            GameRegistry.registerTileEntity(type, new ResourceLocation("lootr4rlcraft_installed_test", "container_" + index));
        }
        TileEntity tile = constructor.newInstance();
        BlockPos pos = new BlockPos(48 + index * 3, 90, 48);
        world.getChunkProvider().provideChunk(pos.getX() >> 4, pos.getZ() >> 4);
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        tile.setWorld(world);
        tile.setPos(pos);
        world.setTileEntity(pos, tile);
        setQuarkVariant(tile);

        InventoryAdapter adapter = ContainerAdapters.findGenerated(tile);
        check(adapter != null, "No adapter recognized the installed tile");
        IInventory physical = adapter.physical(tile);
        check(physical.getSizeInventory() > 0, "Installed inventory has no slots");
        ItemStack book = new ItemStack(Items.BOOK, 5);
        book.setStackDisplayName("Installed adapter provenance");
        int slot = physical.getSizeInventory() - 1;
        physical.setInventorySlotContents(slot, book);
        NBTTagCompound fixedSource = tile.writeToNBT(new NBTTagCompound()).copy();
        adapter.seal(tile);
        check(physical.isEmpty(), "Sealing retained shared physical items");
        check(!adapter.hasLootTable(tile), "Sealing retained a pending shared loot table");

        TileEntity restored = TileEntity.create(world, fixedSource.copy());
        check(restored != null && restored.getClass() == type, "Snapshot did not restore the original installed tile class");
        IInventory restoredInventory = adapter.physical(restored);
        check(restoredInventory.getSizeInventory() == physical.getSizeInventory(), "Snapshot changed slot count");
        check(ItemStack.areItemStacksEqual(book, restoredInventory.getStackInSlot(slot)),
                "Snapshot changed fixed contents, layout or item NBT");
        verifyQuarkVariant(tile, restored);

        boolean lootBearing = setNativeTable(tile);
        if (lootBearing) {
            NBTTagCompound tableSource = tile.writeToNBT(new NBTTagCompound()).copy();
            check(adapter.hasLootTable(tile), "Assigned source table was not recognized");
            adapter.seal(tile);
            InventoryBasic generated = new InventoryBasic("test", true, physical.getSizeInventory());
            adapter.generate(world, player, tableSource, generated);
            int apples = 0;
            for (int itemSlot = 0; itemSlot < generated.getSizeInventory(); itemSlot++) {
                ItemStack stack = generated.getStackInSlot(itemSlot);
                if (stack.getItem() == Items.APPLE) {
                    apples += stack.getCount();
                }
            }
            int expectedApples = tile instanceof TileEntityLockableLoot ? 4 : 16;
            check(apples == expectedApples, "Native loot replay changed the deterministic source table or Charm loot-size semantics");
            check(adapter.physical(tile).isEmpty() && !adapter.hasLootTable(tile),
                    "Detached loot replay materialized shared physical loot");
        }
        if (name.equals(TYPES[1])) {
            verifyActiveRusticMenu(world, player, tile, adapter);
        }
        System.out.println("Verified installed adapter " + name + " with " + physical.getSizeInventory() + " slots");
        world.setBlockToAir(pos);
    }

    private static void verifyActiveRusticMenu(WorldServer world, EntityPlayerMP player, TileEntity tile,
            InventoryAdapter adapter) throws ReflectiveOperationException {
        GenericContainersTest.initializeCapabilities();
        GenericContainersTest.attachChunk(world.getChunkFromBlockCoords(tile.getPos()));
        IInventory inventory = adapter.physical(tile);
        inventory.setInventorySlotContents(0, new ItemStack(Items.BOOK, 5));
        Class<?> menuType = Class.forName("rustic.common.tileentity.ContainerBarrel");
        Container menu = (Container) menuType.getConstructor(IInventory.class, tile.getClass())
                .newInstance(player.inventory, tile);
        Container previous = player.openContainer;
        player.openContainer = menu;
        try {
            boolean refused = false;
            try {
                ContainerLoot.convert(world, tile.getPos(), true);
            } catch (IllegalArgumentException busy) {
                refused = busy.getMessage().contains("Close every menu");
            }
            check(refused, "An active actual Rustic barrel menu was not detected before conversion");
            check(!ContainerLoot.isManaged(tile) && inventory.getStackInSlot(0).getCount() == 5,
                    "Refusing an active Rustic barrel menu changed its contents");
        } finally {
            menu.onContainerClosed(player);
            player.openContainer = previous;
        }
        adapter.seal(tile);
        System.out.println("Verified active actual Rustic barrel menus prevent conversion without materializing loot");
    }

    private static boolean setNativeTable(TileEntity tile) throws Exception {
        if (tile instanceof TileEntityLockableLoot) {
            ((TileEntityLockableLoot) tile).setLootTable(TABLE, 19L);
            return true;
        }
        try {
            Method table = tile.getClass().getMethod("setLootTable", String.class);
            table.invoke(tile, TABLE.toString());
            tile.getClass().getMethod("setLootSize", int.class).invoke(tile, 4);
            return true;
        } catch (NoSuchMethodException fixedOnly) {
            return false;
        }
    }

    private static void setQuarkVariant(TileEntity tile) throws ReflectiveOperationException {
        if (!tile.getClass().getName().equals(TYPES[0])) {
            return;
        }
        Field field = tile.getClass().getField("chestType");
        for (Object variant : field.getType().getEnumConstants()) {
            if (variant.toString().equalsIgnoreCase("spruce")) {
                field.set(tile, variant);
                return;
            }
        }
        throw new AssertionError("Installed Quark does not expose its expected spruce chest variant");
    }

    private static void verifyQuarkVariant(TileEntity original, TileEntity restored) throws Exception {
        if (original.getClass().getName().equals(TYPES[0])) {
            Field field = original.getClass().getField("chestType");
            check(field.get(original) == field.get(restored), "Quark wood variant changed during snapshot restoration");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
