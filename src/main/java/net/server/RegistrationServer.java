package net.server;

import client.DefaultDates;
import config.YamlConfig;
import tools.BCrypt;
import tools.DatabaseConnection;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Map;

final class RegistrationServer {

    private final HttpServer httpServer;

    private RegistrationServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    static RegistrationServer start(int port) throws IOException {
        // Bind to all interfaces to support host access when running inside Docker
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        RegistrationServer regServer = new RegistrationServer(server);
        server.createContext("/register", new RegisterHandler());
        server.setExecutor(null);
        server.start();
        return regServer;
    }

    void stop() {
        httpServer.stop(0);
    }

    private static final class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "method_not_allowed");
                return;
            }

            byte[] body = exchange.getRequestBody().readAllBytes();
            String payload = new String(body, StandardCharsets.UTF_8).trim();
            Map<String, String> params = Json.parseObject(payload);
            if (params == null) {
                send(exchange, 400, "invalid_json");
                return;
            }

            String username = trimToEmpty(params.get("username"));
            String password = trimToEmpty(params.get("password"));
            if (username.isEmpty() || password.isEmpty()) {
                send(exchange, 400, "missing_username_or_password");
                return;
            }
            if (username.length() > 13) {
                send(exchange, 400, "username_too_long");
                return;
            }

            boolean bcrypt = YamlConfig.config.server.BCRYPT_MIGRATION;
            String passwordHash = bcrypt ? BCrypt.hashpw(password, BCrypt.gensalt(12)) : Hash.sha512(password);

            try (Connection con = DatabaseConnection.getConnection()) {
                if (exists(con, username)) {
                    send(exchange, 409, "username_exists");
                    return;
                }

                int id = insert(con, username, passwordHash);
                send(exchange, 201, "{\"ok\":true,\"id\":" + id + "}");
            } catch (SQLException e) {
                send(exchange, 500, "sql_error");
            }
        }

        private static boolean exists(Connection con, String username) throws SQLException {
            try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM accounts WHERE name = ? LIMIT 1")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        private static int insert(Connection con, String username, String passwordHash) throws SQLException {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts (name, password, birthday, tempban) VALUES (?, ?, ?, ?);",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, passwordHash);
                ps.setDate(3, Date.valueOf(DefaultDates.getBirthday()));
                ps.setTimestamp(4, Timestamp.valueOf(DefaultDates.getTempban()));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return -1;
                }
            }
        }

        private static String trimToEmpty(String s) {
            return s == null ? "" : s.trim();
        }

        private static void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
    }

    // Minimal JSON parser for simple flat objects {"k":"v"}
    private static final class Json {
        static Map<String, String> parseObject(String s) {
            s = s.trim();
            if (s.isEmpty() || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') return null;
            s = s.substring(1, s.length() - 1).trim();
            java.util.HashMap<String, String> map = new java.util.HashMap<>();
            if (s.isEmpty()) return map;
            String[] parts = s.split(",");
            for (String p : parts) {
                String[] kv = p.split(":", 2);
                if (kv.length != 2) return null;
                String k = unquote(kv[0].trim());
                String v = unquote(kv[1].trim());
                map.put(k, v);
            }
            return map;
        }

        private static String unquote(String s) {
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            return s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }

    private static final class Hash {
        static String sha512(String pwd) {
            try {
                java.security.MessageDigest digester = java.security.MessageDigest.getInstance("SHA-512");
                byte[] bytes = pwd.getBytes(StandardCharsets.UTF_8);
                digester.update(bytes, 0, bytes.length);
                byte[] out = digester.digest();
                StringBuilder sb = new StringBuilder(out.length * 2);
                for (byte b : out) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}



