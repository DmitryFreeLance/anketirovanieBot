package ru.phosagro.survey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.SendMessage;
import ru.phosagro.survey.db.Db;
import ru.phosagro.survey.model.Question;
import ru.phosagro.survey.model.QuestionType;
import ru.phosagro.survey.model.Survey;
import ru.phosagro.survey.service.AdminService;
import ru.phosagro.survey.service.SurveyService;
import ru.phosagro.survey.util.Keyboards;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bot {
    private final TelegramBot bot;
    private final Db db;
    private final SurveyService surveyService;
    private final AdminService adminService;
    private final Survey survey;

    // ---- защита от повторной установки listener’а ----
    private static final AtomicBoolean LISTENER_INSTALLED = new AtomicBoolean(false);

    // ---- LRU-дедуп по updateId (1000 последних) ----
    private final Set<Integer> seenUpdates = Collections.newSetFromMap(
            new LinkedHashMap<Integer, Boolean>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                    return size() > 1000;
                }
            }
    );

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

        System.out.println("[Bot] Init OK, username=@" + username + ", pid=" + ProcessHandle.current().pid());
    }

    public void start() {
        // гард: не даём установить listener дважды
        if (!LISTENER_INSTALLED.compareAndSet(false, true)) {
            System.out.println("[Bot] Updates listener already installed — skip.");
            return;
        }
        System.out.println("[Bot] Installing updates listener...");

        bot.setUpdatesListener(updates -> {
            try {
                for (Update u : updates) {
                    // --- ДЕДУП по updateId ---
                    Integer id = u.updateId();
                    boolean skip;
                    synchronized (seenUpdates) {
                        skip = seenUpdates.contains(id);
                        if (!skip) seenUpdates.add(id);
                    }
                    if (skip) {
                        // дубль апдейта — пропускаем
                        continue;
                    }

                    if (u.message() != null) {
                        handleMessage(u.message());
                    } else if (u.callbackQuery() != null) {
                        handleCallback(u.callbackQuery());
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            // подтверждаем все апдейты, чтобы Telegram не слал их снова
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    private void handleMessage(Message msg) {
        long chatId = msg.chat().id();
        User tgUser = msg.from();
        String text = msg.text() != null ? msg.text().trim() : "";

        db.ensureUser(tgUser);

        // Ждём свободный текст? (после «Другое» или TEXT-вопроса)
        if (surveyService.isAwaitingFreeText(tgUser.id()) && text != null && !text.isEmpty()) {
            String resp = surveyService.acceptFreeText(tgUser.id(), text);
            if (resp != null && !resp.isBlank()) bot.execute(new SendMessage(chatId, resp));
            Question q = surveyService.currentQuestion(tgUser.id());
            if (q != null) sendQuestion(chatId, tgUser.id(), q);
            else if (surveyService.isCompleted(tgUser.id())) bot.execute(new SendMessage(chatId, survey.getFinish()));
            return;
        }

        if ("/start".equalsIgnoreCase(text)) {
            if (surveyService.userCompleted(tgUser.id())) { bot.execute(new SendMessage(chatId, "Вы уже проходили анкетирование. Спасибо!")); return; }
            InlineKeyboardMarkup kb = Keyboards.startKeyboard(survey.getStartButton());
            bot.execute(new SendMessage(chatId, survey.getWelcome()).replyMarkup(kb));
            return;
        }

        if ("/restart".equalsIgnoreCase(text)) {
            if (surveyService.userCompleted(tgUser.id())) { bot.execute(new SendMessage(chatId, "Вы уже проходили анкетирование. Спасибо!")); return; }
            if (db.inDraft(tgUser.id())) {
                if (surveyService.isAwaitingFreeText(tgUser.id())) bot.execute(new SendMessage(chatId, "Напишите свой вариант:"));
                else {
                    Question q = surveyService.currentQuestion(tgUser.id());
                    if (q != null) sendQuestion(chatId, tgUser.id(), q);
                    else bot.execute(new SendMessage(chatId, survey.getWelcome()).replyMarkup(Keyboards.startKeyboard(survey.getStartButton())));
                }
            } else bot.execute(new SendMessage(chatId, survey.getWelcome()).replyMarkup(Keyboards.startKeyboard(survey.getStartButton())));
            return;
        }

        if ("/resetme".equalsIgnoreCase(text)) {
            if (!db.isAdmin(tgUser.id())) { bot.execute(new SendMessage(chatId, "Доступ запрещён.")); return; }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:data/survey.db")) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM answers WHERE response_id IN (SELECT r.id FROM responses r JOIN users u ON u.id=r.user_id WHERE u.tg_id=?)")) { ps.setLong(1, tgUser.id()); ps.executeUpdate(); }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM responses WHERE user_id IN (SELECT id FROM users WHERE tg_id=?)")) { ps.setLong(1, tgUser.id()); ps.executeUpdate(); }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM user_progress WHERE user_id IN (SELECT id FROM users WHERE tg_id=?)")) { ps.setLong(1, tgUser.id()); ps.executeUpdate(); }
                c.commit();
                bot.execute(new SendMessage(chatId, "Ваши ответы и прогресс очищены. Введите /start для нового прохождения."));
            } catch (Exception e) { e.printStackTrace(); bot.execute(new SendMessage(chatId, "Ошибка при очистке: " + e.getMessage())); }
            return;
        }

        if (text.startsWith("/adminadd")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) { bot.execute(new SendMessage(chatId, "Использование: /adminadd <telegram_id>")); return; }
            long toAdd;
            try { toAdd = Long.parseLong(parts[1]); } catch (Exception e) { bot.execute(new SendMessage(chatId, "ID должен быть числом.")); return; }
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
            String[] parts = text.split("\\s+");
            if (parts.length < 2) { bot.execute(new SendMessage(chatId, "Использование: /user <telegram_id>")); return; }
            long reqId;
            try { reqId = Long.parseLong(parts[1]); } catch (Exception e) { bot.execute(new SendMessage(chatId, "ID должен быть числом.")); return; }
            String res = adminService.showUserAnswers(tgUser.id(), reqId);
            bot.execute(new SendMessage(chatId, res));
            return;
        }

        // Прочий текст
        if (surveyService.userCompleted(tgUser.id())) { bot.execute(new SendMessage(chatId, "Опрос завершён. Спасибо!")); return; }
        if (db.inDraft(tgUser.id())) {
            Question q = surveyService.currentQuestion(tgUser.id());
            if (q != null) { sendQuestion(chatId, tgUser.id(), q); return; }
        }
        bot.execute(new SendMessage(chatId, "Пожалуйста, используйте кнопки ниже. Если они исчезли — введите /restart."));
    }

    private void handleCallback(CallbackQuery cb) {
        long chatId = cb.message().chat().id();
        long uid = cb.from().id();
        String data = cb.data();
        if (data == null) return;

        // START
        if (data.equals("start")) {
            if (surveyService.userCompleted(uid)) {
                bot.execute(new SendMessage(chatId, "Вы уже проходили анкетирование. Спасибо!"));
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
            surveyService.startSurvey(uid);
            Question q = surveyService.currentQuestion(uid);
            if (q != null) sendQuestion(chatId, uid, q);
            bot.execute(new AnswerCallbackQuery(cb.id()));
            return;
        }

        // ------- ADMIN callbacks -------
        if (data.startsWith("admin:")) {
            if ("admin:menu".equals(data)) {
                String res = adminService.openAdminPanel(uid);
                bot.execute(new SendMessage(chatId, res).replyMarkup(Keyboards.adminMenu()));
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
            if ("admin:stats".equals(data)) {
                int totalPages = adminService.statsTotalPages();
                String page0 = adminService.buildStatsPage(0);
                bot.execute(new SendMessage(chatId, page0)); // заголовок
                if (totalPages > 1) {
                    String page1 = adminService.buildStatsPage(1);
                    sendPagedStats(chatId, page1, 1, totalPages);
                }
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
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
            if ("admin:users".equals(data)) {
                sendUsersPage(chatId, 0);
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }
            if (data.startsWith("admin:users:page:")) {
                try {
                    int idx = Integer.parseInt(data.substring("admin:users:page:".length()));
                    sendUsersPage(chatId, idx);
                } catch (Exception ignored) {}
                bot.execute(new AnswerCallbackQuery(cb.id()));
                return;
            }

            String res = adminService.handleAdminCallback(uid, data);
            if (res != null && !res.isBlank() && !"__MULTI__".equals(res) && !"__USERS__".equals(res)) {
                bot.execute(new SendMessage(chatId, res).replyMarkup(Keyboards.adminMenu()));
            }
            bot.execute(new AnswerCallbackQuery(cb.id()));
            return;
        }

        // ------- SURVEY callbacks -------
        String feedback = surveyService.handleCallback(uid, data);
        if (feedback != null && !feedback.isBlank()) bot.execute(new SendMessage(chatId, feedback));

        // Если ждём свободный текст (после «Другое») — НЕ присылаем вопрос повторно
        if (surveyService.isAwaitingFreeText(uid)) {
            bot.execute(new AnswerCallbackQuery(cb.id()));
            return;
        }

        Question q = surveyService.currentQuestion(uid);
        if (q != null) sendQuestion(chatId, uid, q);
        else if (surveyService.isCompleted(uid)) bot.execute(new SendMessage(chatId, survey.getFinish()));
        bot.execute(new AnswerCallbackQuery(cb.id()));
    }

    /** Пагинация списка пользователей: 15 на страницу; кнопки нет, если на странице < 10. */
    private void sendUsersPage(long chatId, int pageIndex) {
        List<Long> users = adminService.listUsersPage(pageIndex);
        if (users.isEmpty()) {
            bot.execute(new SendMessage(chatId, "Нет завершённых анкет."));
            return;
        }
        StringBuilder sb = new StringBuilder("Пользователи (страница ").append(pageIndex + 1).append("):\n");
        for (Long id : users) sb.append("• ").append(id).append("  (/user ").append(id).append(")\n");

        if (users.size() >= 10) bot.execute(new SendMessage(chatId, sb.toString()).replyMarkup(Keyboards.adminUsersNext(pageIndex + 1)));
        else bot.execute(new SendMessage(chatId, sb.toString()));
    }

    /** Отправка вопроса пользователю. */
    private void sendQuestion(long chatId, long uid, Question q) {
        StringBuilder text = new StringBuilder(q.getText());

        if (q.getType() == QuestionType.MULTI) {
            int sel = surveyService.getMultiSelected(uid, q.getId()).size();
            text.append("\n\nВыбрано: ").append(sel).append(" / ").append(q.getMax());
        }

        if (q.getType() == QuestionType.TEXT) {
            surveyService.prepareAwaitingText(uid, q.getId());
            text.append("\n\nНапишите, пожалуйста, ваш ответ текстом.");
        }

        InlineKeyboardMarkup kb = Keyboards.forQuestion(q, surveyService.getMultiSelected(uid, q.getId()));
        bot.execute(new SendMessage(chatId, text.toString()).replyMarkup(kb));
    }

    // --- безопасная отправка длинной страницы статистики (чанки <= ~3800 символов)
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
}