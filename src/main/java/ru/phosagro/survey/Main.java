// src/main/java/ru/phosagro/survey/Main.java
package ru.phosagro.survey;

import ru.phosagro.survey.db.Db;
import ru.phosagro.survey.service.AdminService;
import ru.phosagro.survey.service.SurveyService;

public class Main {
    // ⬇️ Вшитые значения для теста (можно позже убрать)
    private static final String DEFAULT_TOKEN = "";
    private static final String DEFAULT_USERNAME = "FosAgroAnket_bot";
    private static final long BOOTSTRAP_ADMIN_TG_ID = 726773708L;

    public static void main(String[] args) throws Exception {
        // Берём из ENV, если есть — иначе используем вшитые
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isBlank()) token = DEFAULT_TOKEN;

        String username = System.getenv("BOT_USERNAME");
        if (username == null || username.isBlank()) username = DEFAULT_USERNAME;

        Db db = new Db("data/survey.db");
        db.init();

        // Назначаем первичного админа без проверок (идемпотентно)
        db.ensureAdmin(BOOTSTRAP_ADMIN_TG_ID);

        SurveyService surveyService = new SurveyService(db);
        AdminService adminService = new AdminService(db, surveyService);

        Bot bot = new Bot(token, username, db, surveyService, adminService);
        bot.start();

        System.out.println("Bot started as @" + username + " ; bootstrap admin: " + BOOTSTRAP_ADMIN_TG_ID);
    }
}
