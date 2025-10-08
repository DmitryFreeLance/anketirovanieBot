package ru.phosagro.survey;

import ru.phosagro.survey.db.Db;
import ru.phosagro.survey.service.AdminService;
import ru.phosagro.survey.service.SurveyService;

public class Main {
    public static void main(String[] args) throws Exception {
        // читаем из окружения (compose/.env), при отсутствии можно подставить дефолты
        String token = System.getenv().getOrDefault("BOT_TOKEN",
                "");
        String username = System.getenv().getOrDefault("BOT_USERNAME", "FosAgroAnket_bot");
        long bootstrapAdmin = Long.parseLong(
                System.getenv().getOrDefault("ADMIN_BOOTSTRAP", "726773708")
        );

        // БД
        Db db = new Db("data/survey.db");
        db.initSchema();       // было db.init();
        db.initPerformance();  // WAL/индексы/таймауты
        db.ensureAdmin(bootstrapAdmin);

        // Сервисы и бот
        SurveyService surveyService = new SurveyService(db);
        AdminService adminService = new AdminService(db, surveyService); // если требуется конструктор с surveyService
        Bot bot = new Bot(token, username, db, surveyService, adminService);

        System.out.println("Bot starting as @" + username);
        bot.start(); // long-polling
    }
}