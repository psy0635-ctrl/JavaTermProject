package kr.ac.aikopo.javatermproject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class ProductRepository {
    private final Connection connection;

    ProductRepository(Connection connection) {
        this.connection = connection;
    }

    List<Manufacturer> findManufacturers() throws SQLException {
        String sql = "SELECT manufacturer_id, name FROM manufacturer ORDER BY name, manufacturer_id";
        List<Manufacturer> manufacturers = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                manufacturers.add(new Manufacturer(rows.getLong("manufacturer_id"), rows.getString("name")));
            }
        }
        return manufacturers;
    }

    List<Product> findProducts() throws SQLException {
        String sql = """
                SELECT p.product_id, p.name, p.price, m.manufacturer_id, m.name AS manufacturer_name
                FROM product p
                JOIN manufacturer m ON m.manufacturer_id = p.manufacturer_id
                ORDER BY p.product_id
                """;
        List<Product> products = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                Manufacturer manufacturer = new Manufacturer(
                        rows.getLong("manufacturer_id"), rows.getString("manufacturer_name"));
                products.add(new Product(rows.getLong("product_id"), rows.getString("name"),
                        rows.getLong("price"), manufacturer));
            }
        }
        return products;
    }

    long insert(ProductInput input) throws SQLException {
        String sql = "INSERT INTO product (name, price, manufacturer_id) VALUES (?, ?, ?)";
        try (var statement = connection.prepareStatement(sql, new String[]{"PRODUCT_ID"})) {
            bindInput(statement, input);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Oracle에서 생성한 상품번호를 받지 못했습니다.");
            }
        }
    }

    boolean update(long id, ProductInput input) throws SQLException {
        String sql = "UPDATE product SET name = ?, price = ?, manufacturer_id = ? WHERE product_id = ?";
        try (var statement = connection.prepareStatement(sql)) {
            bindInput(statement, input);
            statement.setLong(4, id);
            return statement.executeUpdate() == 1;
        }
    }

    boolean delete(long id) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM product WHERE product_id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() == 1;
        }
    }

    private void bindInput(PreparedStatement statement, ProductInput input) throws SQLException {
        statement.setString(1, input.name());
        statement.setLong(2, input.price());
        statement.setLong(3, input.manufacturerId());
    }

    record Manufacturer(long id, String name) {
        @Override
        public String toString() {
            return name + " (" + id + ")";
        }
    }

    record Product(long id, String name, long price, Manufacturer manufacturer) {
    }

    record ProductInput(String name, long price, long manufacturerId) {
    }
}
