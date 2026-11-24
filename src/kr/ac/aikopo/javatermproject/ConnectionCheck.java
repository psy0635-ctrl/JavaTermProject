package kr.ac.aikopo.javatermproject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ConnectionCheck {
    private static final String SCHEMA_QUERY = """
            SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM dual
            """;

    private static final String TABLE_QUERY = """
            SELECT table_name
            FROM user_tables
            WHERE table_name IN ('MEMBER', 'MANUFACTURER', 'PRODUCT', 'PRODUCT_OPTION',
                                 'SALES_ORDER', 'ORDER_ITEM', 'STOCK_HISTORY')
            ORDER BY table_name
            """;

    private static final int EXPECTED_TABLE_COUNT = 7;

    private ConnectionCheck() {
    }

    public static void main(String[] args) {
        System.exit(checkConnection());
    }

    private static int checkConnection() {
        try (Connection connection = Database.open()) {
            System.out.println("Oracle JDBC 연결 성공");
            printSchema(connection);
            int tableCount = printTables(connection);
            System.out.printf("확인한 테이블: %d/%d%n", tableCount, EXPECTED_TABLE_COUNT);
            if (tableCount != EXPECTED_TABLE_COUNT) {
                System.err.println("접속 사용자와 서비스 이름, 테이블을 생성한 스키마를 확인하세요.");
                return 2;
            }
            return 0;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (SQLException e) {
            System.err.printf("Oracle JDBC 확인 실패 (SQLState=%s, 오류 코드=%d)%n",
                    e.getSQLState(), e.getErrorCode());
            System.err.println("Maven 드라이버와 Oracle 접속 설정을 확인하세요.");
            return 1;
        }
    }

    private static void printSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SCHEMA_QUERY);
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                System.out.println("현재 스키마: " + rows.getString(1));
            }
        }
    }

    private static int printTables(Connection connection) throws SQLException {
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(TABLE_QUERY);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                System.out.println("  " + rows.getString(1));
                count++;
            }
        }
        return count;
    }
}
