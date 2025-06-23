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

        // プレイヤーのデータを取得
        TOROpassICsystem.StationData data = plugin.playerData.get(player.getUniqueId());
        if (data == null) {
            return false; // プレイヤーデータが存在しない場合
        }

        if (data.balance >= amount) {
            // 残高を減らす
            data.balance -= amount;
            // 履歴を追加
            data.paymentHistory.add(PaymentHistory.build("Other::deduct", "他のプラグインから引き去り", -amount, data.balance, System.currentTimeMillis() / 1000L));
            plugin.save();
            return true;
        } else {
            // 残高不足
            return false;
        }
    }

    public int getBalance(Player player) {
        TOROpassICsystem.StationData data = plugin.playerData.get(player.getUniqueId());
        return data != null ? data.balance : 0;
    }
}
