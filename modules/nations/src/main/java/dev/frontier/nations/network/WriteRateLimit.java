package dev.frontier.nations.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread limit on attempts, including invalid writes. At most one warning per window. */
public final class WriteRateLimit {
    public enum Decision { ALLOW, WARN, DROP }
    private static final class Window {
        final long started;
        boolean warned;
        Window(long started){this.started=started;}
    }
    private final long interval;
    private final Map<UUID,Window> windows=new HashMap<>();
    public WriteRateLimit(long interval){if(interval<=0)throw new IllegalArgumentException("interval");this.interval=interval;}
    public Decision check(UUID player){
        long now=System.nanoTime();Window previous=windows.get(player);
        if(previous==null||now-previous.started>=interval){windows.put(player,new Window(now));return Decision.ALLOW;}
        if(!previous.warned){previous.warned=true;return Decision.WARN;}
        return Decision.DROP;
    }
    public void remove(UUID player){windows.remove(player);}
    public void clear(){windows.clear();}
}
