package ru.phosagro.survey.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengrad.telegrambot.model.User;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class Db {
    private final String url;

    public Db(String path) {
        try { Files.createDirectories(Path.of("data")); } catch (Exception ignored) {}
        this.url = "jdbc:sqlite:" + path; // обычно "data/survey.db"
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON;"); }
        return c;
    }

    /* =================== schema & perf =================== */

    public void initSchema() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON;");

            s.execute("""
                CREATE TABLE IF NOT EXISTS users (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  tg_id INTEGER UNIQUE NOT NULL,
                  first_name TEXT,
                  last_name TEXT,
                  username TEXT,
                  is_admin INTEGER DEFAULT 0,
                  created_at TEXT
                );
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS responses (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  status TEXT NOT NULL DEFAULT 'DRAFT',
                  started_at TEXT,
                  completed_at TEXT,
                  FOREIGN KEY(user_id) REFERENCES users(id)
                );
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS answers (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  response_id INTEGER NOT NULL,
                  question_id TEXT,
                  answer_text TEXT,
                  option_ids_json TEXT,
                  created_at TEXT,
                  FOREIGN KEY(response_id) REFERENCES responses(id)
                );
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS user_progress (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id INTEGER NOT NULL,
                  response_id INTEGER NOT NULL,
                  current_q_index INTEGER NOT NULL DEFAULT 0,
                  current_msg_id INTEGER,
                  awaiting_other_question_id TEXT,
                  awaiting_other_option_id TEXT,
                  multi_selection_json TEXT,
                  updated_at TEXT,
                  FOREIGN KEY(user_id) REFERENCES users(id),
                  FOREIGN KEY(response_id) REFERENCES responses(id)
                );
            """);

            ensureColumn(c, "users", "is_admin", "INTEGER DEFAULT 0");
            ensureColumn(c, "users", "created_at", "TEXT");

            ensureColumn(c, "responses", "started_at", "TEXT");
            ensureColumn(c, "responses", "completed_at", "TEXT");

            ensureColumn(c, "answers", "question_id", "TEXT");
            ensureColumn(c, "answers", "answer_text", "TEXT");
            ensureColumn(c, "answers", "option_ids_json", "TEXT");
            ensureColumn(c, "answers", "created_at", "TEXT");

            ensureColumn(c, "user_progress", "current_q_index", "INTEGER");
            ensureColumn(c, "user_progress", "current_msg_id", "INTEGER");
            ensureColumn(c, "user_progress", "awaiting_other_question_id", "TEXT");
            ensureColumn(c, "user_progress", "awaiting_other_option_id", "TEXT");
            ensureColumn(c, "user_progress", "multi_selection_json", "TEXT");
            ensureColumn(c, "user_progress", "updated_at", "TEXT");

            s.execute("CREATE INDEX IF NOT EXISTS idx_users_tg_id          ON users(tg_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_resp_user_status     ON responses(user_id, status, completed_at);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_answers_resp         ON answers(response_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_answers_qid          ON answers(question_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_progress_user        ON user_progress(user_id);");
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void initPerformance() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA synchronous=NORMAL;");
            s.execute("PRAGMA foreign_keys=ON;");
            s.execute("PRAGMA busy_timeout=5000;");

            s.execute("CREATE INDEX IF NOT EXISTS idx_users_tg_id          ON users(tg_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_resp_user_status     ON responses(user_id, status, completed_at);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_answers_resp         ON answers(response_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_answers_qid          ON answers(question_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_progress_user        ON user_progress(user_id);");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void ensureColumn(Connection c, String table, String column, String declType) throws Exception {
        boolean exists = false;
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + table + ")"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
        }
        if (!exists) try (Statement st = c.createStatement()) { st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + declType + ";"); }
    }

    /* =================== users/admin =================== */

    public void ensureUser(User u) {
        String sql = "INSERT INTO users(tg_id, first_name, last_name, username, created_at) " +
                "VALUES(?,?,?,?,?) ON CONFLICT(tg_id) DO UPDATE SET " +
                "first_name=excluded.first_name, last_name=excluded.last_name, username=excluded.username";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, u.id());
            ps.setString(2, u.firstName());
            ps.setString(3, u.lastName());
            ps.setString(4, u.username());
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void ensureAdmin(long tgId) {
        String sql = "INSERT INTO users(tg_id, created_at, is_admin) VALUES(?, ?, 1) " +
                "ON CONFLICT(tg_id) DO UPDATE SET is_admin=1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public boolean isAdmin(long tgId) {
        String sql = "SELECT is_admin FROM users WHERE tg_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) == 1; }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public String addAdmin(long actorTgId, long targetTgId) {
        if (!isAdmin(actorTgId)) return "Доступ запрещён (только админ может добавлять админов).";
        String sql = "INSERT INTO users(tg_id, created_at, is_admin) VALUES(?,?,1) " +
                "ON CONFLICT(tg_id) DO UPDATE SET is_admin=1";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, targetTgId);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
            return "Пользователь " + targetTgId + " назначен администратором.";
        } catch (SQLException e) { e.printStackTrace(); return "Ошибка: " + e.getMessage(); }
    }

    /* =================== survey progress =================== */

    public boolean hasCompleted(long tgId) {
        String sql = "SELECT COUNT(*) FROM responses r JOIN users u ON u.id=r.user_id WHERE u.tg_id=? AND r.status='COMPLETED'";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public long startResponse(long tgId) {
        String sqlUser = "SELECT id FROM users WHERE tg_id=?";
        String sqlResp = "INSERT INTO responses(user_id,status,started_at) VALUES(?, 'DRAFT', ?)";
        String sqlProgress = """
            INSERT INTO user_progress(user_id,response_id,current_q_index,current_msg_id,updated_at)
            VALUES(?,?,0,NULL,?)
            ON CONFLICT(user_id) DO UPDATE SET
              response_id=excluded.response_id,
              current_q_index=0,
              current_msg_id=NULL,
              awaiting_other_question_id=NULL,
              awaiting_other_option_id=NULL,
              multi_selection_json=NULL,
              updated_at=?;
        """;
        try (Connection c = connect()) {
            c.setAutoCommit(false);

            long userId;
            try (PreparedStatement ps = c.prepareStatement(sqlUser)) {
                ps.setLong(1, tgId);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) { c.rollback(); return -1; } userId = rs.getLong(1); }
            }

            long respId;
            try (PreparedStatement ps = c.prepareStatement(sqlResp, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, userId);
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); respId = rs.getLong(1); }
            }

            try (PreparedStatement ps = c.prepareStatement(sqlProgress)) {
                String now = Instant.now().toString();
                ps.setLong(1, userId);
                ps.setLong(2, respId);
                ps.setString(3, now);
                ps.setString(4, now);
                ps.executeUpdate();
            }

            c.commit();
            return respId;
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    /** keys: current_q_index, current_msg_id, awaiting_other_q, awaiting_other_o, multi_selection_json, response_id */
    public Map<String,Object> loadProgress(long tgId) {
        String sql = "SELECT p.current_q_index, p.current_msg_id, p.awaiting_other_question_id, p.awaiting_other_option_id, p.multi_selection_json, p.response_id " +
                "FROM user_progress p JOIN users u ON u.id=p.user_id WHERE u.tg_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String,Object> map = new HashMap<>();
                map.put("current_q_index", rs.getInt(1));
                map.put("current_msg_id", rs.getObject(2) == null ? null : rs.getInt(2));
                map.put("awaiting_other_q", rs.getString(3));
                map.put("awaiting_other_o", rs.getString(4));
                map.put("multi_selection_json", rs.getString(5));
                map.put("response_id", rs.getLong(6));
                return map;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public void saveProgress(long tgId, int currentIndex, String awaitingQ, String awaitingO, List<String> multi) {
        String sql = "UPDATE user_progress SET current_q_index=?, awaiting_other_question_id=?, awaiting_other_option_id=?, multi_selection_json=?, updated_at=? WHERE user_id=(SELECT id FROM users WHERE tg_id=?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, currentIndex);
            ps.setString(2, awaitingQ);
            ps.setString(3, awaitingO);
            String json = null;
            if (multi != null) json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(multi);
            ps.setString(4, json);
            ps.setString(5, Instant.now().toString());
            ps.setLong(6, tgId);
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void setCurrentMessageId(long tgId, Integer msgId) {
        String sql = "UPDATE user_progress SET current_msg_id=?, updated_at=? WHERE user_id=(SELECT id FROM users WHERE tg_id=?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (msgId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, msgId);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, tgId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public Integer getCurrentMessageId(long tgId) {
        String sql = "SELECT current_msg_id FROM user_progress p JOIN users u ON u.id=p.user_id WHERE u.tg_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return (Integer) rs.getObject(1); }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public List<String> getMultiSelected(long tgId) {
        String sql = "SELECT multi_selection_json FROM user_progress p JOIN users u ON u.id=p.user_id WHERE u.tg_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new ArrayList<>();
                String json = rs.getString(1);
                if (json == null) return new ArrayList<>();
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) { e.printStackTrace(); }
        return new ArrayList<>();
    }

    public long getCurrentResponseId(long tgId) {
        String sql = "SELECT p.response_id FROM user_progress p JOIN users u ON u.id=p.user_id WHERE u.tg_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public void insertAnswer(long responseId, String questionId, String answerText, List<String> multiOptions) {
        String sql = "INSERT INTO answers(response_id,question_id,answer_text,option_ids_json,created_at) VALUES(?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, responseId);
            ps.setString(2, questionId);
            ps.setString(3, answerText);
            String json = null;
            if (multiOptions != null) json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(multiOptions);
            ps.setString(4, json);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void finishAndCommit(long tgId) {
        String sql = """
            UPDATE responses SET status='COMPLETED', completed_at=? 
            WHERE id=(SELECT response_id FROM user_progress p JOIN users u ON u.id=p.user_id WHERE u.tg_id=?);
            """;
        String del = "DELETE FROM user_progress WHERE user_id=(SELECT id FROM users WHERE tg_id=?)";
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, Instant.now().toString());
                ps.setLong(2, tgId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(del)) {
                ps.setLong(1, tgId);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public boolean inDraft(long tgId) {
        String sql = "SELECT COUNT(*) FROM user_progress p JOIN users u ON u.id=p.user_id WHERE u.tg_id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1)>0; }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    /* =================== admin helpers =================== */

    public int countCompleted() {
        String sql = "SELECT COUNT(*) FROM responses WHERE status='COMPLETED';";
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) { e.printStackTrace(); return 0; }
    }

    public List<Long> listCompletedUserTgIdsPaged(int limit, int offset) {
        List<Long> out = new ArrayList<>();
        String sql = """
            SELECT DISTINCT u.tg_id
            FROM responses r
            JOIN users u ON u.id = r.user_id
            WHERE r.status = 'COMPLETED'
            ORDER BY r.completed_at DESC
            LIMIT ? OFFSET ?;
        """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getLong(1)); }
        } catch (Exception e) { e.printStackTrace(); }
        return out;
    }

    public List<Long> listCompletedUserTgIds() {
        String sql = """
            SELECT DISTINCT u.tg_id
            FROM responses r JOIN users u ON u.id=r.user_id
            WHERE r.status='COMPLETED'
            ORDER BY r.completed_at DESC
        """;
        List<Long> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getLong(1));
        } catch (SQLException e) { e.printStackTrace(); }
        return out;
    }

    public Map<String,List<String>> getUserAnswers(long tgId) {
        String sql = """
          SELECT a.question_id, COALESCE(a.answer_text, a.option_ids_json)
          FROM answers a
          JOIN responses r ON r.id=a.response_id
          JOIN users u ON u.id=r.user_id
          WHERE u.tg_id=? AND r.status='COMPLETED'
          ORDER BY a.id
        """;
        Map<String, List<String>> map = new LinkedHashMap<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String q = rs.getString(1);
                    String v = rs.getString(2);
                    map.computeIfAbsent(q, k -> new ArrayList<>()).add(v);
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return map;
    }

    public List<Map<String, Object>> getAllCompletedAnswers() {
        String sql = """
          SELECT u.tg_id, a.question_id, a.answer_text, a.option_ids_json
          FROM answers a
          JOIN responses r ON r.id=a.response_id
          JOIN users u ON u.id=r.user_id
          WHERE r.status='COMPLETED'
        """;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                row.put("tgId", rs.getLong(1));
                row.put("q", rs.getString(2));
                row.put("text", rs.getString(3));
                row.put("json", rs.getString(4));
                out.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return out;
    }
}