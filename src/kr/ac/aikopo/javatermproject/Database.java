package kr.ac.aikopo.javatermproject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

final class Database {
    private Database() {
    }

    static Connection open() throws SQLException {
        return DriverManager.getConnection(
                requireEnvironment("DB_URL"),
                requireEnvironment("DB_USER"),
                requireEnvironment("DB_PASSWORD"));
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("IntelliJ 실행 설정에 " + name + " 환경 변수를 입력하세요.");
        }
        return value;
    }
}
