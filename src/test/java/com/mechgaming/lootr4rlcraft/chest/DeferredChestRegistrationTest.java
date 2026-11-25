package com.mechgaming.lootr4rlcraft.chest;

import com.mechgaming.lootr4rlcraft.container.ContainerLoot;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;

/** Exercises the installed Locks capability's phase contract without redistributing its classes. */
public final class DeferredChestRegistrationTest {
    private DeferredChestRegistrationTest() {}

    public static void verify(WorldServer world) {
        Map<Field, Object> originalFields = new HashMap<>();
        Map<Chunk, CapabilityDispatcher> originalCapabilities = new HashMap<>();
        try {
            Capability<Phase> capability = phaseCapability();
            installLockAccess(capability, originalFields);
            Chunk firstChunk = world.getChunkProvider().getLoadedChunk(0, 0);
            Chunk secondChunk = world.getChunkProvider().getLoadedChunk(1, 0);
            Phase firstPhase = attach(firstChunk, capability, originalCapabilities);
            Phase secondPhase = attach(secondChunk, capability, originalCapabilities);
            check(!LockAccess.worldgenReady(firstChunk), "Locks phase zero was treated as complete");
            firstPhase.value = 1;
            check(!LockAccess.worldgenReady(firstChunk), "Locks phase one was treated as complete");
            firstPhase.value = 2;
            check(LockAccess.worldgenReady(firstChunk), "Locks phase two was not recognized");

            verifyFixedAndTableWait(world, firstPhase);
            verifyPairWaitsForBothChunks(world, firstPhase, secondPhase);
            verifyOverlappingDeadlines(world, firstPhase, secondPhase);
            verifyMutationStopsConversion(world, firstPhase);
            verifySaveAndReloadRecovery(world, firstChunk, firstPhase);
            System.out.println("Verified delayed Locks phases, unchanged physical loot, paired readiness, mutation refusal and saved enrollment recovery");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not initialize the isolated Locks capability fixture", error);
        } finally {
            try {
                Field capabilities = Chunk.class.getDeclaredField("capabilities");
                capabilities.setAccessible(true);
                for (Map.Entry<Chunk, CapabilityDispatcher> entry : originalCapabilities.entrySet()) {
                    capabilities.set(entry.getKey(), entry.getValue());
                }
                for (Map.Entry<Field, Object> entry : originalFields.entrySet()) {
                    entry.getKey().set(null, entry.getValue());
                }
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not restore the isolated Locks fixture", error);
            }
        }
    }

    private static void verifyFixedAndTableWait(WorldServer world, Phase phase) {
        phase.value = 0;
        BlockPos fixedPos = new BlockPos(3, 75, 12);
        BlockPos tablePos = new BlockPos(6, 75, 12);
        TileEntityChest fixed = fixed(world, fixedPos, 5);
        TileEntityChest table = empty(world, tablePos);
        ResourceLocation originalTable = new ResourceLocation("lootr4rlcraft_test", "apples");
        table.setLootTable(originalTable, 42L);
        DeferredChestRegistration pending = new DeferredChestRegistration();
        pending.add(world, new HashSet<>(Arrays.asList(fixedPos, tablePos)));
        check(world.getTileEntity(fixedPos) == fixed && fixed.getStackInSlot(0).getCount() == 5,
                "Enrollment emptied fixed loot before Locks inspected it");
        check(world.getTileEntity(tablePos) == table && originalTable.equals(table.getLootTable()),
                "Enrollment consumed the native table before Locks inspected it");
        phase.value = 1;
        advance(world, pending);
        check(world.getTileEntity(fixedPos) == fixed && world.getTileEntity(tablePos) == table,
                "Enrollment ran during Locks phase one");
        check(fixed.getStackInSlot(0).getCount() == 5 && originalTable.equals(table.getLootTable()),
                "Waiting for Locks changed the generator's physical inventory");
        phase.value = 2;
        advance(world, pending);
        check(ContainerLoot.isManaged(world.getTileEntity(fixedPos))
                && ContainerLoot.isManaged(world.getTileEntity(tablePos)),
                "Enrollment failed after Locks reached phase two");
    }

    private static void verifyPairWaitsForBothChunks(WorldServer world, Phase first, Phase second) {
        first.value = 2;
        second.value = 1;
        BlockPos leftPos = new BlockPos(15, 75, 12);
        BlockPos rightPos = leftPos.east();
        TileEntityChest left = fixed(world, leftPos, 3);
        TileEntityChest right = fixed(world, rightPos, 7);
        DeferredChestRegistration pending = new DeferredChestRegistration();
        pending.add(world, new HashSet<>(Arrays.asList(leftPos, rightPos)));
        check(world.getTileEntity(leftPos) == left && world.getTileEntity(rightPos) == right,
                "Enrollment replaced one half before both Locks chunks completed");
        second.value = 2;
        advance(world, pending);
        check(ContainerLoot.isManaged(world.getTileEntity(leftPos))
                && ContainerLoot.isManaged(world.getTileEntity(rightPos)),
                "Cross-chunk double chest did not enroll after both Locks phases completed");
        check(world.getTileEntity(leftPos).getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id")
                .equals(world.getTileEntity(rightPos).getTileData().getCompoundTag(ContainerLoot.MARKER).getUniqueId("Id")),
                "Deferred double chest lost its paired inventory identity");
    }

    private static void verifyMutationStopsConversion(WorldServer world, Phase phase) {
        phase.value = 0;
        BlockPos pos = new BlockPos(9, 75, 12);
        TileEntityChest original = fixed(world, pos, 5);
        DeferredChestRegistration pending = new DeferredChestRegistration();
        pending.add(world, Collections.singleton(pos));
        original.setInventorySlotContents(0, new ItemStack(Items.APPLE, 7));
        phase.value = 2;
        advance(world, pending);
        check(world.getTileEntity(pos) == original && original.getStackInSlot(0).getCount() == 7,
                "Enrollment overwrote a chest modified while native lock generation was pending");
        check(!original.getTileData().hasKey(DeferredChestRegistration.MARKER),
                "A modified chest retained pending enrollment provenance");
    }

    private static void verifyOverlappingDeadlines(WorldServer world, Phase first, Phase second) {
        first.value = 1;
        second.value = 1;
        DeferredChestRegistration pending = new DeferredChestRegistration();
        BlockPos early = new BlockPos(10, 79, 10);
        BlockPos late = new BlockPos(20, 79, 10);
        fixed(world, early, 3);
        fixed(world, late, 4);
        long start = world.getTotalWorldTime();
        pending.add(world, Collections.singleton(early));
        world.setTotalWorldTime(start + 10);
        pending.add(world, Collections.singleton(late));
        first.value = 2;
        world.setTotalWorldTime(start + 21);
        pending.tick(world);
        check(ContainerLoot.isManaged(world.getTileEntity(early)), "Earlier Locks completion missed");
        check(!ContainerLoot.isManaged(world.getTileEntity(late)), "Later chest registered before native Locks");
        second.value = 2;
        world.setTotalWorldTime(start + 31);
        pending.tick(world);
        check(ContainerLoot.isManaged(world.getTileEntity(late)), "An early global retry swallowed a later chunk's deadline");
    }

    private static void verifySaveAndReloadRecovery(WorldServer world, Chunk chunk, Phase phase) {
        phase.value = 0;
        BlockPos pos = new BlockPos(12, 75, 12);
        TileEntityChest original = fixed(world, pos, 6);
        DeferredChestRegistration firstSession = new DeferredChestRegistration();
        firstSession.add(world, Collections.singleton(pos));
        check(original.getTileData().hasKey(DeferredChestRegistration.MARKER),
                "Pending enrollment was not persisted on the original tile");
        NBTTagCompound saved = original.writeToNBT(new NBTTagCompound());
        firstSession.unload(chunk);
        TileEntityChest restored = (TileEntityChest) TileEntity.create(world, saved);
        world.setTileEntity(pos, restored);
        DeferredChestRegistration nextSession = new DeferredChestRegistration();
        nextSession.load(chunk);
        check(world.getTileEntity(pos) == restored && restored.getStackInSlot(0).getCount() == 6,
                "Restoring pending provenance consumed the physical loot before Locks completed");
        phase.value = 2;
        advance(world, nextSession);
        check(ContainerLoot.isManaged(world.getTileEntity(pos)),
                "Saved generation provenance did not resume enrollment after Locks completed");
        check(!world.getTileEntity(pos).getTileData().hasKey(DeferredChestRegistration.MARKER),
                "Converted chest retained the pending enrollment marker");
    }

    private static void advance(WorldServer world, DeferredChestRegistration pending) {
        pending.wake(world);
        world.setTotalWorldTime(world.getTotalWorldTime() + 25L);
        pending.tick(world);
    }

    private static TileEntityChest fixed(WorldServer world, BlockPos pos, int count) {
        TileEntityChest chest = empty(world, pos);
        chest.setInventorySlotContents(0, new ItemStack(Items.APPLE, count));
        return chest;
    }

    private static TileEntityChest empty(WorldServer world, BlockPos pos) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        return (TileEntityChest) world.getTileEntity(pos);
    }

    public static boolean unlocked(World world, BlockPos pos) {
        return false;
    }

    private static void installLockAccess(Capability<Phase> capability, Map<Field, Object> originals)
            throws ReflectiveOperationException {
        set("checked", true, originals);
        set("present", true, originals);
        set("locked", DeferredChestRegistrationTest.class.getMethod("unlocked", World.class, BlockPos.class), originals);
        set("generationChecked", true, originals);
        set("worldgen", capability, originals);
        set("generationDelay", 20, originals);
        set("generationFailureLogged", false, originals);
    }

    private static void set(String name, Object value, Map<Field, Object> originals) throws ReflectiveOperationException {
        Field field = LockAccess.class.getDeclaredField(name);
        field.setAccessible(true);
        originals.put(field, field.get(null));
        field.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static Capability<Phase> phaseCapability() throws ReflectiveOperationException {
        CapabilityManager.INSTANCE.register(Phase.class, new Capability.IStorage<Phase>() {
            @Override public NBTBase writeNBT(Capability<Phase> capability, Phase phase, EnumFacing side) {
                return phase.serializeNBT();
            }

            @Override public void readNBT(Capability<Phase> capability, Phase phase, EnumFacing side, NBTBase tag) {
                phase.deserializeNBT((NBTTagInt) tag);
            }
        }, Phase::new);
        Field providers = CapabilityManager.class.getDeclaredField("providers");
        providers.setAccessible(true);
        return (Capability<Phase>) ((Map<?, ?>) providers.get(CapabilityManager.INSTANCE)).get(Phase.class.getName().intern());
    }

    private static Phase attach(Chunk chunk, Capability<Phase> capability,
            Map<Chunk, CapabilityDispatcher> originals) throws ReflectiveOperationException {
        check(chunk != null, "Locks fixture requires an already loaded chunk");
        Phase phase = new Phase();
        ICapabilityProvider provider = new ICapabilityProvider() {
            @Override public boolean hasCapability(Capability<?> requested, @Nullable EnumFacing side) {
                return requested == capability;
            }

            @Override @Nullable public <T> T getCapability(Capability<T> requested, @Nullable EnumFacing side) {
                return requested == capability ? capability.cast(phase) : null;
            }
        };
        Field field = Chunk.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        CapabilityDispatcher original = (CapabilityDispatcher) field.get(chunk);
        originals.put(chunk, original);
        field.set(chunk, new CapabilityDispatcher(Collections.singletonMap(
                new ResourceLocation("lootr4rlcraft_test", "locks_generation_phase"), provider), original));
        return phase;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class Phase implements INBTSerializable<NBTTagInt> {
        private int value;

        @Override public NBTTagInt serializeNBT() { return new NBTTagInt(value); }
        @Override public void deserializeNBT(NBTTagInt tag) { value = tag.getInt(); }
    }
}
