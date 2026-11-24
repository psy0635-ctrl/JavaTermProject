package kr.ac.aikopo.javatermproject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class OrderRepository {
    private final Connection connection;

    OrderRepository(Connection connection) {
        this.connection = connection;
    }

    List<Member> findMembers() throws SQLException {
        List<Member> members = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT member_id, name FROM member ORDER BY name, member_id");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                members.add(new Member(rows.getLong("member_id"), rows.getString("name")));
            }
        }
        return members;
    }

    List<OrderSummary> findOrders() throws SQLException {
        String sql = """
                SELECT o.order_id, m.name AS member_name, o.ordered_at,
                       COALESCE((SELECT SUM(i.quantity * i.unit_price)
                                 FROM order_item i WHERE i.order_id = o.order_id), 0) AS total
                FROM sales_order o
                JOIN member m ON m.member_id = o.member_id
                ORDER BY o.ordered_at DESC, o.order_id DESC
                """;
        List<OrderSummary> orders = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                orders.add(new OrderSummary(rows.getLong("order_id"), rows.getString("member_name"),
                        rows.getObject("ordered_at", OffsetDateTime.class), rows.getBigDecimal("total")));
            }
        }
        return orders;
    }

    List<OrderDetail> findDetails(long orderId) throws SQLException {
        String sql = """
                SELECT p.name AS product_name, o.color, o.option_size, i.quantity, i.unit_price
                FROM order_item i
                JOIN product_option o ON o.option_id = i.option_id
                JOIN product p ON p.product_id = o.product_id
                WHERE i.order_id = ?
                ORDER BY i.order_item_id
                """;
        List<OrderDetail> details = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, orderId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    details.add(new OrderDetail(rows.getString("product_name"), rows.getString("color"),
                            rows.getString("option_size"), rows.getLong("quantity"), rows.getLong("unit_price")));
                }
            }
        }
        return details;
    }

    long placeOrder(long memberId, Map<Long, Long> quantities) throws SQLException {
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("주문할 옵션을 담으세요.");
        }

        // Acquire option locks in ID order for orders that share options.
        var lines = new TreeMap<>(quantities);
        for (long quantity : lines.values()) {
            if (quantity < 1 || quantity > InventoryRepository.MAX_QUANTITY) {
                throw new IllegalArgumentException("주문 수량이 허용 범위를 벗어났습니다.");
            }
        }

        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("주문 등록에는 새 JDBC 연결이 필요합니다.");
        }
        connection.setAutoCommit(false);
        try {
            long orderId = insertOrder(memberId);
            for (var line : lines.entrySet()) {
                long optionId = line.getKey();
                long quantity = line.getValue();
                reduceStock(optionId, quantity);
                long orderItemId = insertItem(orderId, optionId, quantity, findUnitPrice(optionId));
                insertShipment(optionId, orderItemId, quantity);
            }
            connection.commit();
            return orderId;
        } catch (SQLException | RuntimeException error) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        }
    }

    private long insertOrder(long memberId) throws SQLException {
        String sql = "INSERT INTO sales_order (member_id) VALUES (?)";
        try (var statement = connection.prepareStatement(sql, new String[]{"ORDER_ID"})) {
            statement.setLong(1, memberId);
            return insertAndGetId(statement);
        }
    }

    private void reduceStock(long optionId, long quantity) throws SQLException {
        String sql = """
                UPDATE product_option
                SET stock_quantity = stock_quantity - ?
                WHERE option_id = ? AND stock_quantity >= ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, quantity);
            statement.setLong(2, optionId);
            statement.setLong(3, quantity);
            if (statement.executeUpdate() != 1) {
                throw new StockUnavailableException(optionId, quantity);
            }
        }
    }

    private long findUnitPrice(long optionId) throws SQLException {
        String sql = """
                SELECT p.price FROM product p
                JOIN product_option o ON o.product_id = p.product_id
                WHERE o.option_id = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, optionId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getLong(1);
                }
                throw new SQLException("옵션의 상품 가격을 확인하지 못했습니다.");
            }
        }
    }

    private long insertItem(long orderId, long optionId, long quantity, long unitPrice) throws SQLException {
        String sql = """
                INSERT INTO order_item (order_id, option_id, quantity, unit_price) VALUES (?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql, new String[]{"ORDER_ITEM_ID"})) {
            statement.setLong(1, orderId);
            statement.setLong(2, optionId);
            statement.setLong(3, quantity);
            statement.setLong(4, unitPrice);
            return insertAndGetId(statement);
        }
    }

    private void insertShipment(long optionId, long orderItemId, long quantity) throws SQLException {
        String sql = """
                INSERT INTO stock_history (option_id, order_item_id, change_type, change_quantity)
                VALUES (?, ?, 'OUT', ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, optionId);
            statement.setLong(2, orderItemId);
            statement.setLong(3, quantity);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("출고 이력을 한 건 저장하지 못했습니다.");
            }
        }
    }

    private long insertAndGetId(PreparedStatement statement) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new SQLException("주문 행을 한 건 저장하지 못했습니다.");
        }
        try (var keys = statement.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
            throw new SQLException("Oracle에서 자동 생성된 주문 키를 받지 못했습니다.");
        }
    }

    record Member(long id, String name) {
        @Override
        public String toString() {
            return name + " (" + id + ")";
        }
    }

    record OrderSummary(long id, String memberName, OffsetDateTime orderedAt, BigDecimal total) {
    }

    record OrderDetail(String productName, String color, String size, long quantity, long unitPrice) {
    }

    static final class StockUnavailableException extends SQLException {
        StockUnavailableException(long optionId, long quantity) {
            super("옵션 " + optionId + "의 재고가 부족하거나 옵션이 없습니다. 요청 수량은 " + quantity + "개입니다.");
        }
    }
}
