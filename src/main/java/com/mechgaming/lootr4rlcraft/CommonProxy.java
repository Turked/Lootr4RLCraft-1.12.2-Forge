package com.mechgaming.lootr4rlcraft;

import com.mechgaming.lootr4rlcraft.network.ChestViews;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class CommonProxy {
    public void register() {}

    public void acceptView(ChestViews.View message, MessageContext context) {}

    public void updateCart(int dimension, int entityId, UUID cartUuid, boolean managed, boolean opened,
            MessageContext context) {}
}
