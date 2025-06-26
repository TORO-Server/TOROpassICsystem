package prj.salmon.toropassicsystem.types;

public class PaymentHistory {
    public String from;
    public String to;
    public int amount;
    public int balance;
    public long timestamp;
    public String description;

    public static PaymentHistory build(String from, String to, int amount, int balance, long timestamp, String description) {
        PaymentHistory history = new PaymentHistory();
        history.from = from;
        history.to = to;
        history.amount = amount;
        history.balance = balance;
        history.timestamp = timestamp;
        history.description = description;
        return history;
    }
}