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

    public void startSurvey(long tgId) {
        if (db.inDraft(tgId)) return;
        db.startResponse(tgId);
    }

    public Question currentQuestion(long tgId) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return null;
        int idx = (int)p.get("current_q_index");
        if (idx < 0 || idx >= survey.getQuestions().size()) return null;
        return survey.getQuestions().get(idx);
    }

    public boolean isAwaitingFreeText(long tgId) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return false;
        return p.get("awaiting_other_q") != null;
    }

    /** Для TEXT-вопросов: поставить ожидание текстового ответа на текущем индексе. */
    public void prepareAwaitingText(long tgId, String qId) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return;
        int idx = (int)p.get("current_q_index");
        db.saveProgress(tgId, idx, qId, null, getMultiSelectedInternal(tgId));
    }

    /** Принятие текстового ответа: MULTI+«Другое», SINGLE+«Другое», либо обычный TEXT-вопрос. */
    public String acceptFreeText(long tgId, String text) {
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return "Сессия не найдена.";

        String awaitingQ = (String)p.get("awaiting_other_q");
        String awaitingO = (String)p.get("awaiting_other_o");
        long respId = (long)p.get("response_id");
        int idx = (int)p.get("current_q_index");
        Question q = survey.getQuestions().get(idx);

        // --- MULTI + «Другое»: добавить как отдельный выбор и, если достигнут max, автоматически зафиксировать и перейти ---
        if (awaitingO != null && q.getType() == QuestionType.MULTI && q.getId().equals(awaitingQ)) {
            List<String> selected = getMultiSelectedInternal(tgId);
            if (selected == null) selected = new ArrayList<>();
            selected.add("Другое: " + text);
            int max = q.getMax();

            if (selected.size() >= max) {
                // зафиксировать ответ по вопросу
                List<String> labels = mapMultiLabels(q, selected);
                db.insertAnswer(respId, q.getId(), null, labels);
                // перейти к следующему вопросу
                db.saveProgress(tgId, idx+1, null, null, null);
                if (idx+1 >= survey.getQuestions().size()) {
                    db.finishAndCommit(tgId);
                    return "Спасибо! Анкетирование завершено.";
                }
                return ""; // без лишнего сообщения — Bot отправит следующий вопрос
            } else {
                // остаться на вопросе, ждать ещё варианты
                db.saveProgress(tgId, idx, null, null, selected);
                return "Добавлено. Выберите ещё " + (max - selected.size()) + " вариант(а) или снова укажите «Другое».";
            }
        }

        // --- SINGLE + «Другое»: принять текст и перейти дальше ---
        if (awaitingO != null && q.getType() == QuestionType.SINGLE && q.getId().equals(awaitingQ)) {
            db.insertAnswer(respId, q.getId(), "Другое: " + text, null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) {
                db.finishAndCommit(tgId);
                return "Спасибо! Анкетирование завершено.";
            }
            return ""; // без лишнего сообщения
        }

        // --- Обычный TEXT-вопрос ---
        if (q.getType() == QuestionType.TEXT && q.getId().equals(awaitingQ)) {
            db.insertAnswer(respId, q.getId(), text, null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) {
                db.finishAndCommit(tgId);
                return "Спасибо! Анкетирование завершено.";
            }
            return ""; // следующий вопрос отправит Bot
        }

        return "";
    }

    /** Обработка колбэков по вопросам. */
    public String handleCallback(long tgId, String data) {
        if (!data.startsWith("ans:")) return "";

        String[] parts = data.split(":");
        if (parts.length < 3) return "";

        String qId = parts[1];
        Map<String,Object> p = db.loadProgress(tgId);
        if (p == null) return "Сессия не найдена.";
        int idx = (int)p.get("current_q_index");
        Question q = survey.getQuestions().get(idx);
        if (!q.getId().equals(qId)) {
            return ""; // игнорируем протухший колбэк
        }
        long respId = (long)p.get("response_id");

        // SINGLE
        if ("s".equals(parts[2])) {
            String optId = parts[3];
            Option o = q.getOptions().stream().filter(x->x.getId().equals(optId)).findFirst().orElse(null);
            if (o == null) return "Опция не найдена.";

            if (o.isOther()) {
                // ждём свободный текст
                db.saveProgress(tgId, idx, q.getId(), optId, null);
                return "Напишите свой вариант:";
            } else {
                db.insertAnswer(respId, q.getId(), o.getText(), null);
                db.saveProgress(tgId, idx+1, null, null, null);
                if (idx+1 >= survey.getQuestions().size()) {
                    db.finishAndCommit(tgId);
                    return "Спасибо! Анкетирование завершено.";
                }
                return "";
            }
        }

        // RATING 1..10
        if ("r".equals(parts[2])) {
            String val = parts[3];
            db.insertAnswer(respId, q.getId(), val, null);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) {
                db.finishAndCommit(tgId);
                return "Спасибо! Анкетирование завершено.";
            }
            return "";
        }

        // MULTI toggle + Другое (с автоматическим переходом при достижении max)
        if ("m".equals(parts[2])) {
            String optId = parts[3];
            List<String> selected = getMultiSelectedInternal(tgId);
            if (selected == null) selected = new ArrayList<>();

            Option opt = q.getOptions().stream().filter(x->x.getId().equals(optId)).findFirst().orElse(null);
            if (opt == null) return "Опция не найдена.";

            if (opt.isOther()) {
                // спросим текст, не меняя текущий набор
                db.saveProgress(tgId, idx, q.getId(), optId, selected);
                return "Напишите свой вариант:";
            }

            // Обычная опция: переключить её наличие как ID
            if (selected.contains(opt.getId())) selected.remove(opt.getId());
            else selected.add(opt.getId());

            int max = q.getMax();
            if (selected.size() > max) {
                return "Можно выбрать не более " + max + " вариантов.";
            }

            // Если достигнут максимум — фиксируем и идём дальше автоматически
            if (selected.size() == max) {
                List<String> labels = mapMultiLabels(q, selected);
                db.insertAnswer(respId, q.getId(), null, labels);
                db.saveProgress(tgId, idx+1, null, null, null);
                if (idx+1 >= survey.getQuestions().size()) {
                    db.finishAndCommit(tgId);
                    return "Спасибо! Анкетирование завершено.";
                }
                return ""; // без сообщения — следующий вопрос отправит Bot
            }

            // Иначе — остаёмся на вопросе
            db.saveProgress(tgId, idx, null, null, selected);
            return "";
        }

        // MULTI done (оставляем на случай, если пользователь им пользуется вручную)
        if ("mdone".equals(parts[2])) {
            List<String> selected = getMultiSelectedInternal(tgId);
            if (selected == null) selected = new ArrayList<>();
            int max = q.getMax();
            if (selected.size() != max) {
                return "Пожалуйста, выберите ровно " + max + " вариант(а).";
            }
            List<String> labels = mapMultiLabels(q, selected);
            db.insertAnswer(respId, q.getId(), null, labels);
            db.saveProgress(tgId, idx+1, null, null, null);
            if (idx+1 >= survey.getQuestions().size()) {
                db.finishAndCommit(tgId);
                return "Спасибо! Анкетирование завершено.";
            }
            return "";
        }

        return "";
    }

    public boolean isCompleted(long tgId) {
        return db.hasCompleted(tgId) && !db.inDraft(tgId);
    }

    /** Для Keyboards/отрисовки состояния MULTI — возвращаем как есть (ID и/или "Другое: ..."). */
    public Set<String> getMultiSelected(long tgId, String qId) {
        return new LinkedHashSet<>(db.getMultiSelected(tgId));
    }

    // Внутренний helper
    private List<String> getMultiSelectedInternal(long tgId) {
        return new ArrayList<>(db.getMultiSelected(tgId));
    }

    /** Преобразует список выбранных значений MULTI: ID → тексты опций, «Другое: ...» — как есть. */
    private static List<String> mapMultiLabels(Question q, List<String> selected) {
        Map<String,String> dict = q.getOptions().stream().collect(Collectors.toMap(Option::getId, Option::getText));
        List<String> labels = new ArrayList<>();
        for (String s : selected) {
            if (s.startsWith("Другое: ")) labels.add(s);
            else labels.add(dict.getOrDefault(s, s));
        }
        return labels;
    }
}