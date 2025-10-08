package ru.phosagro.survey.service;

import ru.phosagro.survey.db.Db;
import ru.phosagro.survey.model.Option;
import ru.phosagro.survey.model.Question;
import ru.phosagro.survey.model.QuestionType;
import ru.phosagro.survey.model.Survey;

import java.util.*;
import java.util.stream.Collectors;

public class SurveyService {
    private final Db db;
    private Survey survey;

    public SurveyService(Db db) { this.db = db; }
    public void setSurvey(Survey survey) { this.survey = survey; }

    public boolean userCompleted(long tgId) { return db.hasCompleted(tgId); }
    public void startSurvey(long tgId) { if (!db.inDraft(tgId)) db.startResponse(tgId); }

    public Question currentQuestion(long tgId) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return null;
        int idx = (int)p.get("current_q_index");
        if (idx < 0 || idx >= survey.getQuestions().size()) return null;
        return survey.getQuestions().get(idx);
    }

    /** Ждём свободный текст:
     * - TEXT-вопрос (через prepareAwaitingText)
     * - или SINGLE/MULTI, у которого есть вариант Другое (убран из клавиатуры, но мы разрешаем текст) */
    public boolean isAwaitingFreeText(long tgId) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return false;
        Question q = currentQuestion(tgId);
        if (q == null) return false;
        if (q.getType() == QuestionType.TEXT) return true;
        boolean hasOther = q.getOptions() != null && q.getOptions().stream().anyMatch(Option::isOther);
        return hasOther; // разрешаем текст для вопросов с «Другое», даже без нажатия кнопки
    }

    public void prepareAwaitingText(long tgId, String qId) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return;
        int idx = (int)p.get("current_q_index");
        db.saveProgress(tgId, idx, qId, null, getMultiSelectedInternal(tgId));
    }

    /** Текстовый ответ: TEXT или «Другое» (без кнопки) для SINGLE/MULTI. */
    public String acceptFreeText(long tgId, String text) {
        if (text == null || text.isBlank()) return "";
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return "";
        long respId = (long)p.get("response_id");
        int idx = (int)p.get("current_q_index");
        Question q = survey.getQuestions().get(idx);

        if (q.getType() == QuestionType.TEXT) {
            db.insertAnswer(respId, q.getId(), text, null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) { db.finishAndCommit(tgId); return "Спасибо! Анкетирование завершено."; }
            return "";
        }

        // SINGLE с «Другое»: принять как ответ (Другое: ...)
        if (q.getType() == QuestionType.SINGLE) {
            db.insertAnswer(respId, q.getId(), "Другое: " + text, null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) { db.finishAndCommit(tgId); return "Спасибо! Анкетирование завершено."; }
            return "";
        }

        // MULTI с «Другое»: добавить к выбору
        if (q.getType() == QuestionType.MULTI) {
            List<String> selected = getMultiSelectedInternal(tgId);
            if (selected == null) selected = new ArrayList<>();
            selected.add("Другое: " + text);
            int max = q.getMax();

            if (selected.size() >= max) {
                List<String> labels = mapMultiLabels(q, selected);
                db.insertAnswer(respId, q.getId(), null, labels);
                db.saveProgress(tgId, idx+1, null, null, null);
                if (idx+1 >= survey.getQuestions().size()) { db.finishAndCommit(tgId); return "Спасибо! Анкетирование завершено."; }
                return "";
            } else {
                db.saveProgress(tgId, idx, null, null, selected);
                return ""; // останемся на вопросе; Bot отредактирует сообщение с текущим набором
            }
        }

        return "";
    }

    /** Обработка кнопок: теперь «Другое» из клавиатуры нет. */
    public String handleCallback(long tgId, String data) {
        if (!data.startsWith("ans:")) return "";
        String[] parts = data.split(":");
        if (parts.length < 3) return "";

        String qId = parts[1];
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return "Сессия не найдена.";
        int idx = (int)p.get("current_q_index");
        Question q = survey.getQuestions().get(idx);
        if (!q.getId().equals(qId)) return ""; // протухший колбэк
        long respId = (long)p.get("response_id");

        if ("s".equals(parts[2])) { // SINGLE
            String optId = parts[3];
            Option o = q.getOptions().stream().filter(x->x.getId().equals(optId)).findFirst().orElse(null);
            if (o == null) return "Опция не найдена.";
            db.insertAnswer(respId, q.getId(), o.getText(), null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) { db.finishAndCommit(tgId); return "Спасибо! Анкетирование завершено."; }
            return "";
        }

        if ("r".equals(parts[2])) { // RATING
            String val = parts[3];
            db.insertAnswer(respId, q.getId(), val, null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) { db.finishAndCommit(tgId); return "Спасибо! Анкетирование завершено."; }
            return "";
        }

        if ("m".equals(parts[2])) { // MULTI toggle
            String optId = parts[3];
            List<String> selected = getMultiSelectedInternal(tgId);
            if (selected == null) selected = new ArrayList<>();
            Option opt = q.getOptions().stream().filter(x->x.getId().equals(optId)).findFirst().orElse(null);
            if (opt == null) return "Опция не найдена.";

            if (selected.contains(opt.getId())) selected.remove(opt.getId());
            else selected.add(opt.getId());

            int max = q.getMax();
            if (selected.size() > max) return "Можно выбрать не более " + max + " вариантов.";

            if (selected.size() == max) {
                List<String> labels = mapMultiLabels(q, selected);
                db.insertAnswer(respId, q.getId(), null, labels);
                db.saveProgress(tgId, idx+1, null, null, null);
                if (idx+1 >= survey.getQuestions().size()) { db.finishAndCommit(tgId); return "Спасибо! Анкетирование завершено."; }
                return "";
            }

            db.saveProgress(tgId, idx, null, null, selected);
            return "";
        }

        return "";
    }

    public boolean isCompleted(long tgId) {
        return db.hasCompleted(tgId) && !db.inDraft(tgId);
    }

    public Set<String> getMultiSelected(long tgId, String qId) {
        return new LinkedHashSet<>(db.getMultiSelected(tgId));
    }

    private List<String> getMultiSelectedInternal(long tgId) {
        return new ArrayList<>(db.getMultiSelected(tgId));
    }

    private static List<String> mapMultiLabels(Question q, List<String> selected) {
        Map<String,String> dict = q.getOptions().stream().collect(Collectors.toMap(Option::getId, Option::getText));
        List<String> labels = new ArrayList<>();
        for (String s : selected) {
            if (s.startsWith("Другое: ")) labels.add(s);
            else labels.add(dict.getOrDefault(s, s));
        }
        return labels;
    }

    /* ===== Helpers for UI text ===== */
    public static String renderSelectedList(Question q, Collection<String> selected) {
        if (selected == null || selected.isEmpty()) return "Ответы: —";
        Map<String,String> dict = q.getOptions().stream().collect(Collectors.toMap(Option::getId, Option::getText));
        List<String> labels = new ArrayList<>();
        for (String id : selected) labels.add(id.startsWith("Другое: ") ? id : dict.getOrDefault(id, id));
        return "Ответы: " + String.join(", ", labels);
    }
}