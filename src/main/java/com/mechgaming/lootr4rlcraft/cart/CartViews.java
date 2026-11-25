package com.mechgaming.lootr4rlcraft.cart;

import com.mechgaming.lootr4rlcraft.Lootr4RLCraft;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** Only tracking identity and the recipient's appearance cross the network. */
public final class CartViews implements IMessage {
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("l4rl_cart");
    private int dimension;
    private int entityId;
    private UUID uuid;
    private int state;

    public CartViews() {}

    public static void register() {
        CHANNEL.registerMessage((msg, ctx) -> {
            Lootr4RLCraft.proxy.updateCart(msg.dimension, msg.entityId, msg.uuid, msg.state != 0, msg.state == 2, ctx);
            return null;
        }, CartViews.class, 0, Side.CLIENT);
    }

    static void send(EntityPlayerMP player, EntityMinecartChest cart, boolean managed, boolean opened) {
        CartViews view = new CartViews();
        view.dimension = cart.dimension;
        view.entityId = cart.getEntityId();
        view.uuid = cart.getUniqueID();
        view.state = managed ? (opened ? 2 : 1) : 0;
        CHANNEL.sendTo(view, player);
    }

    @Override public void toBytes(ByteBuf out) {
        out.writeByte(1).writeInt(dimension).writeInt(entityId)
                .writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits()).writeByte(state);
    }

    @Override public void fromBytes(ByteBuf in) {
        if (in.readableBytes() != 26 || in.readUnsignedByte() != 1) throw new IllegalArgumentException("Invalid cart view");
        dimension = in.readInt();
        entityId = in.readInt();
        uuid = new UUID(in.readLong(), in.readLong());
        state = in.readUnsignedByte();
        if (state > 2) throw new IllegalArgumentException("Unknown cart view state");
    }
}
