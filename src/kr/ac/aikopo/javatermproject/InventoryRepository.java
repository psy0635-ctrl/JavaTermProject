package kr.ac.aikopo.javatermproject;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class InventoryRepository {
    static final long MAX_QUANTITY = 9_999_999_999L;

    private final Connection connection;

    InventoryRepository(Connection connection) {
        this.connection = connection;
    }

    List<StockOption> findOptions() throws SQLException {
        String sql = """
                SELECT o.option_id, p.name AS product_name, o.color, o.option_size, o.stock_quantity
                FROM product_option o
                JOIN product p ON p.product_id = o.product_id
                ORDER BY o.option_id
                """;
        List<StockOption> options = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                options.add(new StockOption(rows.getLong("option_id"), rows.getString("product_name"),
                        rows.getString("color"), rows.getString("option_size"), rows.getLong("stock_quantity")));
            }
        }
        return options;
    }

    // The caller owns the transaction. A failed receipt leaves its earlier work intact.
    void receive(long optionId, long quantity) throws SQLException {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("입고 수량은 1~9,999,999,999 사이의 정수여야 합니다.");
        }

        var receiptStart = connection.setSavepoint();
        try {
            increaseStock(optionId, quantity);
            recordReceipt(optionId, quantity);
        } catch (SQLException | RuntimeException error) {
            try {
                connection.rollback(receiptStart);
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
    }

    private void increaseStock(long optionId, long quantity) throws SQLException {
        String sql = """
                UPDATE product_option
                SET stock_quantity = stock_quantity + ?
                WHERE option_id = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, quantity);
            statement.setLong(2, optionId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("재고를 갱신할 옵션이 없습니다.", "02000");
            }
        }
    }

    private void recordReceipt(long optionId, long quantity) throws SQLException {
        String sql = """
                INSERT INTO stock_history (option_id, order_item_id, change_type, change_quantity)
                VALUES (?, NULL, 'IN', ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, optionId);
            statement.setLong(2, quantity);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("입고 이력을 한 건 저장하지 못했습니다.");
            }
        }
    }

    record StockOption(long id, String productName, String color, String size, long quantity) {
    }
}
