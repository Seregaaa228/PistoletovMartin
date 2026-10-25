package dev.frontier.nations.network;

import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

/** Used inside client-only packet work, so queued data from an old server cannot enter a new session. */
public final class ClientPacketGuard {
    private ClientPacketGuard(){}
    public static boolean current(NetworkEvent.Context context){
        var connection=Minecraft.getInstance().getConnection();
        return connection!=null&&connection.getConnection()==context.getNetworkManager();
    }
}
