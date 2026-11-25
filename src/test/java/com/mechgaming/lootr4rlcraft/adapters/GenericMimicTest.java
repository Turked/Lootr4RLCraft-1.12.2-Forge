package com.mechgaming.lootr4rlcraft.adapters;

import com.mechgaming.lootr4rlcraft.container.ContainerChunkData;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import com.mechgaming.lootr4rlcraft.container.ContainerEvents;
import java.lang.reflect.Field;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/** Simulates the native RLArtifacts fill/remove sequence without replacing its actual event handler. */
public final class GenericMimicTest {
    private static final ResourceLocation APPLES = new ResourceLocation("lootr4rlcraft_test", "apples");
    private static final ResourceLocation DIAMONDS = new ResourceLocation("lootr4rlcraft_test", "diamonds");

    private GenericMimicTest() {}

    @SuppressWarnings("unchecked")
    public static void verify(WorldServer world, EntityPlayerMP[] players) throws IOException {
        Field indexedMods = null;
        Object originalMods = null;
        try {
            indexedMods = Loader.class.getDeclaredField("namedMods");
            indexedMods.setAccessible(true);
            originalMods = indexedMods.get(Loader.instance());
            Map<String, ModContainer> mods = new HashMap<>((Map<String, ModContainer>) originalMods);
            ModMetadata metadata = new ModMetadata();
            metadata.modId = "artifacts";
            metadata.name = "Native Mimic sequence fixture";
            mods.put("artifacts", new DummyModContainer(metadata));
            indexedMods.set(Loader.instance(), mods);
            attach(world.getChunkFromBlockCoords(new BlockPos(112, 90, 64)));
            verifyNoSpawn(world, players);
            verifyRemovedSingle(world, players[0]);
            verifyRemovedHalf(world, players);
            verifySneakingDoesNotExposeThePhysicalInventory(world, players[0]);
            verifyPreparationFailureRestoresThePhysicalBuffer(world, players[0]);
            System.out.println("Verified generic native Mimic first-roll reuse, automation buffers, removed parts and duplicate completion protection");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not initialize the isolated native Mimic sequence fixture", error);
        } finally {
            if (indexedMods != null && originalMods != null) {
                try {
                    indexedMods.set(Loader.instance(), originalMods);
                } catch (IllegalAccessException error) {
                    throw new AssertionError("Could not restore the isolated mod registry", error);
                }
            }
        }
    }

    private static void verifySneakingDoesNotExposeThePhysicalInventory(WorldServer world, EntityPlayerMP player) {
        BlockPos pos = new BlockPos(124, 90, 68);
        TileEntityChest tile = place(world, pos, APPLES);
        ContainerLoot.convert(world, pos, false);
        tile.setInventorySlotContents(26, new ItemStack(Items.BOOK, 3));
        position(player, pos);
        player.setSneaking(true);
        player.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.STICK));
        try {
            PlayerInteractEvent.RightClickBlock click = new PlayerInteractEvent.RightClickBlock(player,
                    EnumHand.MAIN_HAND, pos, EnumFacing.NORTH, new Vec3d(pos).addVector(0.5D, 0.5D, 0.5D)) {
                @Override public boolean isCancelable() { return true; }
            };
            ContainerEvents events = new ContainerEvents();
            events.beforeClick(click);
            events.prepareClick(click);
            events.afterClick(click);
            check(click.getUseBlock() == Event.Result.DENY && player.openContainer == player.inventoryContainer,
                    "Sneaking item interaction fell through to the native shared inventory menu");
            check(count(tile, Items.BOOK) == 3 && tile.getLootTable() == null,
                    "Sneaking item interaction changed the independent physical automation buffer");
        } finally {
            player.setSneaking(false);
            player.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private static void verifyPreparationFailureRestoresThePhysicalBuffer(WorldServer world, EntityPlayerMP player) {
        if (TileEntity.getKey(FailingRestoreChest.class) == null) {
            GameRegistry.registerTileEntity(FailingRestoreChest.class,
                    new ResourceLocation("lootr4rlcraft_test", "failing_restore_chest"));
        }
        BlockPos pos = new BlockPos(124, 90, 72);
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        FailingRestoreChest tile = new FailingRestoreChest();
        tile.setLootTable(APPLES, 42L);
        world.setTileEntity(pos, tile);
        GenericContainersTest.installLegacy(world, tile);
        tile.setInventorySlotContents(26, new ItemStack(Items.BOOK, 5));
        position(player, pos);
        boolean failed = false;
        FailingRestoreChest.failRestoration = true;
        try {
            ContainerLoot.prepareInteraction(player, pos);
        } catch (RuntimeException expected) {
            failed = true;
        } finally {
            FailingRestoreChest.failRestoration = false;
        }
        check(failed && count(tile, Items.BOOK) == 5 && tile.getLootTable() == null,
                "A preparation exception lost physical items or left a native loot table active");
        ContainerLoot.Interaction retry = ContainerLoot.prepareInteraction(player, pos);
        check(retry != null, "A preparation exception permanently retained the interaction lock");
        retry.finish(true);
        check(count(tile, Items.BOOK) == 5 && tile.getLootTable() == null,
                "Retrying after a preparation exception changed the original automation buffer");
    }

    private static void verifyNoSpawn(WorldServer world, EntityPlayerMP[] players) {
        BlockPos pos = new BlockPos(112, 90, 64);
        TileEntityChest tile = place(world, pos, APPLES);
        ContainerLoot.convert(world, pos, false);
        tile.setInventorySlotContents(26, new ItemStack(Items.DIAMOND, 2));
        position(players[0], pos);
        position(players[1], pos);
        ContainerLoot.Interaction first = ContainerLoot.prepareInteraction(players[0], pos);
        check(first != null && tile.getLootTable() != null, "First native Mimic decision lost the original pending table");
        check(ContainerLoot.prepareInteraction(players[1], pos) == null, "Concurrent interaction started a second Mimic decision");
        check(!ContainerLoot.open(players[1], pos), "A nested interaction could open while the physical table was exposed");
        tile.fillWithLoot(players[0]);
        check(count(tile, Items.APPLE) == 4 && count(tile, Items.DIAMOND) == 0,
                "Native first roll mixed in automation-buffer contents");
        for (int slot = 0; slot < tile.getSizeInventory(); slot++) {
            if (tile.getStackInSlot(slot).getItem() == Items.APPLE) {
                tile.getStackInSlot(slot).setStackDisplayName("Original native first roll");
            }
        }
        first.finish(false);
        first.finish(false);
        check(tile.getLootTable() == null && count(tile, Items.DIAMOND) == 2 && count(tile, Items.APPLE) == 0,
                "Completing the native event did not restore the independent automation buffer");
        check(ContainerLoot.prepareInteraction(players[1], pos) == null, "A second player repeated the first Mimic decision");

        IInventory firstPrivate = open(players[0], pos, 27);
        check(count(firstPrivate, Items.APPLE) == 4 && count(firstPrivate, Items.DIAMOND) == 0,
                "The first viewer did not receive the native first roll exactly once");
        check(hasNamedRoll(firstPrivate), "Opening rerolled the native first-roll inventory");
        players[0].closeScreen();
        IInventory secondPrivate = open(players[1], pos, 27);
        check(count(secondPrivate, Items.APPLE) == 4 && !hasNamedRoll(secondPrivate),
                "Another player received the first viewer's materialized inventory");
        players[1].closeScreen();
        check(count(tile, Items.DIAMOND) == 2, "Private opens consumed native automation-buffer items");
    }

    private static void verifyRemovedSingle(WorldServer world, EntityPlayerMP player) {
        BlockPos pos = new BlockPos(115, 90, 64);
        TileEntityChest tile = place(world, pos, APPLES);
        ContainerLoot.convert(world, pos, false);
        tile.setInventorySlotContents(26, new ItemStack(Items.DIAMOND, 2));
        position(player, pos);
        ContainerLoot.Interaction first = ContainerLoot.prepareInteraction(player, pos);
        check(first != null, "Single chest did not prepare its native Mimic decision");
        tile.fillWithLoot(player);
        world.setBlockToAir(pos);
        first.finish(true);
        check(drops(world, pos, Items.APPLE) == 4 && drops(world, pos, Items.DIAMOND) == 2,
                "Native replacement changed the original loot or automation-buffer drops");
        first.finish(true);
        check(drops(world, pos, Items.APPLE) == 4 && drops(world, pos, Items.DIAMOND) == 2,
                "Duplicate event completion duplicated native replacement drops");
        check(!ContainerLoot.open(player, pos), "Removed chest still exposed a private inventory");
    }

    private static void verifyRemovedHalf(WorldServer world, EntityPlayerMP[] players)
            throws ReflectiveOperationException, IOException {
        BlockPos leftPos = new BlockPos(127, 90, 64);
        BlockPos rightPos = leftPos.east();
        Chunk originalChunk = world.getChunkFromBlockCoords(leftPos);
        Chunk survivorChunk = world.getChunkFromBlockCoords(rightPos);
        attach(survivorChunk);
        TileEntityChest left = place(world, leftPos, APPLES);
        TileEntityChest right = place(world, rightPos, DIAMONDS);
        ContainerLoot.convert(world, leftPos, false);
        left.setInventorySlotContents(26, new ItemStack(Items.DIAMOND, 2));
        right.setInventorySlotContents(26, new ItemStack(Items.BOOK, 5));
        position(players[0], leftPos);
        ContainerLoot.Interaction first = ContainerLoot.prepareInteraction(players[0], leftPos);
        check(first != null, "Double chest did not prepare its native Mimic decision");
        left.fillWithLoot(players[0]);
        world.setBlockToAir(leftPos);
        first.finish(true);
        first.finish(true);
        check(drops(world, leftPos, Items.APPLE) == 4 && drops(world, leftPos, Items.DIAMOND) == 2,
                "Replacing a double-chest half duplicated or lost its native drops");
        check(ContainerLoot.isManaged(world.getTileEntity(rightPos)), "Native replacement orphaned the surviving managed half");
        NBTTagCompound marker = right.getTileData().getCompoundTag(ContainerLoot.MARKER);
        String id = marker.getUniqueId("Id").toString();
        check(marker.getLong("Anchor") == rightPos.toLong(), "Survivor still depends on its destroyed cross-chunk anchor");
        check(!storage(originalChunk).getCompoundTag("Containers").hasKey(id),
                "The destroyed anchor retained a second copy of the surviving record");
        check(storage(survivorChunk).getCompoundTag("Containers").hasKey(id),
                "The surviving record did not move into its own chunk storage");
        check(count(right, Items.BOOK) == 5, "Native replacement changed the surviving half's automation buffer");
        IInventory remaining = open(players[0], rightPos, 27);
        check(count(remaining, Items.DIAMOND) == 3 && count(remaining, Items.BOOK) == 0,
                "Surviving half lost its independent native table or exposed automation contents");
        for (int slot = 0; slot < remaining.getSizeInventory(); slot++) {
            if (remaining.getStackInSlot(slot).getItem() == Items.DIAMOND) {
                remaining.getStackInSlot(slot).setStackDisplayName("Surviving native roll");
            }
        }
        remaining.markDirty();
        players[0].closeScreen();

        NBTTagCompound tileSnapshot = roundTrip(right.writeToNBT(new NBTTagCompound()));
        NBTTagCompound privateSnapshot = roundTrip(storage(survivorChunk));
        TileEntity restored = TileEntity.create(world, tileSnapshot);
        check(restored != null, "Surviving native tile could not be reloaded");
        world.setTileEntity(rightPos, restored);
        ContainerChunkData.Provider reloaded = ContainerChunkData.attach(survivorChunk);
        reloaded.deserializeNBT(privateSnapshot);
        Field capabilities = Chunk.class.getDeclaredField("capabilities");
        capabilities.setAccessible(true);
        capabilities.set(survivorChunk, new CapabilityDispatcher(Collections.singletonMap(
                new ResourceLocation("lootr4rlcraft", "containers"), reloaded)));
        CapabilityDispatcher oldAnchorStorage = originalChunk.getCapabilities();
        capabilities.set(originalChunk, null);
        try {
            IInventory reopened = open(players[0], rightPos, 27);
            check(count(reopened, Items.DIAMOND) == 3 && hasName(reopened, "Surviving native roll"),
                    "Survivor lost its saved private roll after its old anchor storage became unavailable");
            players[0].closeScreen();
        } finally {
            capabilities.set(originalChunk, oldAnchorStorage);
        }
        position(players[1], rightPos);
        check(ContainerLoot.prepareInteraction(players[1], rightPos) == null,
                "The surviving half repeated an already completed Mimic decision");
    }

    private static TileEntityChest place(WorldServer world, BlockPos pos, ResourceLocation table) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        TileEntityChest tile = (TileEntityChest) world.getTileEntity(pos);
        tile.setLootTable(table, 42L);
        return tile;
    }

    private static IInventory open(EntityPlayerMP player, BlockPos pos, int slots) {
        position(player, pos);
        check(ContainerLoot.open(player, pos), "Native Mimic result could not open its remaining private inventory");
        check(player.openContainer instanceof ContainerChest, "Native Mimic result opened the wrong menu type");
        IInventory inventory = ((ContainerChest) player.openContainer).getLowerChestInventory();
        check(inventory.getSizeInventory() == slots, "Native replacement left the wrong private slot count");
        return inventory;
    }

    private static void position(EntityPlayerMP player, BlockPos pos) {
        player.setPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() - 1.5D);
    }

    private static boolean hasNamedRoll(IInventory inventory) {
        return hasName(inventory, "Original native first roll");
    }

    private static boolean hasName(IInventory inventory, String name) {
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            if (inventory.getStackInSlot(slot).getDisplayName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static NBTTagCompound storage(Chunk chunk) {
        ContainerChunkData data = chunk.getCapability(ContainerChunkData.CAPABILITY, null);
        check(data != null, "The native test chunk lacks private container storage");
        return (NBTTagCompound) ContainerChunkData.CAPABILITY.getStorage().writeNBT(ContainerChunkData.CAPABILITY, data, null);
    }

    private static NBTTagCompound roundTrip(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, bytes);
        return CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static int count(IInventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int drops(WorldServer world, BlockPos pos, Item item) {
        return world.getEntitiesWithinAABB(EntityItem.class, new AxisAlignedBB(pos).grow(1.5D)).stream()
                .map(EntityItem::getItem).filter(stack -> stack.getItem() == item).mapToInt(ItemStack::getCount).sum();
    }

    private static void attach(Chunk chunk) throws ReflectiveOperationException {
        if (chunk.hasCapability(ContainerChunkData.CAPABILITY, null)) {
            return;
        }
        Field field = Chunk.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        field.set(chunk, new CapabilityDispatcher(Collections.singletonMap(
                new ResourceLocation("lootr4rlcraft", "containers"), ContainerChunkData.attach(chunk)), chunk.getCapabilities()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class FailingRestoreChest extends TileEntityChest {
        private static boolean failRestoration;

        @Override
        public void setLootTable(ResourceLocation table, long seed) {
            if (failRestoration && table != null) {
                throw new IllegalStateException("Native loot restoration fixture failure");
            }
            super.setLootTable(table, seed);
        }
    }
}
