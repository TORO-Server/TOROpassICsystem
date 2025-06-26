package prj.salmon.toropassicsystem;

import org.bukkit.entity.Player;
import prj.salmon.toropassicsystem.types.PaymentHistory;

public class TOROpassICsystemAPI {

    private final TOROpassICsystem plugin;

    public TOROpassICsystemAPI(TOROpassICsystem plugin) {
        this.plugin = plugin;
    }

    // 残高を引き去るメソッド
    public boolean deductBalance(Player player, int amount) {
        if (amount < 0) {
            return false; // 負の額の引き去りは無効
        }
        try {
            DatabaseManager.UserData user = plugin.dbManager.getUser(player.getUniqueId());
            if (user == null) return false;
            if (user.balance >= amount) {
                user.balance -= amount;
                user.lastupdate = System.currentTimeMillis() / 1000L;
                plugin.dbManager.upsertUser(user);
                PaymentHistory h = PaymentHistory.build("Other::deduct", "他のプラグインから引き去り", -amount, user.balance, System.currentTimeMillis() / 1000L, null);
                plugin.dbManager.addHistory(user.uuid, h);
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public int getBalance(Player player) {
        try {
            DatabaseManager.UserData user = plugin.dbManager.getUser(player.getUniqueId());
            return user != null ? user.balance : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
