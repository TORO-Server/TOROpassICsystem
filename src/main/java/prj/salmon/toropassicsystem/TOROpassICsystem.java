package prj.salmon.toropassicsystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import prj.salmon.toropassicsystem.types.PaymentHistory;
import prj.salmon.toropassicsystem.types.SavingData;
import prj.salmon.toropassicsystem.types.SavingDataJson;

import java.io.IOException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TOROpassICsystem extends JavaPlugin implements Listener, CommandExecutor {
    final HashMap<UUID, StationData> playerData = new HashMap<>();
    private final JSONControler jsonControler = new JSONControler("toropass.json", getDataFolder());
    private HTTPServer httpserver;

    private final NamespacedKey customModelDataKey = new NamespacedKey(this, "custom_model_data");
    private final NamespacedKey ticketTypeKey = new NamespacedKey(this, "ticket_type");
    private final NamespacedKey companyCodeKey = new NamespacedKey(this, "company_code");
    private final NamespacedKey purchaseAmountKey = new NamespacedKey(this, "purchase_amount");
    private final NamespacedKey expiryDateKey = new NamespacedKey(this, "expiry_date");
    private final NamespacedKey checkDigitKey = new NamespacedKey(this, "check_digit");
    private final NamespacedKey routeStartKey = new NamespacedKey(this, "route_start");
    private final NamespacedKey routeEndKey = new NamespacedKey(this, "route_end");

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("charge").setExecutor(this);
        getCommand("autocharge").setExecutor(this);
        getCommand("writecard").setExecutor(this);
        getCommand("toropassrank").setExecutor(this);
        getCommand("webcharge").setExecutor(this);

        try {
            httpserver = new HTTPServer(5744, this);
            jsonControler.initialiseIfNotExists();
            SavingDataJson lastdata = jsonControler.load();
            for (SavingData data : lastdata.data) {
                StationData sdata = new StationData();
                sdata.balance = data.balance;
                sdata.paymentHistory.addAll(data.paymentHistory);
                sdata.autoChargeThreshold = data.autoChargeThreshold;
                sdata.autoChargeAmount = data.autoChargeAmount;
                sdata.webChargePassword = data.webChargePassword;
                playerData.put(data.player, sdata);
            }
        } catch (IOException e) {
            getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        save();
        httpserver.stop();
    }

    void save() {
        SavingDataJson data = new SavingDataJson();
        data.data = new ArrayList<>();
        for (Map.Entry<UUID, StationData> entry : playerData.entrySet()) {
            SavingData sdata = new SavingData();
            sdata.player = entry.getKey();
            sdata.balance = entry.getValue().balance;
            sdata.paymentHistory = new ArrayList<>(entry.getValue().paymentHistory);
            sdata.autoChargeThreshold = entry.getValue().autoChargeThreshold;
            sdata.autoChargeAmount = entry.getValue().autoChargeAmount;
            sdata.webChargePassword = entry.getValue().webChargePassword;
            data.data.add(sdata);
        }
        data.lastupdate = System.currentTimeMillis() / 1000L;
        try {
            jsonControler.save(data);
        } catch (IOException e) {
            getLogger().warning(e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
            return true;
        }
        StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());

        if (command.getName().equalsIgnoreCase("charge")) {
            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "使用方法: /charge <金額>");
                return true;
            }
            try {
                int amount = Integer.parseInt(args[0]);
                if (amount <= 0 || data.balance + amount > 20000) {
                    player.sendMessage(ChatColor.RED + (amount <= 0 ? "チャージ額が不正です" : "最大チャージ額は20000トロポまでです"));
                    return true;
                }
                data.balance += amount;
                data.paymentHistory.add(PaymentHistory.build("Special::charge", "", amount, data.balance, System.currentTimeMillis() / 1000L));
                save();
                player.sendMessage(ChatColor.GREEN + String.valueOf(amount) + "トロポをチャージしました。現在の残高: " + data.balance + "トロポ");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "有効な数値を入力してください。");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("autocharge")) {
            if (args.length == 2) {
                try {
                    int threshold = Integer.parseInt(args[0]);
                    int amount = Integer.parseInt(args[1]);
                    if (threshold < 0 || amount <= 0 || amount > 10000 || threshold > 10000) {
                        player.sendMessage(ChatColor.RED + (threshold < 0 || amount <= 0 ? "不正な値です" : "1万トロポを超える設定はできません"));
                        return true;
                    }
                    data.autoChargeThreshold = threshold;
                    data.autoChargeAmount = amount;
                    player.sendMessage(ChatColor.GREEN + "残高が " + threshold + "トロポを下回った場合に " + amount + "トロポをチャージします。");
                    save();
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "無効な数値が入力されました。");
                }
            } else if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
                data.autoChargeThreshold = null;
                data.autoChargeAmount = null;
                player.sendMessage(ChatColor.GREEN + "オートチャージが停止されました。");
                save();
            } else {
                player.sendMessage(ChatColor.RED + "使用方法: /autocharge <閾値> <チャージ額> または /autocharge stop");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("writecard")) {
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "使用方法: /writecard <券種>-<事業者コード>-<購入金額>-<有効期限>-<チェックデジット>[-<区間開始>-<区間終了>]");
                return true;
            }
            writeCard(player, data, args[0], "Special::writecard");
            return true;
        }

        if (command.getName().equalsIgnoreCase("toropassrank")) {
            Map<UUID, Integer> consumptionMap = new HashMap<>();
            int totalConsumptionAllUsers = 0;
            for (Map.Entry<UUID, StationData> entry : playerData.entrySet()) {
                int totalConsumption = entry.getValue().paymentHistory.stream()
                        .filter(h -> !h.from.startsWith("Special::") && !h.from.startsWith("Shop::"))
                        .mapToInt(h -> Math.abs(h.amount))
                        .sum();
                totalConsumptionAllUsers += totalConsumption;
                if (totalConsumption > 0) consumptionMap.put(entry.getKey(), totalConsumption);
            }

            List<Map.Entry<UUID, Integer>> sortedRanking = consumptionMap.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .limit(10)
                    .toList();

            player.sendMessage(ChatColor.GOLD + "=====TOROpass 移動量ランキング=====");
            int currentRank = 0, previousConsumption = -1, sameRankCount = 0;
            boolean playerInTop10 = false;

            for (Map.Entry<UUID, Integer> entry : sortedRanking) {
                int consumption = entry.getValue();
                if (consumption != previousConsumption) {
                    currentRank += sameRankCount + 1;
                    sameRankCount = 0;
                } else sameRankCount++;
                previousConsumption = consumption;

                String playerName = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(entry.getKey()).getName(), "不明なプレイヤー");
                String distance = formatDistance(consumption * 5.0);
                String message = ChatColor.YELLOW + String.valueOf(currentRank) + "位 " + playerName + " " + consumption + "トロポ " + distance;
                if (entry.getKey().equals(player.getUniqueId())) {
                    message += " (自分)";
                    playerInTop10 = true;
                }
                player.sendMessage(message);
            }

            if (!playerInTop10) {
                int playerConsumption = data.paymentHistory.stream()
                        .filter(h -> !h.from.startsWith("Special::") && !h.from.startsWith("Shop::"))
                        .mapToInt(h -> Math.abs(h.amount))
                        .sum();
                int playerRank = 1, playerSameRankCount = 0;
                for (Map.Entry<UUID, Integer> entry : consumptionMap.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                        .toList()) {
                    if (entry.getKey().equals(player.getUniqueId())) break;
                    if (entry.getValue() == playerConsumption) playerSameRankCount++;
                    else {
                        playerRank += playerSameRankCount + 1;
                        playerSameRankCount = 0;
                    }
                }
                String playerDistance = formatDistance(playerConsumption * 5.0);
                player.sendMessage(ChatColor.YELLOW + String.valueOf(playerRank) + "位 " + player.getName() + "(あなた) " + playerConsumption + "トロポ " + playerDistance);
            }

            String totalDistance = formatDistance(totalConsumptionAllUsers * 5.0);
            player.sendMessage(ChatColor.GOLD + "全ユーザーの移動量: " + totalConsumptionAllUsers + "トロポ " + totalDistance);
            return true;
        }

        if (command.getName().equalsIgnoreCase("webcharge")) {
            String password = generateRandomPassword(8);
            data.webChargePassword = password;
            save();
            player.sendMessage(ChatColor.GREEN + "Webチャージ用パスワードを生成しました: " + password);
            player.sendMessage(ChatColor.YELLOW + "このパスワードを使用してWebからチャージできます。次の /webcharge 実行でこのパスワードは無効になります。");
            return true;
        }

        return false;
    }

    private void writeCard(Player player, StationData data, String cardDataStr, String historyFrom) {
        String[] cardData = cardDataStr.split("-");
        if (cardData.length < 5 || (cardData.length != 7 && (Integer.parseInt(cardData[0]) == 2 || Integer.parseInt(cardData[0]) == 3))) {
            player.sendMessage(ChatColor.RED + "入力内容が正しくありません。コードを再度発行し直してください。");
            return;
        }
        try {
            int ticketType = Integer.parseInt(cardData[0]);
            int companyCode = Integer.parseInt(cardData[1]);
            int purchaseAmount = Integer.parseInt(cardData[2]);
            String expiryDateStr = cardData[3];
            int checkDigit = Integer.parseInt(cardData[4]);

            if (ticketType < 1 || ticketType > 4 || companyCode < 0 || companyCode > 99 || purchaseAmount < 0) {
                player.sendMessage(ChatColor.RED + "コードの内容が正しくありません。コードを再度発行し直してください。");
                return;
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
            dateFormat.setLenient(false);
            Date expiryDate = dateFormat.parse(expiryDateStr);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(expiryDate);
            calendar.set(Calendar.HOUR_OF_DAY, 23);
            calendar.set(Calendar.MINUTE, 59);
            calendar.set(Calendar.SECOND, 59);
            calendar.set(Calendar.MILLISECOND, 999);
            expiryDate = calendar.getTime();

            ItemStack item = player.getInventory().getItemInMainHand();
            if (!isValidICCard(item)) {
                player.sendMessage(ChatColor.RED + "正しいICカードを持って再度実行してください");
                return;
            }

            if (data.balance < purchaseAmount) {
                player.sendMessage(ChatColor.RED + "残高が不足しています。現在の残高: " + data.balance + "トロポ, 購入金額: " + purchaseAmount + "トロポ");
                return;
            }

            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(ticketTypeKey, PersistentDataType.INTEGER, ticketType);
            meta.getPersistentDataContainer().set(companyCodeKey, PersistentDataType.INTEGER, companyCode);
            meta.getPersistentDataContainer().set(purchaseAmountKey, PersistentDataType.INTEGER, purchaseAmount);
            meta.getPersistentDataContainer().set(expiryDateKey, PersistentDataType.LONG, expiryDate.getTime());
            meta.getPersistentDataContainer().set(checkDigitKey, PersistentDataType.INTEGER, checkDigit);

            if (ticketType == 2 || ticketType == 3) {
                meta.getPersistentDataContainer().set(routeStartKey, PersistentDataType.STRING, cardData[5]);
                meta.getPersistentDataContainer().set(routeEndKey, PersistentDataType.STRING, cardData[6]);
            } else {
                meta.getPersistentDataContainer().remove(routeStartKey);
                meta.getPersistentDataContainer().remove(routeEndKey);
            }

            item.setItemMeta(meta);
            data.balance -= purchaseAmount;
            data.paymentHistory.add(PaymentHistory.build(historyFrom, "", purchaseAmount * -1, data.balance, System.currentTimeMillis() / 1000L));
            save();
            player.sendMessage(ChatColor.GREEN + "ICカードに定期券情報を書き込みました。");
        } catch (NumberFormatException | ParseException e) {
            player.sendMessage(ChatColor.RED + "引数の形式が正しくありません。コードを再度発行し直してください。");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !(event.getClickedBlock().getState() instanceof Sign sign)) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isValidICCard(item)) {
            handlePassSaleSign(sign, player, event);
            return;
        }
        event.setCancelled(true);

        StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
        SignSide frontSide = sign.getSide(Side.FRONT);
        SignSide backSide = sign.getSide(Side.BACK);
        String frontLine1 = ChatColor.stripColor(frontSide.getLine(0));
        String frontLine2 = ChatColor.stripColor(frontSide.getLine(1));
        String frontLine3 = ChatColor.stripColor(frontSide.getLine(2));
        String frontLine4 = ChatColor.stripColor(frontSide.getLine(3));
        String backLine2 = ChatColor.stripColor(backSide.getLine(1));
        String backLine3 = ChatColor.stripColor(backSide.getLine(2));

        switch (frontLine1) {
            case "[入場]":
            case "[出場]":
            case "[入出場]":
                handleStationSign(frontLine1, frontLine2, frontLine4, player, item, data, event);
                break;
            case "[チャージ]":
                handleChargeSign(frontLine2, player, data);
                break;
            case "[残高確認]":
                player.sendMessage(ChatColor.GREEN + "現在の残高: " + data.balance + "トロポ");
                break;
            case "[強制出場]":
                if (data.isInStation) {
                    player.sendMessage(ChatColor.GREEN + "強制出場しました。");
                    data.exitStation();
                } else player.sendMessage(ChatColor.RED + "入場記録がありません。");
                break;
            case "[残額調整]":
                handleBalanceAdjustment(frontLine2, player, data);
                break;
            case "[定期券情報削除]":
                handleTicketInfoRemoval(item, player);
                break;
            case "[定期券情報照会]":
                handleTicketInfoInquiry(item, player);
                break;
            case "[乗換]":
                handleTransferSign(frontLine2, frontLine3, frontLine4, player, item, data, event);
                break;
            case "[物販]":
                if ("[IC]".equals(backLine2) && "ここにタッチ".equals(backLine3))
                    handleShopSign(frontLine2, frontLine3, player, data);
                else player.sendMessage(ChatColor.RED + "この看板は物販用に正しく設定されていません。");
                break;
        }
    }

    private void handleStationSign(String signType, String stationName, String companyCodes, Player player, ItemStack item, StationData data, PlayerInteractEvent event) {
        if (signType.equals("[入場]") || (signType.equals("[入出場]") && !data.isInStation)) {
            if (data.isInStation) {
                player.sendMessage(ChatColor.RED + "すでに入場しています。出場してから再度入場してください。");
                return;
            }
            data.enterStation(stationName, companyCodes);
            openFenceGate(companyCodes, event);
            player.sendMessage(ChatColor.GREEN + "入場: " + stationName);
            player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
            data.setRideStartLocation(player.getLocation());
            player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
            return;
        }

        if (!data.isInStation) {
            player.sendMessage(ChatColor.RED + "入場記録がありません。");
            return;
        }

        int fare = data.stationName.equals(stationName) ? 100 : data.calculateFare();
        ItemMeta meta = item.getItemMeta();
        TicketValidationResult result = validateTicket(meta, data, stationName, companyCodes);

        if (result.isExpired && result.ticketType != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
            player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(result.expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
        }

        if (result.isFree) {
            player.sendMessage(ChatColor.GREEN + result.ticketText);
            player.sendMessage(ChatColor.GREEN + "出場: " + stationName);
            player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
            stationName = stationName + "[[定期利用]]";
            data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes, stationName + data.validateCompanyCodes(companyCodes), 0, data.balance, System.currentTimeMillis() / 1000L));
        } else {
            if (data.checkAutoCharge()) {
                player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
            }
            if (data.balance < fare) {
                player.sendMessage(ChatColor.RED + String.valueOf(fare - data.balance) + "トロポ不足しています。チャージしてください。");
                return;
            }
            data.balance -= fare;
            data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes, stationName + data.validateCompanyCodes(companyCodes), fare * -1, data.balance, System.currentTimeMillis() / 1000L));
            player.sendMessage(ChatColor.GREEN + "出場: " + stationName + (data.stationName.equals(stationName) ? "(入場サービス)" : "") + " 引去: " + fare + "トロポ");
            player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
        }

        openFenceGate(companyCodes, event);
        save();
        player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
        data.exitStation();
    }

    private void handleChargeSign(String amountStr, Player player, StationData data) {
        try {
            int chargeAmount = Integer.parseInt(amountStr);
            if (chargeAmount <= 0 || data.balance + chargeAmount > 20000) {
                player.sendMessage(ChatColor.RED + (chargeAmount <= 0 ? "チャージ額が不正です" : "最大チャージ額は20000トロポまでです"));
                return;
            }
            data.balance += chargeAmount;
            data.paymentHistory.add(PaymentHistory.build("Special::charge", "", chargeAmount, data.balance, System.currentTimeMillis() / 1000L));
            save();
            player.sendMessage(ChatColor.GREEN + String.valueOf(chargeAmount) + "トロポをチャージしました。");
            player.sendMessage(ChatColor.GREEN + "現在の残高: " + data.balance + "トロポ");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "チャージ額が不正です");
        }
    }

    private void handleBalanceAdjustment(String balanceStr, Player player, StationData data) {
        try {
            int newBalance = Integer.parseInt(balanceStr);
            if (newBalance < 0) {
                player.sendMessage(ChatColor.RED + "値が不正です。");
                return;
            }
            data.balance = newBalance;
            data.paymentHistory.add(PaymentHistory.build("Special::balanceAdjustment", "", newBalance, data.balance, System.currentTimeMillis() / 1000L));
            save();
            player.sendMessage(ChatColor.GREEN + "新しい残高: " + data.balance + "トロポ");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "無効な残高値です");
        }
    }

    private void handleTicketInfoRemoval(ItemStack item, Player player) {
        ItemMeta meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(ticketTypeKey, PersistentDataType.INTEGER)) {
            meta.getPersistentDataContainer().remove(ticketTypeKey);
            meta.getPersistentDataContainer().remove(companyCodeKey);
            meta.getPersistentDataContainer().remove(purchaseAmountKey);
            meta.getPersistentDataContainer().remove(expiryDateKey);
            meta.getPersistentDataContainer().remove(checkDigitKey);
            meta.getPersistentDataContainer().remove(routeStartKey);
            meta.getPersistentDataContainer().remove(routeEndKey);
            item.setItemMeta(meta);
            player.sendMessage(ChatColor.GREEN + "定期券情報を削除しました。");
        } else player.sendMessage(ChatColor.YELLOW + "定期券情報が登録されていません。");
    }

    private void handleTicketInfoInquiry(ItemStack item, Player player) {
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(ticketTypeKey, PersistentDataType.INTEGER)) {
            player.sendMessage(ChatColor.YELLOW + "このICカードには定期券情報が登録されていません。");
            return;
        }
        Integer ticketType = meta.getPersistentDataContainer().get(ticketTypeKey, PersistentDataType.INTEGER);
        Integer companyCode = meta.getPersistentDataContainer().get(companyCodeKey, PersistentDataType.INTEGER);
        Integer purchaseAmount = meta.getPersistentDataContainer().get(purchaseAmountKey, PersistentDataType.INTEGER);
        Long expiryDateLong = meta.getPersistentDataContainer().get(expiryDateKey, PersistentDataType.LONG);
        Integer checkDigit = meta.getPersistentDataContainer().get(checkDigitKey, PersistentDataType.INTEGER);
        String routeStart = meta.getPersistentDataContainer().get(routeStartKey, PersistentDataType.STRING);
        String routeEnd = meta.getPersistentDataContainer().get(routeEndKey, PersistentDataType.STRING);

        player.sendMessage(ChatColor.GREEN + "===== 定期券情報 =====");
        player.sendMessage(ChatColor.GREEN + "券種: " + (ticketType != null ? ticketType : "不明"));
        player.sendMessage(ChatColor.GREEN + "事業者コード: " + (companyCode != null ? String.format("%02d", companyCode) : "不明"));
        player.sendMessage(ChatColor.GREEN + "購入金額: " + (purchaseAmount != null ? purchaseAmount + "トロポ" : "不明"));
        player.sendMessage(ChatColor.GREEN + "有効期限: " + (expiryDateLong != null ? new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(new Date(expiryDateLong)) : "不明"));
        player.sendMessage(ChatColor.GREEN + "チェックデジット: " + (checkDigit != null ? checkDigit : "不明"));
        if (ticketType != null && (ticketType == 2 || ticketType == 3)) {
            player.sendMessage(ChatColor.GREEN + "定期区間: " + (routeStart != null ? routeStart : "不明") + " - " + (routeEnd != null ? routeEnd : "不明"));
        }
        player.sendMessage(ChatColor.GREEN + "=======================");
    }

    private void handleTransferSign(String departureStation, String transferStation, String companyCodes, Player player, ItemStack item, StationData data, PlayerInteractEvent event) {
        if (!data.isInStation) {
            player.sendMessage(ChatColor.RED + "入場記録がないため、乗換改札を利用できません。先に入場してください。");
            return;
        }
        int fare = data.calculateFare();
        ItemMeta meta = item.getItemMeta();
        TicketValidationResult result = validateTicket(meta, data, departureStation, companyCodes);

        if (result.isExpired && result.ticketType != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
            player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(result.expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
        }

        if (result.isFree) {
            player.sendMessage(ChatColor.GREEN + result.ticketText);
            player.sendMessage(ChatColor.GREEN + "出場: " + departureStation);
            departureStation = departureStation + "[[定期利用]]";
            data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes, departureStation + data.validateCompanyCodes(companyCodes), 0, data.balance, System.currentTimeMillis() / 1000L));
        } else {
            if (data.checkAutoCharge()) {
                player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
            }
            if (data.balance < fare) {
                player.sendMessage(ChatColor.RED + String.valueOf(fare - data.balance) + "トロポ不足しています。チャージしてください。");
                return;
            }
            data.balance -= fare;
            data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes, departureStation + data.validateCompanyCodes(companyCodes), fare * -1, data.balance, System.currentTimeMillis() / 1000L));
            player.sendMessage(ChatColor.GREEN + "出場: " + departureStation + " 引去: " + fare + "トロポ");
        }

        openFenceGate(companyCodes, event);
        save();
        data.exitStation();
        data.enterStation(transferStation, companyCodes);
        player.sendMessage(ChatColor.GREEN + "入場: " + transferStation);
        player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
        data.setRideStartLocation(player.getLocation());
        player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
    }

    private void handleShopSign(String storeName, String amountStr, Player player, StationData data) {
        try {
            int amount = Integer.parseInt(amountStr);
            if (data.checkAutoCharge()) {
                player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
            }
            if (data.balance < amount) {
                player.sendMessage(ChatColor.RED + String.valueOf(amount - data.balance) + "トロポ不足しています。チャージしてください。");
                return;
            }
            data.balance -= amount;
            data.paymentHistory.add(PaymentHistory.build("Shop::" + storeName, "", amount * -1, data.balance, System.currentTimeMillis() / 1000L));
            save();
            player.sendMessage(ChatColor.GREEN + "店舗: " + storeName);
            player.sendMessage(ChatColor.GREEN + "支払額: " + amount + "トロポ");
            player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
            player.playSound(player.getLocation(), "minecraft:block.note_block.bell", 1.0F, 1.0F);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "店舗看板の金額が不正です。管理者に連絡してください。");
        }
    }

    private void handlePassSaleSign(Sign sign, Player player, PlayerInteractEvent event) {
        if (player.isSneaking()) return;
        SignSide frontSide = sign.getSide(Side.FRONT);
        String frontLine1 = ChatColor.stripColor(frontSide.getLine(0));
        if (!"[TOROpass販売]".equals(frontLine1)) return;
        event.setCancelled(true);

        try {
            int customModelData = Integer.parseInt(ChatColor.stripColor(frontSide.getLine(1)));
            String[] costParts = ChatColor.stripColor(frontSide.getLine(2)).split(" ");
            if (costParts.length != 2) {
                player.sendMessage(ChatColor.RED + "看板の3行目が不正です（例: 3 diamond）");
                return;
            }
            int costAmount = Integer.parseInt(costParts[0]);
            Material costMaterial = Material.matchMaterial(costParts[1].toLowerCase().replace("s$", ""));
            if (costMaterial == null) {
                player.sendMessage(ChatColor.RED + "無効な素材名です: " + costParts[1]);
                return;
            }
            if (!player.getInventory().contains(costMaterial, costAmount)) {
                player.sendMessage(ChatColor.RED + String.valueOf(costAmount) + "個の" + costMaterial.name().toLowerCase() + "が不足しています。");
                return;
            }
            ItemStack costItem = new ItemStack(costMaterial, costAmount);
            player.getInventory().removeItem(costItem);
            ItemStack pass = new ItemStack(Material.PAPER, 1);
            ItemMeta meta = pass.getItemMeta();
            meta.setCustomModelData(customModelData);
            meta.setDisplayName(ChatColor.RESET + getPassName(customModelData));
            pass.setItemMeta(meta);
            player.getInventory().addItem(pass);
            player.sendMessage(ChatColor.GREEN + getPassName(customModelData) + "を購入しました！");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "看板の2行目または3行目の形式が不正です。");
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "購入処理中にエラーが発生しました。");
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line1 = ChatColor.stripColor(event.getLine(0));
        Player player = event.getPlayer();
        switch (line1) {
            case "[入場]":
            case "[出場]":
            case "[チャージ]":
            case "[入出場]":
                if (ChatColor.stripColor(event.getLine(1)).isEmpty()) {
                    player.sendMessage(ChatColor.RED + "必要な情報を2行目に記載してください。");
                    event.setCancelled(true);
                } else player.sendMessage(ChatColor.GREEN + "看板が正常に設定されました。");
                break;
            case "[定期券情報削除]":
            case "[定期券情報照会]":
                event.setLine(0, line1);
                player.sendMessage(ChatColor.GREEN + line1 + "看板が正常に設定されました。");
                break;
            case "[物販]":
                handleShopSignSetup(event, player);
                break;
            case "[乗換]":
                handleTransferSignSetup(event, player);
                break;
        }
    }

    private void handleShopSignSetup(SignChangeEvent event, Player player) {
        String storeName = ChatColor.stripColor(event.getLine(1));
        String amountStr = ChatColor.stripColor(event.getLine(2));
        if (storeName.isEmpty() || amountStr.isEmpty()) {
            player.sendMessage(ChatColor.RED + "2行目に店舗名、3行目に金額を記載してください。");
            event.setCancelled(true);
            return;
        }
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                player.sendMessage(ChatColor.RED + "金額は正の整数で指定してください。");
                event.setCancelled(true);
                return;
            }
            event.setLine(0, "[物販]");
            event.setLine(1, storeName);
            event.setLine(2, String.valueOf(amount));
            org.bukkit.block.Sign signState = (org.bukkit.block.Sign) event.getBlock().getState();
            SignSide backSide = signState.getSide(Side.BACK);
            backSide.setLine(1, ChatColor.GREEN + "[IC]");
            backSide.setLine(2, "ここにタッチ");
            signState.update(true);
            player.sendMessage(ChatColor.GREEN + "物販看板が正常に設定されました。");
            player.sendMessage(ChatColor.GREEN + "裏面に支払い情報が書き込まれました。");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "3行目の金額が正しい数値ではありません。");
            event.setCancelled(true);
        }
    }

    private void handleTransferSignSetup(SignChangeEvent event, Player player) {
        String departureStation = ChatColor.stripColor(event.getLine(1));
        String transferStation = ChatColor.stripColor(event.getLine(2));
        String companyCodesLine = ChatColor.stripColor(event.getLine(3));
        if (departureStation.isEmpty() || transferStation.isEmpty()) {
            player.sendMessage(ChatColor.RED + "2行目に出発駅、3行目に乗り換え先駅を記載してください。");
            event.setCancelled(true);
            return;
        }
        event.setLine(0, "[乗換]");
        event.setLine(1, departureStation);
        event.setLine(2, transferStation);
        if (!companyCodesLine.isEmpty()) {
            String[] codes = companyCodesLine.split(" ");
            List<String> validCodes = new ArrayList<>();
            for (String code : codes) {
                try {
                    int codeNum = Integer.parseInt(code);
                    if (codeNum < 0 || codeNum > 99) {
                        player.sendMessage(ChatColor.RED + "事業者コードは00から99までの整数で指定してください。無効な値: " + code);
                        event.setCancelled(true);
                        return;
                    }
                    validCodes.add(String.format("%02d", codeNum));
                } catch (NumberFormatException e) {
                    return;
                }
            }
            event.setLine(3, String.join(" ", validCodes));
        }
        player.sendMessage(ChatColor.GREEN + "乗換看板が正常に設定されました。");
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
            if (data.isInStation) data.setRideStartLocation(player.getLocation());
        }
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!event.getVehicle().getPassengers().isEmpty() && event.getVehicle().getPassengers().get(0) instanceof Player player) {
            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
            if (data.isInStation) data.addTravelDistance(event.getTo());
        }
    }

    private static boolean isValidICCard(ItemStack item) {
        return item != null && item.getType() == Material.PAPER && item.getItemMeta().hasCustomModelData();
    }

    private TicketValidationResult validateTicket(ItemMeta meta, StationData data, String exitStation, String companyCodesLine) {
        TicketValidationResult result = new TicketValidationResult();
        Integer ticketType = meta.getPersistentDataContainer().get(ticketTypeKey, PersistentDataType.INTEGER);
        Integer companyCode = meta.getPersistentDataContainer().get(companyCodeKey, PersistentDataType.INTEGER);
        Long expiryDateLong = meta.getPersistentDataContainer().get(expiryDateKey, PersistentDataType.LONG);
        String routeStart = meta.getPersistentDataContainer().get(routeStartKey, PersistentDataType.STRING);
        String routeEnd = meta.getPersistentDataContainer().get(routeEndKey, PersistentDataType.STRING);
        result.ticketType = ticketType;
        result.expiryDateLong = expiryDateLong != null ? expiryDateLong : 0L;

        if (expiryDateLong != null && new Date(expiryDateLong).before(new Date())) {
            result.isExpired = true;
        }

        if (ticketType == null || companyCode == null || result.isExpired) return result;

        String companyCodeStr = String.format("%02d", companyCode);
        List<String> exitCompanyCodes = data.validateCompanyCodes(companyCodesLine);

        if (ticketType == 1 && (companyCode == 99 || (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr)))) {
            result.isFree = true;
            result.ticketText = companyCode == 99 ? "定期利用:TORO全線" : "定期利用:全線定期";
        } else if (ticketType == 4 && !result.isExpired) {
            if (companyCode == 99) {
                result.isFree = true;
                result.ticketText = "定期利用:TORO全線";
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                Date purchaseDate = new Date(expiryDateLong);
                Date today = new Date();
                Calendar todayCalendar = Calendar.getInstance();
                todayCalendar.setTime(today);
                todayCalendar.set(Calendar.HOUR_OF_DAY, 23);
                todayCalendar.set(Calendar.MINUTE, 59);
                todayCalendar.set(Calendar.SECOND, 59);
                todayCalendar.set(Calendar.MILLISECOND, 999);
                if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                        sdf.format(purchaseDate).equals(sdf.format(today)) && !today.after(new Date(expiryDateLong))) {
                    result.isFree = true;
                    result.ticketText = "定期利用:1日乗車券";
                }
            }
        } else if ((ticketType == 2 || ticketType == 3) && data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                (routeStart.equals(data.stationName) && routeEnd.equals(exitStation) || routeStart.equals(exitStation) && routeEnd.equals(data.stationName))) {
            result.isFree = true;
            result.ticketText = "定期利用:通勤･通学定期";
        }
        return result;
    }

    private static class TicketValidationResult {
        boolean isFree = false;
        boolean isExpired = false;
        String ticketText = "";
        Integer ticketType = null;
        long expiryDateLong = 0L;
    }

    public void openFenceGate(String frontLine4, PlayerInteractEvent event) {
        if (frontLine4 == null) return;
        Matcher matcher = Pattern.compile("\\(\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*\\d+\\s*\\)").matcher(frontLine4);
        if (!matcher.find()) return;

        String[] parts = matcher.group().replaceAll("[()\\s]", "").split(",");
        int offsetX = Integer.parseInt(parts[0]);
        int offsetY = Integer.parseInt(parts[1]);
        int offsetZ = Integer.parseInt(parts[2]);
        int seconds = Integer.parseInt(parts[3]);

        Location signLoc = event.getClickedBlock().getState().getLocation();
        Location targetLoc = signLoc.clone().add(offsetX, offsetY, offsetZ);
        Block targetBlock = targetLoc.getBlock();

        if (!targetBlock.getType().name().endsWith("_FENCE_GATE")) return;
        BlockData blockData = targetBlock.getBlockData();
        if (!(blockData instanceof Openable gate) || gate.isOpen()) return;

        gate.setOpen(true);
        targetBlock.setBlockData(gate);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Block b = targetLoc.getBlock();
            if (b.getType().name().endsWith("_FENCE_GATE")) {
                BlockData bd = b.getBlockData();
                if (bd instanceof Openable g) {
                    g.setOpen(false);
                    b.setBlockData(g);
                }
            }
        }, seconds * 20L);
    }

    private String getPassName(int customModelData) {
        return switch (customModelData) {
            case 1 -> "TORO CARD";
            case 3 -> "Minu pass";
            case 4 -> "KOUDAN pass";
            case 5 -> "Rupica";
            case 6 -> "ShakechanRupica";
            case 7 -> "TOHOCA";
            default -> "TOROpass";
        };
    }

    private String formatDistance(double meters) {
        return meters >= 1000 ? String.format("%.3f", meters / 1000).replaceAll("0*$", "").replaceAll("\\.$", "") + "km" :
                String.format("%.0f", meters) + "m";
    }

    private String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        return password.toString();
    }

    public static class StationData {
        public boolean isInStation = false;
        public int balance = 0;
        public String stationName = "";
        private Location rideStartLocation;
        private double travelDistance = 0;
        public ArrayList<PaymentHistory> paymentHistory = new ArrayList<>();
        public Integer autoChargeThreshold = null;
        public Integer autoChargeAmount = null;
        public String webChargePassword = null;
        private List<String> entryCompanyCodes = new ArrayList<>();

        public void enterStation(String stationName, String companyCodesLine) {
            this.isInStation = true;
            this.stationName = stationName;
            this.travelDistance = 0;
            this.entryCompanyCodes = validateCompanyCodes(companyCodesLine);
        }

        public void exitStation() {
            this.isInStation = false;
            this.stationName = "";
            this.travelDistance = 0;
            this.entryCompanyCodes.clear();
        }

        public List<String> validateCompanyCodes(String line) {
            List<String> validCodes = new ArrayList<>();
            if (line == null || line.trim().isEmpty()) return validCodes;
            String[] codes = line.split("[,\\s]+");
            for (String code : codes) {
                try {
                    int codeNum = Integer.parseInt(code);
                    if (codeNum >= 0 && codeNum <= 99) validCodes.add(String.format("%02d", codeNum));
                } catch (NumberFormatException ignored) {}
            }
            return validCodes;
        }

        public void setRideStartLocation(Location location) {
            this.rideStartLocation = location;
        }

        public void addTravelDistance(Location newLocation) {
            if (rideStartLocation != null) {
                travelDistance += rideStartLocation.distance(newLocation);
                rideStartLocation = newLocation;
            }
        }

        public int calculateFare() {
            return (int) (travelDistance * 0.2);
        }

        public boolean checkAutoCharge() {
            if (autoChargeThreshold != null && autoChargeAmount != null && balance < autoChargeThreshold) {
                balance += autoChargeAmount;
                paymentHistory.add(PaymentHistory.build("Special::autocharge", "", autoChargeAmount, balance, System.currentTimeMillis() / 1000L));
                return true;
            }
            return false;
        }
    }

    public static class HTTPServer extends NanoHTTPD {
        private final TOROpassICsystem mainclass;
        private final ObjectMapper mapper = new ObjectMapper();

        public HTTPServer(int port, TOROpassICsystem mainclass) throws IOException {
            super(port);
            start(SOCKET_READ_TIMEOUT, false);
            this.mainclass = mainclass;
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Method method = session.getMethod();

            if (uri.equals("/")) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"OK\"}");
            } else if (uri.startsWith("/api/balance/")) {
                String playerName = uri.substring("/api/balance/".length());
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (Player Not Found)");
                }
                StationData data = mainclass.playerData.get(player.getUniqueId());
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"balance\": " + data.balance + "}");
            } else if (uri.startsWith("/api/history/") || uri.startsWith("/api/fullhistory/")) {
                String playerName = uri.substring(uri.indexOf("/", 5) + 1);
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (Player Not Found)");
                }
                StationData data = mainclass.playerData.get(player.getUniqueId());
                List<PaymentHistory> history = new ArrayList<>(data.paymentHistory);
                Collections.reverse(history);
                if (uri.startsWith("/api/history/") && history.size() > 100) history = history.subList(0, 100);
                try {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", mapper.writeValueAsString(history));
                } catch (JsonProcessingException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 Internal Server Error (JSON Error)");
                }
            } else if (uri.equals("/api/webcharge") && method == Method.POST) {
                try {
                    String body = getRequestBody(session);
                    Map<String, Object> request = mapper.readValue(body, Map.class);
                    String playerName = (String) request.get("playername");
                    String password = (String) request.get("password");
                    Object chargeBalanceObj = request.get("chargebalance");
                    if (playerName == null || password == null || chargeBalanceObj == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Missing required fields\"}");
                    }

                    int chargeBalance;
                    try {
                        chargeBalance = Integer.parseInt(chargeBalanceObj.toString());
                    } catch (NumberFormatException e) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Invalid charge amount\"}");
                    }

                    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                    if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player not found\"}");
                    }

                    StationData data = mainclass.playerData.get(player.getUniqueId());
                    if (data.webChargePassword == null || !data.webChargePassword.equals(password)) {
                        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\": \"ERROR\", \"message\": \"Invalid password\"}");
                    }

                    if (chargeBalance <= 0 || data.balance + chargeBalance > 20000) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": " +
                                (chargeBalance <= 0 ? "\"Invalid charge amount\"" : "\"Maximum balance is 20000 Tropo\"") + "}");
                    }

                    data.balance += chargeBalance;
                    data.paymentHistory.add(PaymentHistory.build("Special::webcharge", "", chargeBalance, data.balance, System.currentTimeMillis() / 1000L));
                    mainclass.save();

                    Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
                    if (onlinePlayer != null) {
                        onlinePlayer.sendMessage(ChatColor.GREEN + String.valueOf(chargeBalance) + "トロポをWebからチャージしました。現在の残高: " + data.balance + "トロポ");
                    }

                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"OK\", \"balance\": " + data.balance + "}");
                } catch (IOException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\": \"ERROR\", \"message\": \"Internal server error\"}");
                }
            } else if (uri.equals("/api/writecard") && method == Method.POST) {
                try {
                    String body = getRequestBody(session);
                    Map<String, Object> request = mapper.readValue(body, Map.class);
                    String playerName = (String) request.get("playername");
                    String password = (String) request.get("password");
                    String cardData = (String) request.get("carddata");
                    if (playerName == null || password == null || cardData == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Missing required fields\"}");
                    }

                    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                    if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player not found\"}");
                    }

                    Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
                    if (onlinePlayer == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player must be online to write card\"}");
                    }

                    StationData data = mainclass.playerData.get(player.getUniqueId());
                    if (data.webChargePassword == null || !data.webChargePassword.equals(password)) {
                        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\": \"ERROR\", \"message\": \"Invalid password\"}");
                    }

                    ItemStack item = findValidICCard(onlinePlayer);
                    if (item == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"No valid IC card found in player's inventory\"}");
                    }

                    mainclass.writeCard(onlinePlayer, data, cardData, "Special::webwritecard");
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"OK\", \"balance\": " + data.balance + "}");
                } catch (IOException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\": \"ERROR\", \"message\": \"Internal server error\"}");
                }
            }else if (uri.equals("/api/getcardinfo") && method == Method.POST) {
                try {
                    String body = getRequestBody(session);
                    Map<String, Object> request = mapper.readValue(body, Map.class);
                    String playerName = (String) request.get("playername");
                    String password = (String) request.get("password");
                    if (playerName == null || password == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Missing required fields\"}");
                    }

                    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                    if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player not found\"}");
                    }

                    Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
                    if (onlinePlayer == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player must be online\"}");
                    }

                    StationData data = mainclass.playerData.get(player.getUniqueId());
                    if (data.webChargePassword == null || !data.webChargePassword.equals(password)) {
                        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\": \"ERROR\", \"message\": \"Invalid password\"}");
                    }

                    ItemStack item = TOROpassICsystem.findValidICCard(onlinePlayer);
                    if (item == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"No valid IC card found in player's inventory\"}");
                    }

                    Map<String, Object> cardInfo = mainclass.getCardInfo(item);
                    if (cardInfo.isEmpty()) {
                        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"OK\", \"message\": \"No ticket information registered\"}");
                    }

                    return newFixedLengthResponse(Response.Status.OK, "application/json", mapper.writeValueAsString(Map.of("status", "OK", "cardInfo", cardInfo)));
                } catch (IOException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\": \"ERROR\", \"message\": \"Internal server error\"}");
                }
            } else if (uri.equals("/api/delcardinfo") && method == Method.POST) {
                try {
                    String body = getRequestBody(session);
                    Map<String, Object> request = mapper.readValue(body, Map.class);
                    String playerName = (String) request.get("playername");
                    String password = (String) request.get("password");
                    if (playerName == null || password == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Missing required fields\"}");
                    }

                    OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                    if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player not found\"}");
                    }

                    Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
                    if (onlinePlayer == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"Player must be online\"}");
                    }

                    StationData data = mainclass.playerData.get(player.getUniqueId());
                    if (data.webChargePassword == null || !data.webChargePassword.equals(password)) {
                        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\": \"ERROR\", \"message\": \"Invalid password\"}");
                    }

                    ItemStack item = TOROpassICsystem.findValidICCard(onlinePlayer);
                    if (item == null) {
                        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"status\": \"ERROR\", \"message\": \"No valid IC card found in player's inventory\"}");
                    }

                    boolean deleted = mainclass.deleteCardInfo(item, onlinePlayer);
                    if (!deleted) {
                        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"OK\", \"message\": \"No ticket information to delete\"}");
                    }

                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"OK\", \"message\": \"Ticket information deleted\"}");
                } catch (IOException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\": \"ERROR\", \"message\": \"Internal server error\"}");
                }
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (URI Error)");
        }

        private String getRequestBody(IHTTPSession session) throws IOException {
            int contentLength = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
            byte[] buffer = new byte[contentLength];
            session.getInputStream().read(buffer, 0, contentLength);
            return new String(buffer);
        }
    }

    private static ItemStack findValidICCard(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isValidICCard(item)) {
                return item;
            }
        }
        return null;
    }
    private Map<String, Object> getCardInfo(ItemStack item) {
        Map<String, Object> cardInfo = new HashMap<>();
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(ticketTypeKey, PersistentDataType.INTEGER)) {
            return cardInfo;
        }

        Integer ticketType = meta.getPersistentDataContainer().get(ticketTypeKey, PersistentDataType.INTEGER);
        Integer companyCode = meta.getPersistentDataContainer().get(companyCodeKey, PersistentDataType.INTEGER);
        Integer purchaseAmount = meta.getPersistentDataContainer().get(purchaseAmountKey, PersistentDataType.INTEGER);
        Long expiryDateLong = meta.getPersistentDataContainer().get(expiryDateKey, PersistentDataType.LONG);
        Integer checkDigit = meta.getPersistentDataContainer().get(checkDigitKey, PersistentDataType.INTEGER);
        String routeStart = meta.getPersistentDataContainer().get(routeStartKey, PersistentDataType.STRING);
        String routeEnd = meta.getPersistentDataContainer().get(routeEndKey, PersistentDataType.STRING);

        if (ticketType != null) cardInfo.put("ticketType", ticketType);
        if (companyCode != null) cardInfo.put("companyCode", String.format("%02d", companyCode));
        if (purchaseAmount != null) cardInfo.put("purchaseAmount", purchaseAmount);
        if (expiryDateLong != null) {
            cardInfo.put("expiryDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expiryDateLong)));
        }
        if (checkDigit != null) cardInfo.put("checkDigit", checkDigit);
        if (routeStart != null) cardInfo.put("routeStart", routeStart);
        if (routeEnd != null) cardInfo.put("routeEnd", routeEnd);

        return cardInfo;
    }

    private boolean deleteCardInfo(ItemStack item, Player player) {
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(ticketTypeKey, PersistentDataType.INTEGER)) {
            return false; // 定期券情報なし
        }

        meta.getPersistentDataContainer().remove(ticketTypeKey);
        meta.getPersistentDataContainer().remove(companyCodeKey);
        meta.getPersistentDataContainer().remove(purchaseAmountKey);
        meta.getPersistentDataContainer().remove(expiryDateKey);
        meta.getPersistentDataContainer().remove(checkDigitKey);
        meta.getPersistentDataContainer().remove(routeStartKey);
        meta.getPersistentDataContainer().remove(routeEndKey);
        item.setItemMeta(meta);

        player.sendMessage(ChatColor.GREEN + "Webから定期券情報を削除しました。");
        return true;
    }
}