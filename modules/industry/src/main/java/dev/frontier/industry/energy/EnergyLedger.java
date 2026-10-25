package dev.frontier.industry.energy;

/** Transaction rules independent of chunks and serialization; accounts own their balances. */
public final class EnergyLedger {
    private EnergyLedger() {}

    public interface Account {
        int stored();
        int capacity();
        void store(int balance);
    }

    public static int stored(Account account) {
        return Math.max(0, Math.min(Math.max(0, account.capacity()), account.stored()));
    }

    public static int receive(Account account, int maximum, boolean simulate) {
        int balance = stored(account);
        int received = Math.max(0, Math.min(maximum, Math.max(0, account.capacity()) - balance));
        if (!simulate && received > 0) account.store(balance + received);
        return received;
    }

    public static int extract(Account account, int maximum, boolean simulate) {
        int balance = stored(account);
        int extracted = Math.max(0, Math.min(maximum, balance));
        if (!simulate && extracted > 0) account.store(balance - extracted);
        return extracted;
    }

    public static int transfer(Account source, Account target, int maximum) {
        if (source == target) return 0;
        int moved = Math.min(extract(source, maximum, true), receive(target, maximum, true));
        if (moved > 0) {
            extract(source, moved, false);
            receive(target, moved, false);
        }
        return moved;
    }

    public static boolean consume(Account account, int amount) {
        if (amount < 0 || stored(account) < amount) return false;
        return amount == 0 || extract(account, amount, false) == amount;
    }
}
