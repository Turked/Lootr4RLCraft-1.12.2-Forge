package com.mechgaming.lootr4rlcraft.serverprobe;

import com.mechgaming.lootr4rlcraft.adapter.ContainerAdapters;
import com.mechgaming.lootr4rlcraft.adapter.InventoryAdapter;
import com.mechgaming.lootr4rlcraft.cart.CartEvents;
import com.mechgaming.lootr4rlcraft.chest.LockAccess;
import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.LogManager;

/** Dedicated test installation only. Arrange identical reproducible viewpoints for each connected client. */
final class RenderRow {
    private static final String[] TYPES = {"minecraft:chest", "minecraft:chest", "minecraft:trapped_chest",
            "quark:custom_chest", "quark:custom_chest", "rustic:cabinet", "rustic:barrel", "charm:barrel",
            "variedcommodities:barrel", "minecraft:chest_minecart", "charm:crate", "charm:bookshelf_chest",
            "minecraft:chest", "rustic:cabinet"};

    static void watch(WorldServer world, EntityPlayerMP actor) {
        if (!"CandidateWorld2".equals(world.getSaveHandler().getWorldDirectory().getName()))
            throw new IllegalStateException("Requires the disposable CandidateWorld2 fixture");
        for (int cx = 4; cx <= 10; cx++) for (int cz = 3; cz <= 6; cz++) world.getChunkFromChunkCoords(cx, cz);
        for (int cx = 5; cx <= 8; cx++) {
            Chunk chunk = world.getChunkFromChunkCoords(cx, 4);
            MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(chunk, actor));
        }
    }

    static boolean ready(WorldServer world) {
        for (int cx = 5; cx <= 8; cx++) if (!LockAccess.worldgenReady(world.getChunkFromChunkCoords(cx, 4))) return false;
        return true;
    }

    static void prepare(WorldServer world, EntityPlayerMP actor) throws Exception {
        // A saved row with fixtures beyond the cart must retain its original cart and UUID.
        boolean expectSavedCart = ContainerLoot.isManaged(world.getTileEntity(new BlockPos(128, 160, 72)));
        for (int i = 0; i < TYPES.length; i++) {
            BlockPos pos = new BlockPos(88 + i * 4, 160, 72);
            for (int x = -1; x <= 2; x++) for (int z = -4; z <= 1; z++) {
                BlockPos floor = pos.add(x, -1, z);
                if (world.isAirBlock(floor)) world.setBlockState(floor, Blocks.STONE.getDefaultState());
            }
            if (i == 9) {
                List<EntityMinecartChest> carts = world.getEntitiesWithinAABB(EntityMinecartChest.class, new AxisAlignedBB(pos).grow(0.15));
                EntityMinecartChest cart;
                if (carts.isEmpty()) {
                    if (expectSavedCart) throw malformed(pos, "The saved row's chest minecart is missing");
                    cart = new EntityMinecartChest(world, pos.getX() + .5, pos.getY(), pos.getZ() + .5);
                    cart.setInventorySlotContents(0, new ItemStack(Items.BOOK, 3));
                    if (!world.spawnEntity(cart)) throw new IllegalStateException("Cannot spawn fixture cart");
                    CartEvents.convert(world, cart, true);
                } else {
                    if (carts.size() != 1) throw malformed(pos, "Several chest minecarts occupy the cart fixture");
                    cart = carts.get(0);
                }
                String details = CartEvents.inspect(world, cart);
                if (!details.endsWith(": managed, 27 private slots.")) throw malformed(pos, details);
                continue;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(TYPES[i]));
            BlockPos partner = i == 1 ? pos.east() : i == 13 ? pos.up() : null;
            if (ContainerLoot.isManaged(world.getTileEntity(pos))) {
                if (world.getBlockState(pos).getBlock() != block) throw malformed(pos, "Expected " + TYPES[i]);
                continue;
            }
            if (block == null || block == Blocks.AIR || !world.isAirBlock(pos))
                throw new IllegalStateException("Unavailable fixture block " + TYPES[i] + " at " + pos);
            if (partner != null && !world.isAirBlock(partner)) throw malformed(partner, "The partner position is occupied");
            IBlockState state = block.getDefaultState();
            for (IProperty<?> prop : state.getPropertyKeys()) {
                if ("facing".equals(prop.getName()) && prop.getAllowedValues().contains(EnumFacing.NORTH))
                    state = set(state, prop, "north");
            }
            world.setBlockState(pos, state);
            if (i == 1) world.setBlockState(pos.east(), state);
            if (i == 13) {
                IProperty<?> top = block.getBlockState().getProperty("top");
                if (top == null) throw malformed(pos, "Rustic cabinet is missing its top property");
                world.setBlockState(pos.up(), set(state, top, "true"));
            }
            TileEntity tile = world.getTileEntity(pos);
            if (i == 3 || i == 4) {
                NBTTagCompound nbt = tile.writeToNBT(new NBTTagCompound());
                nbt.setString("type", i == 3 ? "spruce" : "birch");
                tile.readFromNBT(nbt);
                world.notifyBlockUpdate(pos, state, state, 3);
            }
            InventoryAdapter adapter = ContainerAdapters.forEnrollment(tile);
            if (adapter == null) throw new IllegalStateException("Fixture type lacks adapter: " + TYPES[i]);
            adapter.physical(tile).setInventorySlotContents(0, new ItemStack(Items.BOOK, 3));
            tile.markDirty();
            ContainerLoot.convert(world, pos, true);
            if (i == 12) {
                actor.setPosition(pos.getX() + .5, pos.getY(), pos.getZ() - 2);
                ItemStack lock = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("locks:wood_lock")));
                ItemStack key = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("locks:key")));
                Class.forName("melonslise.locks.common.item.LockingItem")
                        .getMethod("copyId", ItemStack.class, ItemStack.class).invoke(null, lock, key);
                actor.setHeldItem(EnumHand.MAIN_HAND, lock);
                lock.getItem().onItemUse(actor, world, pos, EnumHand.MAIN_HAND, EnumFacing.NORTH, .5F, .5F, 0);
                if (LockAccess.canOpen(world, pos)) throw new IllegalStateException("Row lock did not attach");
                actor.setHeldItem(EnumHand.MAIN_HAND, key);
                actor.interactionManager.processRightClickBlock(actor, world, key, EnumHand.MAIN_HAND,
                        pos, EnumFacing.NORTH, .5F, .5F, 0F);
                if (!LockAccess.canOpen(world, pos)) throw new IllegalStateException("Row lock did not unlock");
            }
        }
        verifyPair(world, actor, new BlockPos(92, 160, 72), EnumFacing.EAST);
        verifyPair(world, actor, new BlockPos(140, 160, 72), EnumFacing.UP);
        BlockPos lockedFixture = new BlockPos(136, 160, 72);
        try (Stream<?> locks = (Stream<?>) Class.forName("melonslise.locks.common.util.LocksUtil")
                .getMethod("intersecting", World.class, BlockPos.class).invoke(null, world, lockedFixture)) {
            if (locks.count() != 1 || !LockAccess.canOpen(world, lockedFixture))
                throw malformed(lockedFixture, "Expected one attached, unlocked padlock");
        }
        LogManager.getLogger("lootr-server-probe").info("RENDER ROW READY 14 native fixtures at y=160, z=72, x=88..140");
    }

    private static void verifyPair(WorldServer world, EntityPlayerMP actor, BlockPos pos, EnumFacing direction) {
        BlockPos partner = pos.offset(direction);
        TileEntity first = world.getTileEntity(pos);
        TileEntity second = world.getTileEntity(partner);
        if (!ContainerLoot.isManaged(first) || !ContainerLoot.isManaged(second)
                || !first.getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id")
                        .equals(second.getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id"))) {
            throw malformed(pos, "Expected a single managed inventory spanning " + pos + " and " + partner);
        }
        // Both partners are in the same chunk. collectViews validates the saved record against native pairing.
        Map<BlockPos, Boolean> views = new HashMap<>();
        ContainerLoot.collectViews(actor, world.getChunkFromBlockCoords(pos), views, new HashMap<>());
        if (!views.containsKey(pos) || !views.containsKey(partner))
            throw malformed(pos, "The saved pair does not match the native inventory");
    }

    private static IllegalStateException malformed(BlockPos pos, String problem) {
        return new IllegalStateException(problem + " at " + pos + ". Inspect this saved row; use a fresh pre-row world copy to provision fixtures again.");
    }

    private static <T extends Comparable<T>> IBlockState set(IBlockState state, IProperty<T> property, String value) {
        return state.withProperty(property, property.parseValue(value).get());
    }
}
