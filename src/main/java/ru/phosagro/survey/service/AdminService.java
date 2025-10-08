package ru.phosagro.survey.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.phosagro.survey.db.Db;
import ru.phosagro.survey.model.Option;
import ru.phosagro.survey.model.Question;
import ru.phosagro.survey.model.QuestionType;
import ru.phosagro.survey.model.Survey;

import java.util.*;
import java.util.stream.Collectors;

public class AdminService {
    private static final int USERS_PAGE_SIZE = 15;
    private final Db db;
    private final SurveyService surveyService;
    private Survey survey;

    public AdminService(Db db, SurveyService surveyService) {
        this.db = db;
        this.surveyService = surveyService;
    }

    public String addAdmin(long actor, long target) { return db.addAdmin(actor, target); }

    public String openAdminPanel(long actor) {
        if (!db.isAdmin(actor)) return "Доступ запрещён.";
        int total = db.countCompleted();
        return "Админ-панель:\n— Завершённых анкет: " + total + "\nВыберите действие ниже.";
    }

    public String handleAdminCallback(long actor, String data) {
        if (!db.isAdmin(actor)) return "Доступ запрещён.";
        if ("admin:stats".equals(data)) return "__MULTI__";
        if ("admin:users".equals(data)) {
            // текст строим в Bot через listUsersPage(0)
            return "__USERS__";
        }
        return null;
    }

    public String showUserAnswers(long actor, long tgId) {
        if (!db.isAdmin(actor)) return "Доступ запрещён.";
        Map<String,List<String>> ans = db.getUserAnswers(tgId);
        if (ans.isEmpty()) return "Нет завершённой анкеты у пользователя " + tgId;
        ensureSurvey();

        Map<String, String> qText = survey.getQuestions().stream()
                .collect(Collectors.toMap(Question::getId, Question::getText, (a,b)->a, LinkedHashMap::new));

        StringBuilder sb = new StringBuilder("Ответы пользователя ").append(tgId).append(":\n\n");
        for (Map.Entry<String,List<String>> e : ans.entrySet()) {
            sb.append("• ").append(qText.getOrDefault(e.getKey(), e.getKey())).append("\n");
            for (String v : e.getValue()) {
                if (v != null && v.startsWith("[")) {
                    try {
                        List<String> arr = new ObjectMapper().readValue(v, new TypeReference<List<String>>() {});
                        sb.append("   - ").append(String.join(", ", arr)).append("\n");
                    } catch (Exception ex) {
                        sb.append("   - ").append(v).append("\n");
                    }
                } else {
                    sb.append("   - ").append(v == null ? "(пусто)" : v).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ----- Общая статистика (постранично) -----

    public int statsTotalPages() {
        ensureSurvey();
        return 1 + survey.getQuestions().size();
    }

    public String buildStatsPage(int pageIndex) {
        ensureSurvey();
        int completed = db.countCompleted();
        if (pageIndex == 0) return "Завершили опрос: " + completed;

        int qIdx = pageIndex - 1;
        if (qIdx < 0 || qIdx >= survey.getQuestions().size()) return "Нет такой страницы.";

        Question q = survey.getQuestions().get(qIdx);
        StringBuilder sb = new StringBuilder("• ").append(q.getText()).append("\n\n");

        List<Map<String,Object>> rows = db.getAllCompletedAnswers();

        switch (q.getType()) {
            case SINGLE -> {
                Map<String,Integer> cnt = new LinkedHashMap<>();
                Set<String> known = new LinkedHashSet<>();
                for (Option o : q.getOptions()) {
                    if (o.isOther()) continue;
                    cnt.put(o.getText(), 0);
                    known.add(o.getText());
                }
                Map<String,Integer> otherMap = new LinkedHashMap<>();

                for (var r : rows) {
                    if (!q.getId().equals((String) r.get("q"))) continue;
                    String text = (String) r.get("text");
                    if (text == null) text = "(пусто)";
                    if (text.startsWith("Другое: ")) {
                        String payload = text.substring("Другое: ".length()).trim();
                        if (payload.isEmpty()) payload = "(без текста)";
                        otherMap.merge(payload, 1, Integer::sum);
                    } else { cnt.merge(text, 1, Integer::sum); }
                }

                for (var e : cnt.entrySet()) {
                    int c = e.getValue();
                    int pct = completed == 0 ? 0 : (int)Math.round((c * 100.0) / Math.max(completed,1));
                    sb.append(e.getKey()).append(" — ").append(c).append(" голосов (").append(pct).append("%)\n");
                }
                for (var e : otherMap.entrySet()) {
                    int c = e.getValue();
                    int pct = completed == 0 ? 0 : (int)Math.round((c * 100.0) / Math.max(completed,1));
                    sb.append("Другое (").append(e.getKey()).append(") — ").append(c)
                            .append(" голосов (").append(pct).append("%)\n");
                }
            }
            case RATING_1_10 -> {
                Map<Integer,Integer> cnt = new LinkedHashMap<>();
                for (int v=1; v<=10; v++) cnt.put(v, 0);
                for (var r : rows) {
                    if (!q.getId().equals((String) r.get("q"))) continue;
                    try { int v = Integer.parseInt((String) r.get("text")); cnt.merge(v, 1, Integer::sum); }
                    catch (Exception ignored) {}
                }
                for (int v=1; v<=10; v++) {
                    int c = cnt.getOrDefault(v, 0);
                    int pct = completed == 0 ? 0 : (int)Math.round((c * 100.0) / Math.max(completed,1));
                    sb.append(v).append(" — ").append(c).append(" голосов (").append(pct).append("%)\n");
                }
            }
            case MULTI -> {
                Map<String,Integer> cnt = new LinkedHashMap<>();
                Set<String> known = new LinkedHashSet<>();
                for (Option o : q.getOptions()) {
                    if (o.isOther()) continue;
                    cnt.put(o.getText(), 0);
                    known.add(o.getText());
                }
                Map<String,Integer> otherMap = new LinkedHashMap<>();
                for (var r : rows) {
                    if (!q.getId().equals((String) r.get("q"))) continue;
                    String json = (String) r.get("json");
                    if (json == null || !json.startsWith("[")) continue;
                    try {
                        List<String> arr = new ObjectMapper().readValue(json, new TypeReference<List<String>>() {});
                        for (String s : arr) {
                            if (s.startsWith("Другое: ")) {
                                String payload = s.substring("Другое: ".length()).trim();
                                if (payload.isEmpty()) payload = "(без текста)";
                                otherMap.merge(payload, 1, Integer::sum);
                            } else { cnt.merge(s, 1, Integer::sum); }
                        }
                    } catch (Exception ignored) {}
                }
                for (var e : cnt.entrySet()) {
                    int c = e.getValue();
                    int pct = completed == 0 ? 0 : (int)Math.round((c * 100.0) / Math.max(completed,1));
                    sb.append(e.getKey()).append(" — ").append(c).append(" голосов (").append(pct).append("%)\n");
                }
                for (var e : otherMap.entrySet()) {
                    int c = e.getValue();
                    int pct = completed == 0 ? 0 : (int)Math.round((c * 100.0) / Math.max(completed,1));
                    sb.append("Другое (").append(e.getKey()).append(") — ").append(c)
                            .append(" голосов (").append(pct).append("%)\n");
                }
            }
            case TEXT -> {
                List<String> texts = new ArrayList<>();
                for (var r : rows) {
                    if (!q.getId().equals((String) r.get("q"))) continue;
                    String t = (String) r.get("text");
                    if (t == null || t.isBlank()) t = "(без текста)";
                    texts.add(t);
                }
                if (texts.isEmpty()) sb.append("— нет данных");
                else for (String t : texts) sb.append("— ").append(t).append("\n");
            }
        }
        return sb.toString();
    }

    // ----- Пагинация пользователей -----

    public List<Long> listUsersPage(int pageIndex) {
        int offset = pageIndex * USERS_PAGE_SIZE;
        return db.listCompletedUserTgIdsPaged(USERS_PAGE_SIZE, offset);
    }

    private void ensureSurvey() {
        if (this.survey == null) {
            try (var in = getClass().getResourceAsStream("/survey.json")) {
                this.survey = new ObjectMapper().readValue(in, Survey.class);
            } catch (Exception ignored) {}
        }
    }
}
