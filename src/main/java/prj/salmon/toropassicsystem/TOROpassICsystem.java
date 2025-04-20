package prj.salmon.toropassicsystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TOROpassICsystem extends JavaPlugin implements Listener, CommandExecutor {
    // 乗車中データ・残高
    public final HashMap<UUID, StationData> playerData = new HashMap<>();
    // データの保存
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
        this.getCommand("charge").setExecutor(this);
        this.getCommand("autocharge").setExecutor(this);
        this.getCommand("writecard").setExecutor(this);

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
            sdata.paymentHistory = new ArrayList<>();
            sdata.paymentHistory.addAll(entry.getValue().paymentHistory);
            sdata.autoChargeThreshold = entry.getValue().autoChargeThreshold;
            sdata.autoChargeAmount = entry.getValue().autoChargeAmount;
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
        if (command.getName().equalsIgnoreCase("charge")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "使用方法: /charge <金額>");
                return true;
            }

            try {
                int amount = Integer.parseInt(args[0]);
                if (amount < 0) {
                    player.sendMessage(ChatColor.RED + "チャージ額が不正です");
                    return true;
                }
                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());

                if (data.balance + amount > 20000) {
                    player.sendMessage(ChatColor.RED + "最大チャージ額は20000トロポまでです");
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
            if (!(sender instanceof Player player)) {
                sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            if (args.length == 2) {
                try {
                    int threshold = Integer.parseInt(args[0]);
                    int amount = Integer.parseInt(args[1]);

                    if (threshold < 0 || amount < 0) {
                        player.sendMessage(ChatColor.RED + "不正な値です");
                        return true;
                    }

                    if (amount > 10000) {
                        player.sendMessage(ChatColor.RED + "1万トロポを超えるオートチャージはできません");
                        return true;
                    }

                    if (threshold > 10000) {
                        player.sendMessage(ChatColor.RED + "1万トロポを超えるときはオートチャージ設定はできません");
                        return true;
                    }

                    StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                    data.autoChargeThreshold = threshold;
                    data.autoChargeAmount = amount;
                    player.sendMessage(ChatColor.GREEN + "残高が " + threshold + "トロポを下回った場合に " + amount + "トロポをチャージします。");

                    save();
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "無効な数値が入力されました。");
                }
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                data.autoChargeThreshold = null;
                data.autoChargeAmount = null;
                player.sendMessage(ChatColor.GREEN + "オートチャージが停止されました。");

                save();
                return true;
            }

            player.sendMessage(ChatColor.RED + "使用方法:<チャージする額> または /autocharge stopで停止");
            return true;
        }
        if (command.getName().equalsIgnoreCase("writecard")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            if (args.length < 1 || args[0].trim().isEmpty()) {
                player.sendMessage(ChatColor.RED + "使用方法: /writecard <券種>-<事業者コード>-<購入金額>-<有効期限>-<チェックデジット>");
                return true;
            }

            String[] cardData = args[0].split("-");
            if (cardData.length < 5) {
                player.sendMessage(ChatColor.RED + "入力内容が正しくありません。コードを再度発行し直してください。" + cardData.length);
                return true;
            }

            try {
                int ticketType = Integer.parseInt(cardData[0]);
                int companyCode = Integer.parseInt(cardData[1]);
                int purchaseAmount = Integer.parseInt(cardData[2]);
                String expiryDateStr = cardData[3];
                int checkDigit = Integer.parseInt(cardData[4]);

                if (ticketType < 1 || ticketType > 4) {
                    player.sendMessage(ChatColor.RED + "券種番号が正しくありません。コードを再度発行し直してください。");
                    return true;
                }

                if (companyCode < 0 || companyCode > 99) {
                    player.sendMessage(ChatColor.RED + "事業者コードが正しくありません。コードを再度発行し直してください。");
                    return true;
                }

                if (purchaseAmount < 0) {
                    player.sendMessage(ChatColor.RED + "購入金額が正しくありません。コードを再度発行し直してください。");
                    return true;
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
                    return true;
                }

                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(ticketTypeKey, PersistentDataType.INTEGER, ticketType);
                meta.getPersistentDataContainer().set(companyCodeKey, PersistentDataType.INTEGER, companyCode);
                meta.getPersistentDataContainer().set(purchaseAmountKey, PersistentDataType.INTEGER, purchaseAmount);
                meta.getPersistentDataContainer().set(expiryDateKey, PersistentDataType.LONG, expiryDate.getTime());
                meta.getPersistentDataContainer().set(checkDigitKey, PersistentDataType.INTEGER, checkDigit);

                if (ticketType == 2 || ticketType == 3) {
                    if (cardData.length != 7) {
                        player.sendMessage(ChatColor.RED + "定期区間が正しくありません。コードを再度発行し直してください。");
                        return true;
                    }
                    meta.getPersistentDataContainer().set(routeStartKey, PersistentDataType.STRING, cardData[5]);
                    meta.getPersistentDataContainer().set(routeEndKey, PersistentDataType.STRING, cardData[6]);
                } else {
                    meta.getPersistentDataContainer().remove(routeStartKey);
                    meta.getPersistentDataContainer().remove(routeEndKey);
                }

                item.setItemMeta(meta);

                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                if (data.balance < purchaseAmount) {
                    player.sendMessage(ChatColor.RED + "残高が不足しています。現在の残高: " + data.balance + "トロポ, 購入金額: " + purchaseAmount + "トロポ");
                    return true;
                }

                data.balance -= purchaseAmount;
                data.paymentHistory.add(PaymentHistory.build("Special::writecard", "", purchaseAmount * -1, data.balance, System.currentTimeMillis() / 1000L));
                save();

                player.sendMessage(ChatColor.GREEN + "ICカードに定期券情報を書き込みました。");

            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "引数の形式が正しくありません。コードを再度発行し直してください。");
            } catch (ParseException e) {
                player.sendMessage(ChatColor.RED + "有効期限が正しくありません。コードを再度発行し直してください。");
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("toropassrank")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
                return true;
            }

            Map<UUID, Integer> consumptionMap = new HashMap<>();
            int totalConsumptionAllUsers = 0;
            for (Map.Entry<UUID, StationData> entry : playerData.entrySet()) {
                int totalConsumption = 0;
                for (PaymentHistory history : entry.getValue().paymentHistory) {
                    if (!history.from.startsWith("Special::") && !history.from.startsWith("Shop::")) {
                        totalConsumption += Math.abs(history.amount);
                    }
                }
                totalConsumptionAllUsers += totalConsumption;
                if (totalConsumption > 0) {
                    consumptionMap.put(entry.getKey(), totalConsumption);
                }
            }

            List<Map.Entry<UUID, Integer>> sortedRanking = consumptionMap.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .limit(10)
                    .toList();

            player.sendMessage(ChatColor.GOLD + "=====TOROpass 移動量ランキング=====");
            int currentRank = 0;
            int previousConsumption = -1;
            int sameRankCount = 0;
            boolean playerInTop10 = false;

            for (Map.Entry<UUID, Integer> entry : sortedRanking) {
                int consumption = entry.getValue();
                if (consumption != previousConsumption) {
                    currentRank += sameRankCount + 1;
                    sameRankCount = 0;
                } else {
                    sameRankCount++;
                }
                previousConsumption = consumption;

                String playerName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                if (playerName == null) {
                    playerName = "不明なプレイヤー";
                }
                double meters = consumption * 5.0;
                String distance = meters >= 1000 ? String.format("%.3f", meters / 1000).replaceAll("0*$", "").replaceAll("\\.$", "") + "km" : String.format("%.0f", meters) + "m";
                String message = ChatColor.YELLOW.toString() + currentRank + "位 " + playerName + " " + consumption + "トロポ " + distance;
                if (entry.getKey().equals(player.getUniqueId())) {
                    message += " (自分)";
                    playerInTop10 = true;
                }
                player.sendMessage(message);
            }

            if (!playerInTop10) {
                StationData playerDataEntry = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                int playerConsumption = 0;
                for (PaymentHistory history : playerDataEntry.paymentHistory) {
                    if (!history.from.startsWith("Special::") && !history.from.startsWith("Shop::")) {
                        playerConsumption += Math.abs(history.amount);
                    }
                }

                int playerRank = 1;
                int playerSameRankCount = 0;
                for (Map.Entry<UUID, Integer> entry : consumptionMap.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                        .toList()) {
                    if (entry.getKey().equals(player.getUniqueId())) {
                        break;
                    }
                    if (entry.getValue() == playerConsumption) {
                        playerSameRankCount++;
                    } else {
                        playerRank += playerSameRankCount + 1;
                        playerSameRankCount = 0;
                    }
                }

                double playerMeters = playerConsumption * 5.0;
                String playerDistance = playerMeters >= 1000 ? String.format("%.3f", playerMeters / 1000).replaceAll("0*$", "").replaceAll("\\.$", "") + "km" : String.format("%.0f", playerMeters) + "m";
                player.sendMessage(ChatColor.YELLOW.toString() + playerRank + "位 " + player.getName() + "(あなた) " + playerConsumption + "トロポ " + playerDistance);
            }

            double totalMeters = totalConsumptionAllUsers * 5.0;
            String totalDistance = totalMeters >= 1000 ? String.format("%.3f", totalMeters / 1000).replaceAll("0*$", "").replaceAll("\\.$", "") + "km" : String.format("%.0f", totalMeters) + "m";
            player.sendMessage(ChatColor.GOLD + "全ユーザーの移動量: " + totalConsumptionAllUsers + "トロポ " + totalDistance);

            return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        SignSide frontSide = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        SignSide backSide = sign.getSide(org.bukkit.block.sign.Side.BACK);
        String frontLine1 = ChatColor.stripColor(frontSide.getLine(0));
        String frontLine2 = ChatColor.stripColor(frontSide.getLine(1));
        String frontLine3 = ChatColor.stripColor(frontSide.getLine(2));
        String frontLine4 = ChatColor.stripColor(frontSide.getLine(3));
        String backLine2 = ChatColor.stripColor(backSide.getLine(1));
        String backLine3 = ChatColor.stripColor(backSide.getLine(2));

        if (isValidICCard(item)) {
            event.setCancelled(true);

            // [入場] と [出場] の処理
            if ("[入場]".equals(frontLine1) || "[出場]".equals(frontLine1) || "[入出場]".equals(frontLine1)) {
                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                ItemMeta meta = item.getItemMeta();
                Integer ticketType = meta.getPersistentDataContainer().get(ticketTypeKey, PersistentDataType.INTEGER);
                Integer companyCode = meta.getPersistentDataContainer().get(companyCodeKey, PersistentDataType.INTEGER);
                Long expiryDateLong = meta.getPersistentDataContainer().get(expiryDateKey, PersistentDataType.LONG);
                String routeStart = meta.getPersistentDataContainer().get(routeStartKey, PersistentDataType.STRING);
                String routeEnd = meta.getPersistentDataContainer().get(routeEndKey, PersistentDataType.STRING);

                List<String> exitCompanyCodes = data.validateCompanyCodes(frontLine4);
                String companyCodeStr = companyCode != null ? String.format("%02d", companyCode) : null;

                if ("[入場]".equals(frontLine1)) {
                    if (data.isInStation) {
                        player.sendMessage(ChatColor.RED + "すでに入場しています。出場してから再度入場してください。");
                        return;
                    }
                    data.enterStation(frontLine2, frontLine4);
                    openFenceGate(frontLine4,event);
                    player.sendMessage(ChatColor.GREEN + "入場: " + frontLine2);
                    player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                    data.setRideStartLocation(player.getLocation());
                    player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                } else if ("[出場]".equals(frontLine1)) {
                    if (!data.isInStation) {
                        player.sendMessage(ChatColor.RED + "入場記録がありません。");
                        return;
                    }
                    int fare = data.calculateFare();

                    // 同じ駅で出場した場合は強制的に100トロポを引く
                    if (data.stationName != null && data.stationName.equals(frontLine2)) {
                        fare = 100;
                    }

                    boolean isFree = false;
                    boolean isExpired = false;

                    if (expiryDateLong != null) {
                        Date expiryDate = new Date(expiryDateLong);
                        Date now = new Date();
                        isExpired = expiryDate.before(now);
                        if (isExpired) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
                            player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
                        }
                    }

                    if (ticketType != null && companyCode != null && !isExpired) {
                        if (ticketType == 1) {
                            if (companyCode == 99) {
                                isFree = true;
                                player.sendMessage(ChatColor.GREEN + "定期利用:TORO全線");
                            } else if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr)) {
                                isFree = true;
                                player.sendMessage(ChatColor.GREEN + "定期利用:全線定期");
                            }
                        } else if (ticketType == 4) {
                            if (companyCode == 99) {
                                isFree = true;
                                player.sendMessage(ChatColor.GREEN + "定期利用:TORO全線");
                            }else {
                                Date purchaseDate = new Date(expiryDateLong);
                                Date today = new Date();
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                                Calendar todayCalendar = Calendar.getInstance();
                                todayCalendar.setTime(today);
                                todayCalendar.set(Calendar.HOUR_OF_DAY, 23);
                                todayCalendar.set(Calendar.MINUTE, 59);
                                todayCalendar.set(Calendar.SECOND, 59);
                                todayCalendar.set(Calendar.MILLISECOND, 999);
                                Date todayEnd = todayCalendar.getTime();

                                if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                                        sdf.format(purchaseDate).equals(sdf.format(today)) && !today.after(new Date(expiryDateLong))) {
                                    isFree = true;
                                    player.sendMessage(ChatColor.GREEN + "定期利用:1日乗車券");
                                }
                            }
                        } else if (ticketType == 2 || ticketType == 3) {
                            if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                                    (routeStart.equals(data.stationName) && routeEnd.equals(frontLine2) ||
                                            routeStart.equals(frontLine2) && routeEnd.equals(data.stationName))) {
                                isFree = true;
                                player.sendMessage(ChatColor.GREEN + "定期利用:通勤･通学定期");
                            }
                        }
                    }

                    if (isFree) {
                        openFenceGate(frontLine4,event);
                        player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2);
                        player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                        player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                        data.exitStation();
                    } else {
                        if (data.checkAutoCharge()) {
                            player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
                        }
                        if (data.balance < fare) {
                            int shortSF = fare - data.balance;
                            player.sendMessage(ChatColor.RED + String.valueOf(shortSF) + "トロポ不足しています。チャージしてください。");
                        } else {
                            data.balance -= fare;
                            openFenceGate(frontLine4,event);
                            data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes , frontLine2 + exitCompanyCodes, fare * -1, data.balance, System.currentTimeMillis() / 1000L));
                            save();
                            if (data.stationName != null && data.stationName.equals(frontLine2)) {
                                player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2 + "(入場サービス) 引去: " + fare + "トロポ");
                            }else{
                                player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2 + " 引去: " + fare + "トロポ");
                            }
                            player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                            if (isExpired && ticketType != null) {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日");
                                player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
                            }
                            player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                            data.exitStation();
                        }
                    }
                }else{
                    if (!data.isInStation) {
                        data.enterStation(frontLine2, frontLine4);
                        player.sendMessage(ChatColor.GREEN + "入場: " + frontLine2);
                        player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                        data.setRideStartLocation(player.getLocation());
                        player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                    }else{
                        int fare = data.calculateFare();

                        if (data.stationName != null && data.stationName.equals(frontLine2)) {
                            fare = 100;
                        }

                        boolean isFree = false;
                        boolean isExpired = false;

                        if (expiryDateLong != null) {
                            Date expiryDate = new Date(expiryDateLong);
                            Date now = new Date();
                            isExpired = expiryDate.before(now);
                            if (isExpired) {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
                                player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
                            }
                        }

                        if (ticketType != null && companyCode != null && !isExpired) {
                            if (ticketType == 1) {
                                if (companyCode == 99) {
                                    isFree = true;
                                    player.sendMessage(ChatColor.GREEN + "定期利用:TORO全線");
                                } else if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr)) {
                                    isFree = true;
                                    player.sendMessage(ChatColor.GREEN + "定期利用:全線定期");
                                }
                            } else if (ticketType == 4) {
                                if (companyCode == 99) {
                                    isFree = true;
                                    player.sendMessage(ChatColor.GREEN + "定期利用:TORO全線");
                                }else {
                                    Date purchaseDate = new Date(expiryDateLong);
                                    Date today = new Date();
                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                                    Calendar todayCalendar = Calendar.getInstance();
                                    todayCalendar.setTime(today);
                                    todayCalendar.set(Calendar.HOUR_OF_DAY, 23);
                                    todayCalendar.set(Calendar.MINUTE, 59);
                                    todayCalendar.set(Calendar.SECOND, 59);
                                    todayCalendar.set(Calendar.MILLISECOND, 999);
                                    Date todayEnd = todayCalendar.getTime();

                                    if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                                            sdf.format(purchaseDate).equals(sdf.format(today)) && !today.after(new Date(expiryDateLong))) {
                                        isFree = true;
                                        player.sendMessage(ChatColor.GREEN + "定期利用:1日乗車券");
                                    }
                                }
                            } else if (ticketType == 2 || ticketType == 3) {
                                if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                                        (routeStart.equals(data.stationName) && routeEnd.equals(frontLine2) ||
                                                routeStart.equals(frontLine2) && routeEnd.equals(data.stationName))) {
                                    isFree = true;
                                    player.sendMessage(ChatColor.GREEN + "定期利用:通勤･通学定期");
                                }
                            }
                        }

                        if (isFree) {
                            player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2);
                            player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                            player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                            data.exitStation();
                        } else {
                            if (data.checkAutoCharge()) {
                                player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
                            }
                            if (data.balance < fare) {
                                int shortSF = fare - data.balance;
                                player.sendMessage(ChatColor.RED + String.valueOf(shortSF) + "トロポ不足しています。チャージしてください。");
                            } else {
                                data.balance -= fare;
                                data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes , frontLine2 + exitCompanyCodes, fare * -1, data.balance, System.currentTimeMillis() / 1000L));
                                save();
                                if (data.stationName != null && data.stationName.equals(frontLine2)) {
                                    player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2 + "(入場サービス) 引去: " + fare + "トロポ");
                                }else{
                                    player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2 + " 引去: " + fare + "トロポ");
                                }
                                player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                                if (isExpired && ticketType != null) {
                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日");
                                    player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
                                }
                                player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                                data.exitStation();
                            }
                        }
                    }
                }
            }

            if ("[チャージ]".equals(frontLine1)) {
                try {
                    int chargeAmount = Integer.parseInt(frontLine2);
                    if (chargeAmount < 0) {
                        player.sendMessage(ChatColor.RED + "チャージ額が不正です");
                        return;
                    }
                    StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                    if (data.balance + chargeAmount > 20000) {
                        player.sendMessage(ChatColor.RED + "最大チャージ額は20000トロポまでです");
                        return;
                    }
                    data.balance += chargeAmount;
                    player.sendMessage(ChatColor.GREEN + "チャージ額: " + chargeAmount + "トロポ");
                    player.sendMessage(ChatColor.GREEN + "現在の残高: " + data.balance + "トロポ");
                    data.paymentHistory.add(PaymentHistory.build("Special::charge", "", chargeAmount, data.balance, System.currentTimeMillis() / 1000L));
                    save();
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "チャージ額が不正です");
                }
                return;
            }

            // [残高確認] 看板
            if ("[残高確認]".equals(frontLine1)) {
                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                player.sendMessage(ChatColor.GREEN + "現在の残高: " + data.balance + "トロポ");
                return;
            }

            // [強制出場] 看板
            if ("[強制出場]".equals(frontLine1)) {
                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
                if (data.isInStation) {
                    player.sendMessage(ChatColor.GREEN + "強制出場しました。");
                    data.exitStation();
                } else {
                    player.sendMessage(ChatColor.RED + "入場記録がありません。");
                }
                return;
            }

            // [残額調整] 看板
            if ("[残額調整]".equals(frontLine1)) {
                try {
                    int newBalance = Integer.parseInt(frontLine2);
                    StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());

                    if (newBalance < 0) {
                        player.sendMessage(ChatColor.RED + "値が不正です。");
                        return;
                    }
                    data.balance = newBalance;
                    player.sendMessage(ChatColor.GREEN + "新しい残高: " + data.balance + "トロポ");
                    data.paymentHistory.add(PaymentHistory.build("Special::balanceAdjustment", "", newBalance, data.balance, System.currentTimeMillis() / 1000L));
                    save();
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "無効な残高値です");
                }
                return;
            }

            // [定期券情報削除] 看板
            if ("[定期券情報削除]".equals(frontLine1)) {
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
                } else {
                    player.sendMessage(ChatColor.YELLOW + "定期券情報が登録されていません。");
                }
                return;
            }

            // [定期券情報照会] 看板
            if ("[定期券情報照会]".equals(frontLine1)) {
                ItemMeta meta = item.getItemMeta();
                if (meta.getPersistentDataContainer().has(ticketTypeKey, PersistentDataType.INTEGER)) {
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
                    if (expiryDateLong != null) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
                        player.sendMessage(ChatColor.GREEN + "有効期限: " + sdf.format(new Date(expiryDateLong)));
                    } else {
                        player.sendMessage(ChatColor.GREEN + "有効期限: 不明");
                    }
                    player.sendMessage(ChatColor.GREEN + "チェックデジット: " + (checkDigit != null ? checkDigit : "不明"));
                    if (ticketType != null && (ticketType == 2 || ticketType == 3)) {
                        player.sendMessage(ChatColor.GREEN + "定期区間: " + (routeStart != null ? routeStart : "不明") + " - " + (routeEnd != null ? routeEnd : "不明"));
                    }
                    player.sendMessage(ChatColor.GREEN + "=======================");
                } else {
                    player.sendMessage(ChatColor.YELLOW + "このICカードには定期券情報が登録されていません。");
                }
                return;
            }

            // [乗換] 看板の処理（運賃精算と再入場）
            if ("[乗換]".equals(frontLine1)) {
                StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());

                if (!data.isInStation) {
                    player.sendMessage(ChatColor.RED + "入場記録がないため、乗換改札を利用できません。先に入場してください。");
                    return;
                }

                ItemMeta meta = item.getItemMeta();
                Integer ticketType = meta.getPersistentDataContainer().get(ticketTypeKey, PersistentDataType.INTEGER);
                Integer companyCode = meta.getPersistentDataContainer().get(companyCodeKey, PersistentDataType.INTEGER);
                Long expiryDateLong = meta.getPersistentDataContainer().get(expiryDateKey, PersistentDataType.LONG);
                String routeStart = meta.getPersistentDataContainer().get(routeStartKey, PersistentDataType.STRING);
                String routeEnd = meta.getPersistentDataContainer().get(routeEndKey, PersistentDataType.STRING);

                List<String> exitCompanyCodes = data.validateCompanyCodes(frontLine4);
                String companyCodeStr = companyCode != null ? String.format("%02d", companyCode) : null;

                int fare = data.calculateFare();
                String previousStation = data.stationName;

                boolean isFree = false;
                boolean isExpired = false;

                if (expiryDateLong != null) {
                    Date expiryDate = new Date(expiryDateLong);
                    Date now = new Date();
                    isExpired = expiryDate.before(now);
                    if (isExpired) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
                        player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
                    }
                }

                if (ticketType != null && companyCode != null && !isExpired) {
                    if (ticketType == 1) {
                        if (companyCode == 99) {
                            // 事業者コード99は全線定期券として常に無料
                            isFree = true;
                            player.sendMessage(ChatColor.GREEN + "定期利用:TORO全線");
                        } else if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr)) {
                            isFree = true;
                            player.sendMessage(ChatColor.GREEN + "定期利用:全線定期");
                        }
                    } else if (ticketType == 4) {
                        Date purchaseDate = new Date(expiryDateLong);
                        Date today = new Date();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                        Calendar todayCalendar = Calendar.getInstance();
                        todayCalendar.setTime(today);
                        todayCalendar.set(Calendar.HOUR_OF_DAY, 23);
                        todayCalendar.set(Calendar.MINUTE, 59);
                        todayCalendar.set(Calendar.SECOND, 59);
                        todayCalendar.set(Calendar.MILLISECOND, 999);
                        Date todayEnd = todayCalendar.getTime();

                        if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                                sdf.format(purchaseDate).equals(sdf.format(today)) && !today.after(new Date(expiryDateLong))) {
                            isFree = true;
                            player.sendMessage(ChatColor.GREEN + "定期利用:1日乗車券");
                        }
                    } else if (ticketType == 2 || ticketType == 3) {
                        if (data.entryCompanyCodes.contains(companyCodeStr) && exitCompanyCodes.contains(companyCodeStr) &&
                                (routeStart.equals(data.stationName) && routeEnd.equals(frontLine2) ||
                                        routeStart.equals(frontLine2) && routeEnd.equals(data.stationName))) {
                            isFree = true;
                            player.sendMessage(ChatColor.GREEN + "定期利用:通勤･通学定期");
                        }
                    }
                }

                if (isFree) {
                    player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2);
                    openFenceGate(frontLine4,event);
                } else {
                    if (data.checkAutoCharge()) {
                        player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
                    }
                    if (data.balance < fare) {
                        int shortSF = fare - data.balance;
                        player.sendMessage(ChatColor.RED + String.valueOf(shortSF) + "トロポ不足しています。チャージしてください。");
                        return;
                    } else {
                        data.balance -= fare;
                        data.paymentHistory.add(PaymentHistory.build(data.stationName + data.entryCompanyCodes , frontLine2 + exitCompanyCodes , fare * -1, data.balance, System.currentTimeMillis() / 1000L));
                        save();
                        openFenceGate(frontLine4,event);
                        player.sendMessage(ChatColor.GREEN + "出場: " + frontLine2 + " 引去: " + fare + "トロポ");
                        if (isExpired && ticketType != null) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日");
                            player.sendMessage(ChatColor.RED + "定期券の有効期限 (" + sdf.format(new Date(expiryDateLong)) + ") が切れています。更新または消去処理をしてください。");
                        }
                    }
                }

                // 出場処理を完了（状態をクリア）
                data.exitStation();

                // 2. 入場処理（3行目の駅から再入場）
                data.enterStation(frontLine3, frontLine4);
                player.sendMessage(ChatColor.GREEN + "入場: " + frontLine3);
                player.sendMessage(ChatColor.GREEN + "残高: " + data.balance + "トロポ");
                data.setRideStartLocation(player.getLocation());

                // サウンド再生
                player.playSound(player.getLocation(), "custom.kaisatsu", 1.0F, 1.0F);
                return;
            }

            // [物販] 看板の裏面 ([IC]) での支払い処理
            if ("[IC]".equals(backLine2) && "ここにタッチ".equals(backLine3)) {
                if ("[物販]".equals(frontLine1)) {
                    String storeName = frontLine2;
                    String amountStr = frontLine3;

                    try {
                        int amount = Integer.parseInt(amountStr);
                        StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());

                        if (data.checkAutoCharge()) {
                            player.sendMessage(ChatColor.GREEN + "オートチャージが実行されました。新しい残高: " + data.balance + "トロポ");
                        }

                        if (data.balance < amount) {
                            int shortfall = amount - data.balance;
                            player.sendMessage(ChatColor.RED + String.valueOf(shortfall) + "トロポ不足しています。チャージしてください。");
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
                } else {
                    player.sendMessage(ChatColor.RED + "この看板は物販用に正しく設定されていません。");
                }
            }
        }
        if ("[TOROpass販売]".equals(frontLine1)) {
            if (player.isSneaking()) {
                return;
            }
            event.setCancelled(true);
            try {
                int customModelData = Integer.parseInt(frontLine2);
                String[] costParts = frontLine3.split(" ");
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
                    player.sendMessage(ChatColor.RED.toString() + costAmount + "個の" + costMaterial.name().toLowerCase() + "が不足しています。");
                    return;
                }
                ItemStack costItem = new ItemStack(costMaterial, costAmount);
                player.getInventory().removeItem(costItem);
                ItemStack pass = new ItemStack(Material.PAPER, 1);
                ItemMeta meta = pass.getItemMeta();
                meta.setCustomModelData(customModelData);
                String itemName;
                switch (customModelData) {
                    case 1:
                        itemName = "TORO CARD";
                        break;
                    case 3:
                        itemName = "Minu pass";
                        break;
                    case 4:
                        itemName = "KOUDAN pass";
                        break;
                    case 5:
                        itemName = "Rupica";
                        break;
                    case 6:
                        itemName = "ShakechanRupica";
                        break;
                    case 7:
                        itemName = "TOHOCA";
                        break;
                    case 2:
                    default:
                        itemName = "TOROpass";
                        break;
                }
                meta.setDisplayName(ChatColor.RESET + itemName);
                pass.setItemMeta(meta);
                player.getInventory().addItem(pass);
                player.sendMessage(ChatColor.GREEN + itemName + "を購入しました！");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "看板の2行目または3行目の形式が不正です。");
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "購入処理中にエラーが発生しました。");
            }
            return;
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line1 = ChatColor.stripColor(event.getLine(0));
        Player player = event.getPlayer();

        if ("[入場]".equals(line1) || "[出場]".equals(line1) || "[チャージ]".equals(line1) || "[定期券情報削除]".equals(line1) || "[入出場]".equals(line1)) {
            String line2 = ChatColor.stripColor(event.getLine(1));
            if ("[定期券情報削除]".equals(line1)) {
                player.sendMessage(ChatColor.GREEN + "定期券情報削除看板が正常に設定されました。");
            } else if (line2 == null || line2.isEmpty()) {
                player.sendMessage(ChatColor.RED + "必要な情報を2行目に記載してください。");
                event.setCancelled(true);
            } else {
                player.sendMessage(ChatColor.GREEN + "看板が正常に設定されました。");
            }
        } else if ("[物販]".equals(line1)) {
            String storeName = ChatColor.stripColor(event.getLine(1));
            String amountStr = ChatColor.stripColor(event.getLine(2));

            if (storeName == null || storeName.isEmpty() || amountStr == null || amountStr.isEmpty()) {
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
                player.sendMessage(ChatColor.GREEN + "物販看板が正常に設定されました。");

                org.bukkit.block.Sign signState = (org.bukkit.block.Sign) event.getBlock().getState();
                SignSide backSide = signState.getSide(org.bukkit.block.sign.Side.BACK);
                backSide.setLine(1, ChatColor.GREEN + "[IC]");
                backSide.setLine(2, "ここにタッチ");
                signState.update(true);
                player.sendMessage(ChatColor.GREEN + "裏面に支払い情報が書き込まれました。");

                getLogger().info("看板設置: 表面=" + event.getLine(0) + ", 裏面2行目=" + backSide.getLine(1) + ", 裏面3行目=" + backSide.getLine(2));
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "3行目の金額が正しい数値ではありません。");
                event.setCancelled(true);
            }
        } else if ("[定期券情報照会]".equals(line1)) {
            event.setLine(0, "[定期券情報照会]");
            player.sendMessage(ChatColor.GREEN + "定期券情報照会看板が正常に設定されました。");
        } else if ("[乗換]".equals(line1)) {
            String departureStation = ChatColor.stripColor(event.getLine(1));
            String transferStation = ChatColor.stripColor(event.getLine(2));
            String companyCodesLine = ChatColor.stripColor(event.getLine(3));

            if (departureStation == null || departureStation.isEmpty() || transferStation == null || transferStation.isEmpty()) {
                player.sendMessage(ChatColor.RED + "2行目に出発駅、3行目に乗り換え先駅を記載してください。");
                event.setCancelled(true);
                return;
            }

            event.setLine(0, "[乗換]");
            event.setLine(1, departureStation);
            event.setLine(2, transferStation);
            if (companyCodesLine != null && !companyCodesLine.isEmpty()) {
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
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());
            if (data.isInStation) {
                data.setRideStartLocation(player.getLocation());
            }
        }
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Vehicle) {
            Vehicle vehicle = event.getVehicle();
            if (vehicle.getPassengers().isEmpty()) return;
            Player player = (Player) vehicle.getPassengers().get(0);
            StationData data = playerData.computeIfAbsent(player.getUniqueId(), k -> new StationData());

            if (data.isInStation) {
                data.addTravelDistance(event.getTo());
            }
        }
    }

    private boolean isValidICCard(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData();
    }


    public class StationData {
        public boolean isInStation = false;
        public int balance = 0;
        public String stationName = "";
        private Location rideStartLocation;
        private double travelDistance = 0;
        public ArrayList<PaymentHistory> paymentHistory = new ArrayList<>();
        public Integer autoChargeThreshold = null;
        public Integer autoChargeAmount = null;
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

        private List<String> validateCompanyCodes(String line) {
            List<String> validCodes = new ArrayList<>();
            if (line == null || line.trim().isEmpty()) return validCodes;
            String[] codes = line.split(" ");
            for (String code : codes) {
                try {
                    int codeNum = Integer.parseInt(code);
                    if (codeNum >= 0 && codeNum <= 99) {
                        validCodes.add(String.format("%02d", codeNum));
                    }
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
            return (int) (travelDistance * .2);
        }

        public boolean checkAutoCharge() {
            if (autoChargeThreshold != null && autoChargeAmount != null && balance < autoChargeThreshold) {
                int chargeAmount = autoChargeAmount;
                balance += chargeAmount;
                paymentHistory.add(PaymentHistory.build("Special::autocharge", "", chargeAmount, balance, System.currentTimeMillis() / 1000L));
                return true;
            }
            return false;
        }
    }

    public void openFenceGate(String frontLine4, PlayerInteractEvent event) {
        if (frontLine4 == null) return;

        Matcher matcher = Pattern.compile("\\(\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*\\d+\\s*\\)").matcher(frontLine4);
        if (matcher.find()) {
            String match = matcher.group(); // 例: "(1,2,3,4)"
            String[] parts = match.replaceAll("[()\\s]", "").split(",");
            int offsetX = Integer.parseInt(parts[0]);
            int offsetY = Integer.parseInt(parts[1]);
            int offsetZ = Integer.parseInt(parts[2]);
            int seconds = Integer.parseInt(parts[3]);

            Location signLoc = event.getClickedBlock().getState().getLocation();
            Location targetLoc = signLoc.clone().add(offsetX, offsetY, offsetZ);
            Block targetBlock = targetLoc.getBlock();

            if (targetBlock.getType().name().endsWith("_FENCE_GATE")) {
                BlockData blockData = targetBlock.getBlockData();
                if (blockData instanceof Openable gate && !gate.isOpen()) {
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
            }
        }
    }





    public static class HTTPServer extends NanoHTTPD {
        private final TOROpassICsystem mainclass;

        public HTTPServer(int port, TOROpassICsystem mainclass) throws IOException {
            super(port);
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            this.mainclass = mainclass;
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            ObjectMapper mapper = new ObjectMapper();

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

            } else if (uri.startsWith("/api/history/")) {
                String playerName = uri.substring("/api/history/".length());
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (Player Not Found)");
                }

                StationData data = mainclass.playerData.get(player.getUniqueId());
                List<PaymentHistory> history = new ArrayList<>(data.paymentHistory);
                Collections.reverse(history);

                if (history.size() > 100) {
                    history = history.subList(0, 100);
                }

                try {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", mapper.writeValueAsString(history));
                } catch (JsonProcessingException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 Internal Server Error (JSON Error)");
                }

            } else if (uri.startsWith("/api/fullhistory/")) {
                String playerName = uri.substring("/api/fullhistory/".length());
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                if (player == null || !mainclass.playerData.containsKey(player.getUniqueId())) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (Player Not Found)");
                }

                StationData data = mainclass.playerData.get(player.getUniqueId());
                List<PaymentHistory> fullHistory = new ArrayList<>(data.paymentHistory);
                Collections.reverse(fullHistory);

                try {
                    return newFixedLengthResponse(Response.Status.OK, "application/json", mapper.writeValueAsString(fullHistory));
                } catch (JsonProcessingException e) {
                    mainclass.getLogger().warning(e.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 Internal Server Error (JSON Error)");
                }

            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found (URI Error)");
            }
        }
    }

}