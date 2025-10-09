package ru.phosagro.survey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.request.InputFile;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.*;
import ru.phosagro.survey.db.Db;
import ru.phosagro.survey.model.Option;
import ru.phosagro.survey.model.Question;
import ru.phosagro.survey.model.QuestionType;
import ru.phosagro.survey.model.Survey;
import ru.phosagro.survey.service.AdminService;
import ru.phosagro.survey.service.SurveyService;
import ru.phosagro.survey.util.Keyboards;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class Bot {
    private final TelegramBot bot;
    private final Db db;
    private final SurveyService surveyService;
    private final AdminService adminService;
    private final Survey survey;

    // защита от двойной установки listener
    private static final AtomicBoolean LISTENER_INSTALLED = new AtomicBoolean(false);
    // дедуп апдейтов
    private final Set<Integer> seenUpdates = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<Integer, Boolean>(1024, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Boolean> eldest) { return size() > 1000; }
            });

    public Bot(String token, String username, Db db, SurveyService surveyService, AdminService adminService) throws Exception {
        this.bot = new TelegramBot(token);
        this.db = db;
        this.surveyService = surveyService;
        this.adminService = adminService;

        try (InputStream in = getClass().getResourceAsStream("/survey.json")) {
            this.survey = new ObjectMapper().readValue(in, Survey.class);
        }
        surveyService.setSurvey(this.survey);

        try { this.db.initSchema(); } catch (Throwable ignored) {}
        try { this.db.initPerformance(); } catch (Throwable ignored) {}
    }

    public void start() {
        if (!LISTENER_INSTALLED.compareAndSet(false, true)) return;
        bot.setUpdatesListener(updates -> {
            try {
                for (Update u : updates) {
                    Integer id = u.updateId();
                    synchronized (seenUpdates) { if (seenUpdates.contains(id)) continue; seenUpdates.add(id); }
                    if (u.message() != null) handleMessage(u.message());
                    else if (u.callbackQuery() != null) handleCallback(u.callbackQuery());
                }
            } catch (Exception ex) { ex.printStackTrace(); }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    /* ========================= messages ========================= */

    private void handleMessage(Message msg) {
        long chatId = msg.chat().id();
        User tgUser = msg.from();
        String text = msg.text() != null ? msg.text().trim() : "";

        db.ensureUser(tgUser);

        // Текст как ответ (TEXT или вопросы с подсказкой "напишите свой вариант...")
        if (surveyService.isAwaitingFreeText(tgUser.id()) && text != null && !text.isEmpty()) {
            Question q = surveyService.currentQuestion(tgUser.id());
            if (q == null) return;

            if (q.getType() == QuestionType.MULTI) {
                // MULTI: добавить свой вариант и перерисовать то же сообщение (или финализировать)
                handleFreeTextForMulti(chatId, tgUser.id(), q, text);
                return;
            } else {
                // TEXT или SINGLE с «Другое»: редактируем текущее сообщение → "Ваш ответ: …", затем следующий вопрос
                editCurrentToAnswer(chatId, tgUser.id(), q, text);
                // сохранить ответ и перейти дальше
                String resp = surveyService.acceptFreeText(tgUser.id(), text); // это продвинет индекс/завершит
                Question next = surveyService.currentQuestion(tgUser.id());
                if (next != null) sendNewQuestion(chatId, tgUser.id(), next);
                else if (surveyService.isCompleted(tgUser.id())) bot.execute(new SendMessage(chatId, survey.getFinish()));
                if (resp != null && !resp.isBlank()) bot.execute(new SendMessage(chatId, resp));
                return;
            }
        }

        // команды
        if ("/start".equalsIgnoreCase(text)) {
            if (surveyService.userCompleted(tgUser.id())) { bot.execute(new SendMessage(chatId, "Вы уже проходили анкетирование. Спасибо!")); return; }
            InlineKeyboardMarkup kb = Keyboards.startKeyboard(survey.getStartButton());
            bot.execute(new SendMessage(chatId, survey.getWelcome()).replyMarkup(kb));
            return;
        }

        if ("/restart".equalsIgnoreCase(text)) {
            if (surveyService.userCompleted(tgUser.id())) { bot.execute(new SendMessage(chatId, "Вы уже проходили анкетирование. Спасибо!")); return; }
            if (db.inDraft(tgUser.id())) {
                Question q = surveyService.currentQuestion(tgUser.id());
                if (q != null) resendCurrent(chatId, tgUser.id(), q);
                else bot.execute(new SendMessage(chatId, survey.getWelcome()).replyMarkup(Keyboards.startKeyboard(survey.getStartButton())));
            } else {
                bot.execute(new SendMessage(chatId, survey.getWelcome()).replyMarkup(Keyboards.startKeyboard(survey.getStartButton())));
            }
            return;
        }

        if ("/resetme".equalsIgnoreCase(text)) {
            if (!db.isAdmin(tgUser.id())) { bot.execute(new SendMessage(chatId, "Доступ запрещён.")); return; }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:data/survey.db")) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM answers WHERE response_id IN (SELECT r.id FROM responses r JOIN users u ON u.id=r.user_id WHERE u.tg_id=?)")) { ps.setLong(1, tgUser.id()); ps.executeUpdate(); }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM responses WHERE user_id IN (SELECT id FROM users WHERE tg_id=?)")) { ps.setLong(1, tgUser.id()); ps.executeUpdate(); }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM user_progress WHERE user_id IN (SELECT id FROM users WHERE tg_id=?)")) { ps.setLong(1, tgUser.id()); ps.executeUpdate(); }
                c.commit();
                bot.execute(new SendMessage(chatId, "Ваши ответы и прогресс очищены. Введите /start для нового прохождения."));
            } catch (Exception e) { e.printStackTrace(); bot.execute(new SendMessage(chatId, "Ошибка при очистке: " + e.getMessage())); }
            return;
        }

        if (text.startsWith("/adminadd")) {
            String[] parts = text.split("\\s+"); if (parts.length < 2) { bot.execute(new SendMessage(chatId, "Использование: /adminadd <telegram_id>")); return; }
            long toAdd; try { toAdd = Long.parseLong(parts[1]); } catch (Exception e) { bot.execute(new SendMessage(chatId, "ID должен быть числом.")); return; }
            String res = adminService.addAdmin(tgUser.id(), toAdd);
            bot.execute(new SendMessage(chatId, res));
            return;
        }

        if ("/admin".equalsIgnoreCase(text)) {
            String res = adminService.openAdminPanel(tgUser.id());
            bot.execute(new SendMessage(chatId, res).replyMarkup(Keyboards.adminMenu()));
            return;
        }

        if (text.startsWith("/user")) {
            String[] parts = text.split("\\s+"); if (parts.length < 2) { bot.execute(new SendMessage(chatId, "Использование: /user <telegram_id>")); return; }
            long reqId; try { reqId = Long.parseLong(parts[1]); } catch (Exception e) { bot.execute(new SendMessage(chatId, "ID должен быть числом.")); return; }
            String res = adminService.showUserAnswers(tgUser.id(), reqId);
            bot.execute(new SendMessage(chatId, res));
            return;
        }

        // прочий текст
        if (surveyService.userCompleted(tgUser.id())) { bot.execute(new SendMessage(chatId, "Опрос завершён. Спасибо!")); return; }
        if (db.inDraft(tgUser.id())) {
            Question q = surveyService.currentQuestion(tgUser.id());
            if (q != null) { resendCurrent(chatId, tgUser.id(), q); return; }
        }
        bot.execute(new SendMessage(chatId, "Пожалуйста, используйте кнопки ниже. Если они исчезли — введите /restart."));
    }

    /* ========================= callbacks ========================= */

    private void handleCallback(CallbackQuery cb) {
        long chatId = cb.message().chat().id();
        long uid = cb.from().id();
        String data = cb.data();
        if (data == null) {
            bot.execute(new AnswerCallbackQuery(cb.id()));
            return;
        }

        // ==== Старт анкеты ====
        if ("start".equals(data)) {
            if (surveyService.userCompleted(uid)) {
                bot.execute(new SendMessage(chatId, "Вы уже проходили анкетирование. Спасибо!"));
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
            surveyService.startSurvey(uid);
            Question q = surveyService.currentQuestion(uid);
            if (q != null) sendNewQuestion(chatId, uid, q);
            bot.execute(new AnswerCallbackQuery(cb.id()));
            return;
        }

        // ==== АДМИН ====
        if (data.startsWith("admin:")) {

            // Вернуться в админ-панель
            if ("admin:menu".equals(data)) {
                String res = adminService.openAdminPanel(uid);
                bot.execute(new SendMessage(chatId, res).replyMarkup(Keyboards.adminMenu()));
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // Общая статистика (постранично: 0 — "Завершили опрос", дальше — вопросы)
            if ("admin:stats".equals(data)) {
                int totalPages = adminService.statsTotalPages();
                String page0 = adminService.buildStatsPage(0);
                bot.execute(new SendMessage(chatId, page0));
                if (totalPages > 1) {
                    String page1 = adminService.buildStatsPage(1);
                    sendPagedStats(chatId, page1, 1, totalPages);
                }
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // Следующая страница статистики
            if (data.startsWith("admin:stats:next:")) {
                try {
                    int pageIndex = Integer.parseInt(data.substring("admin:stats:next:".length()));
                    int totalPages = adminService.statsTotalPages();
                    if (pageIndex >= 1 && pageIndex < totalPages) {
                        String page = adminService.buildStatsPage(pageIndex);
                        sendPagedStats(chatId, page, pageIndex, totalPages);
                    }
                } catch (Exception ignored) {}
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // Пользователи: страница 1
            if ("admin:users".equals(data)) {
                sendUsersPage(chatId, 0);
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // Пользователи: следующая страница
            if (data.startsWith("admin:users:page:")) {
                try {
                    int idx = Integer.parseInt(data.substring("admin:users:page:".length()));
                    sendUsersPage(chatId, idx);
                } catch (Exception ignored) {}
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // Экспорт Excel со всей общей статистикой
            if ("admin:export".equals(data)) {
                if (!db.isAdmin(uid)) {
                    bot.execute(new AnswerCallbackQuery(cb.id()).text("Доступ запрещён."));
                    return;
                }
                byte[] xlsx = adminService.exportStatsXlsx();
                if (xlsx == null || xlsx.length == 0) {
                    bot.execute(new SendMessage(chatId, "Не удалось сформировать Excel."));
                } else {
                    java.io.File tmp = null;
                    try {
                        tmp = java.io.File.createTempFile("fosagro_stats_", ".xlsx");
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                            fos.write(xlsx);
                        }
                        // Отправляем как файл
                        SendDocument doc = new SendDocument(chatId, tmp);
                        bot.execute(doc);
                    } catch (Exception e) {
                        e.printStackTrace();
                        bot.execute(new SendMessage(chatId, "Ошибка при отправке Excel: " + e.getMessage()));
                    } finally {
                        if (tmp != null) {
                            // Пытаемся удалить временный файл
                            try { if (!tmp.delete()) tmp.deleteOnExit(); } catch (Exception ignored) {}
                        }
                    }
                }
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // Фоллбек для прочих admin:* (если есть)
            String res = adminService.handleAdminCallback(uid, data);
            if (res != null && !res.isBlank() && !"__MULTI__".equals(res) && !"__USERS__".equals(res)) {
                bot.execute(new SendMessage(chatId, res).replyMarkup(Keyboards.adminMenu()));
            }
            bot.execute(new AnswerCallbackQuery(cb.id()));
            return;
        }

        // ==== АНКЕТА ====
        if (data.startsWith("ans:")) {
            String[] parts = data.split(":");
            if (parts.length < 3) {
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
            String qId = parts[1];
            String kind = parts[2];

            Question q = findQuestionById(qId);
            if (q == null) {
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // SINGLE или RATING: редактируем сообщение → "Ваш ответ: <b>...</b>", фиксируем, показываем следующий вопрос
            if ("s".equals(kind) || "r".equals(kind)) {
                Integer msgId = db.getCurrentMessageId(uid);
                String chosen;
                if ("r".equals(kind)) {
                    chosen = parts[3];
                } else {
                    String optId = parts[3];
                    Optional<Option> op = q.getOptions().stream().filter(o -> o.getId().equals(optId)).findFirst();
                    chosen = op.map(Option::getText).orElse(optId);
                }

                if (msgId != null) {
                    String txt = escapeHtml(q.getText()) + "\n\n<b>Ваш ответ:</b> <b>" + escapeHtml(chosen) + "</b>";
                    bot.execute(new EditMessageText(chatId, msgId, txt).parseMode(ParseMode.HTML));
                }

                // Бизнес-логика
                surveyService.handleCallback(uid, data);

                // Следующий вопрос (или завершение)
                Question next = surveyService.currentQuestion(uid);
                if (next != null) sendNewQuestion(chatId, uid, next);
                else if (surveyService.isCompleted(uid)) bot.execute(new SendMessage(chatId, survey.getFinish()));

                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            // MULTI: тумблер опции с подсветкой выбранных.
            if ("m".equals(kind)) {
                String optId = parts[3];
                Map<String,Object> prog = db.loadProgress(uid);
                if (prog == null) {
                    bot.execute(new AnswerCallbackQuery(cb.id()));
                    return;
                }
                List<String> selected = new ArrayList<>(db.getMultiSelected(uid));
                boolean wasSelected = selected.contains(optId);
                if (wasSelected) selected.remove(optId); else selected.add(optId);

                int max = q.getMax();
                if (selected.size() > max) {
                    bot.execute(new AnswerCallbackQuery(cb.id()).text("Можно выбрать не более " + max));
                    return;
                }

                Integer msgId = db.getCurrentMessageId(uid);

                // Пока не достигли max — просто сохраняем и перерисовываем то же сообщение
                if (selected.size() < max) {
                    db.saveProgress(uid, (int)prog.get("current_q_index"), null, null, selected);
                    if (msgId != null) {
                        String newText = buildQuestionText(q, uid); // уже HTML + "Ваши ответы:"
                        EditMessageText emt = new EditMessageText(chatId, msgId, newText)
                                .parseMode(ParseMode.HTML)
                                .replyMarkup(Keyboards.forQuestion(q, surveyService.getMultiSelected(uid, q.getId())));
                        bot.execute(emt);
                    }
                    bot.execute(new AnswerCallbackQuery(cb.id()));
                    return;
                }

                // Достигли max: фиксируем ответы, двигаем индекс
                List<String> labels = selected.stream().map(s -> {
                    Optional<Option> op = q.getOptions().stream().filter(o -> o.getId().equals(s)).findFirst();
                    return op.map(Option::getText).orElse(s);
                }).collect(java.util.stream.Collectors.toList());

                long respId = (long) prog.get("response_id");
                db.insertAnswer(respId, q.getId(), null, labels);
                db.saveProgress(uid, (int)prog.get("current_q_index")+1, null, null, null);

                // Перерисуем текущее сообщение финальным видом
                if (msgId != null) {
                    String finalText = escapeHtml(q.getText()) + "\n\n<b>Ваши ответы:</b> " + boldJoin(labels);
                    bot.execute(new EditMessageText(chatId, msgId, finalText).parseMode(ParseMode.HTML));
                }

                // Следующий вопрос (или конец)
                Question next = surveyService.currentQuestion(uid);
                if (next != null) sendNewQuestion(chatId, uid, next);
                else if (surveyService.isCompleted(uid)) bot.execute(new SendMessage(chatId, survey.getFinish()));

                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
        }

        // по умолчанию — просто ACK
        bot.execute(new AnswerCallbackQuery(cb.id()));
    }

    /* ========================= helpers ========================= */

    private void resendCurrent(long chatId, long uid, Question q) {
        Integer msgId = db.getCurrentMessageId(uid);
        String text = buildQuestionText(q, uid);
        InlineKeyboardMarkup kb = Keyboards.forQuestion(q, surveyService.getMultiSelected(uid, q.getId()));
        if (msgId != null) {
            bot.execute(new EditMessageText(chatId, msgId, text).parseMode(ParseMode.HTML).replyMarkup(kb));
        } else {
            var res = bot.execute(new SendMessage(chatId, text).parseMode(ParseMode.HTML).replyMarkup(kb));
            if (res != null && res.message()!=null) db.setCurrentMessageId(uid, res.message().messageId());
        }
    }

    /** Отправить новый вопрос и запомнить его message_id. */
    private void sendNewQuestion(long chatId, long uid, Question q) {
        String text = buildQuestionText(q, uid);
        InlineKeyboardMarkup kb = Keyboards.forQuestion(q, surveyService.getMultiSelected(uid, q.getId()));
        var res = bot.execute(new SendMessage(chatId, text).parseMode(ParseMode.HTML).replyMarkup(kb));
        if (res != null && res.message()!=null) db.setCurrentMessageId(uid, res.message().messageId());
    }

    /** Редактировать текущее сообщение в «Ваш ответ: …» (без клавиатуры). */
    private void editCurrentToAnswer(long chatId, long uid, Question q, String answerText) {
        Integer msgId = db.getCurrentMessageId(uid);
        if (msgId == null) return;
        String text = escapeHtml(q.getText()) + "\n\n<b>Ваш ответ:</b> <b>" + escapeHtml(answerText) + "</b>";
        bot.execute(new EditMessageText(chatId, msgId, text).parseMode(ParseMode.HTML));
    }

    /** Ветвь для текстового «своего варианта» на MULTI: добавить, перерисовать, либо финализировать при достижении max. */
    private void handleFreeTextForMulti(long chatId, long uid, Question q, String textInput) {
        Map<String,Object> prog = db.loadProgress(uid);
        if (prog == null) return;
        List<String> selected = new ArrayList<>(db.getMultiSelected(uid));
        selected.add(textInput); // без префикса
        int max = q.getMax();

        Integer msgId = db.getCurrentMessageId(uid);

        if (selected.size() < max) {
            db.saveProgress(uid, (int)prog.get("current_q_index"), null, null, selected);
            if (msgId != null) {
                String newText = buildMultiText(q, uid);
                bot.execute(new EditMessageText(chatId, msgId, newText)
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(Keyboards.forQuestion(q, surveyService.getMultiSelected(uid, q.getId()))));
            }
            return;
        }

        // reached max
        List<String> labels = selected.stream().map(s -> {
            Optional<Option> op = q.getOptions().stream().filter(o -> o.getId().equals(s)).findFirst();
            return op.map(Option::getText).orElse(s);
        }).collect(Collectors.toList());

        long respId = (long) prog.get("response_id");
        db.insertAnswer(respId, q.getId(), null, labels);
        db.saveProgress(uid, (int)prog.get("current_q_index")+1, null, null, null);

        if (msgId != null) {
            String finalText = escapeHtml(q.getText()) + "\n\n<b>Ваши ответы:</b> " + boldJoin(labels);
            bot.execute(new EditMessageText(chatId, msgId, finalText).parseMode(ParseMode.HTML));
        }

        Question next = surveyService.currentQuestion(uid);
        if (next != null) sendNewQuestion(chatId, uid, next);
        else if (surveyService.isCompleted(uid)) bot.execute(new SendMessage(chatId, survey.getFinish()));
    }

    /** Текст вопроса с подсказкой и счётчиком/списком для MULTI. */
    private String buildQuestionText(Question q, long uid) {
        StringBuilder sb = new StringBuilder(escapeHtml(q.getText()));
        boolean hasOther = q.getOptions()!=null && q.getOptions().stream().anyMatch(Option::isOther);
        if (hasOther) sb.append("\n\nМожете написать свой вариант ответа в чат.");

        if (q.getType() == QuestionType.MULTI) {
            sb.append("\n\nВыбрано: ")
                    .append(surveyService.getMultiSelected(uid, q.getId()).size())
                    .append(" / ").append(q.getMax());
            // HTML из renderSelectedList
            String selectedHtml = SurveyService.renderSelectedList(q, surveyService.getMultiSelected(uid, q.getId()));
            sb.append("\n").append(selectedHtml);
        }
        return sb.toString();
    }

    /** Только для MULTI: упрощённый билд текста с актуальным списком. */
    private String buildMultiText(Question q, long uid) {
        return buildQuestionText(q, uid);
    }

    private Question findQuestionById(String qId) {
        for (Question q : survey.getQuestions()) if (q.getId().equals(qId)) return q;
        return null;
    }

    /* ===== admin ui helpers ===== */

    private void sendUsersPage(long chatId, int pageIndex) {
        List<Long> users = adminService.listUsersPage(pageIndex);
        if (users.isEmpty()) {
            bot.execute(new SendMessage(chatId, "Нет завершённых анкет."));
            return;
        }
        StringBuilder sb = new StringBuilder("Пользователи (страница ").append(pageIndex + 1).append("):\n");
        for (Long id : users) sb.append("• ").append(id).append("  (/user ").append(id).append(")\n");
        if (users.size() >= 10)
            bot.execute(new SendMessage(chatId, sb.toString()).replyMarkup(Keyboards.adminUsersNext(pageIndex + 1)));
        else
            bot.execute(new SendMessage(chatId, sb.toString()));
    }

    private void sendPagedStats(long chatId, String fullText, int pageIndex, int totalPages) {
        List<String> chunks = splitBySize(fullText, 3800);
        for (int i = 0; i < chunks.size(); i++) {
            boolean last = (i == chunks.size() - 1);
            if (last) bot.execute(new SendMessage(chatId, chunks.get(i)).replyMarkup(Keyboards.adminStatsNav(pageIndex, totalPages)));
            else bot.execute(new SendMessage(chatId, chunks.get(i)));
        }
    }

    private static List<String> splitBySize(String text, int maxChars) {
        if (text == null) return List.of("");
        if (text.length() <= maxChars) return List.of(text);
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (cur.length() + line.length() + 1 > maxChars) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /* ====== small HTML helpers ====== */

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String boldJoin(java.util.List<String> items) {
        return items.stream()
                .map(Bot::escapeHtml)
                .map(t -> "<b>" + t + "</b>")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
