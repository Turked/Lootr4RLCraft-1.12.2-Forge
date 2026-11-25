package com.mechgaming.lootr4rlcraft.container;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

/** Private server data belongs to a chunk, whose capabilities are absent from vanilla chunk packets. */
public final class ContainerChunkData {
    @CapabilityInject(ContainerChunkData.class)
    public static Capability<ContainerChunkData> CAPABILITY;

    private NBTTagCompound saved = empty();
    private final Map<UUID, ContainerLoot.Record> loaded = new HashMap<>();
    private final Set<UUID> unreadable = new HashSet<>();
    private boolean versionLogged;
    @Nullable
    private Chunk owner;

    public ContainerChunkData() {}

    private ContainerChunkData(Chunk chunk) {
        owner = chunk;
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(ContainerChunkData.class,
                new Capability.IStorage<ContainerChunkData>() {
                    @Override
                    public NBTBase writeNBT(Capability<ContainerChunkData> capability, ContainerChunkData instance,
                            EnumFacing side) {
                        return instance.saved.copy();
                    }

                    @Override
                    public void readNBT(Capability<ContainerChunkData> capability, ContainerChunkData instance,
                            EnumFacing side, NBTBase nbt) {
                        if (!(nbt instanceof NBTTagCompound)) {
                            throw new IllegalArgumentException("Invalid Lootr4RLCraft chunk data");
                        }
                        instance.read((NBTTagCompound) nbt);
                    }
                }, ContainerChunkData::new);
    }

    public static Provider attach(Chunk chunk) {
        return new Provider(chunk);
    }

    private static NBTTagCompound empty() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setTag("Containers", new NBTTagCompound());
        return tag;
    }

    private void read(NBTTagCompound tag) {
        saved = tag.copy();
        loaded.clear();
        unreadable.clear();
        versionLogged = false;
    }

    private void checkVersion() {
        if (saved.getInteger("Version") != 1 || !saved.hasKey("Containers", 10)) {
            throw new IllegalStateException("Unsupported Lootr4RLCraft chunk storage version");
        }
    }

    void markDirty() {
        if (owner != null) {
            owner.markDirty();
        }
    }

    @Nullable
    ContainerLoot.Record get(UUID id) {
        if (saved.getInteger("Version") != 1 || !saved.hasKey("Containers", 10)) {
            if (!versionLogged) {
                Lootr4RLCraft.LOGGER.error("Preserving unsupported private container chunk data");
                versionLogged = true;
            }
            return null;
        }
        if (unreadable.contains(id)) {
            return null;
        }
        if (loaded.containsKey(id)) {
            return loaded.get(id);
        }
        NBTTagCompound records = saved.getCompoundTag("Containers");
        if (!records.hasKey(id.toString(), 10)) {
            return null;
        }
        try {
            ContainerLoot.Record record = ContainerLoot.Record.read(records.getCompoundTag(id.toString()), this::markDirty);
            loaded.put(id, record);
            return record;
        } catch (RuntimeException failure) {
            unreadable.add(id);
            Lootr4RLCraft.LOGGER.error("Preserving unreadable private container {}", id, failure);
            return null;
        }
    }

    void put(UUID id, NBTTagCompound record) {
        checkVersion();
        NBTTagCompound records = saved.getCompoundTag("Containers");
        if (records.hasKey(id.toString())) {
            throw new IllegalStateException("Container identity already exists");
        }
        records.setTag(id.toString(), record);
        loaded.remove(id);
        unreadable.remove(id);
        markDirty();
    }

    void remove(UUID id) {
        checkVersion();
        saved.getCompoundTag("Containers").removeTag(id.toString());
        loaded.remove(id);
        markDirty();
    }

    public static final class Provider implements ICapabilitySerializable<NBTTagCompound> {
        private final ContainerChunkData data;

        private Provider(Chunk owner) {
            data = new ContainerChunkData(owner);
        }

        @Override
        public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
            return capability == CAPABILITY && CAPABILITY != null;
        }

        @Override
        @Nullable
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
            return hasCapability(capability, facing) ? CAPABILITY.cast(data) : null;
        }

        @Override
        public NBTTagCompound serializeNBT() {
            return data.saved.copy();
        }

        @Override
        public void deserializeNBT(NBTTagCompound nbt) {
            data.read(nbt);
        }
    }
}
