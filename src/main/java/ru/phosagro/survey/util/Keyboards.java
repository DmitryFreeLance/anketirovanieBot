package ru.phosagro.survey.util;

import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import ru.phosagro.survey.model.Option;
import ru.phosagro.survey.model.Question;
import ru.phosagro.survey.model.QuestionType;

import java.util.*;

public class Keyboards {

    /* ===== Старт ===== */

    public static InlineKeyboardMarkup startKeyboard(String caption) {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton(caption).callbackData("start")
        );
    }

    /* ===== Админ меню ===== */

    public static InlineKeyboardMarkup adminMenu() {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton("🔢 Общая статистика").callbackData("admin:stats"),
                new InlineKeyboardButton("👥 Пользователи").callbackData("admin:users"),
                new InlineKeyboardButton("📊 Excel-статистика").callbackData("admin:export")
        );
    }

    /** Навигация по странице статистики (много текста) */
    public static InlineKeyboardMarkup adminStatsNav(int pageIndex, int totalPages) {
        // pageIndex начинается с 1 в вызове из бота (0-ю страницу шлём без клавы)
        List<InlineKeyboardButton[]> rows = new ArrayList<>();
        if (pageIndex < totalPages - 1) {
            rows.add(new InlineKeyboardButton[]{
                    new InlineKeyboardButton("▶️ Следующая").callbackData("admin:stats:next:" + (pageIndex + 1))
            });
        }
        rows.add(new InlineKeyboardButton[]{
                new InlineKeyboardButton("🏠 Админ-панель").callbackData("admin:menu")
        });
        return new InlineKeyboardMarkup(rows.toArray(new InlineKeyboardButton[0][]));
    }

    /** Кнопка «следующая страница» в списке пользователей */
    public static InlineKeyboardMarkup adminUsersNext(int nextPageIndex) {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton("▶️ Следующая страница").callbackData("admin:users:page:" + nextPageIndex),
                new InlineKeyboardButton("🏠 Админ-панель").callbackData("admin:menu")
        );
    }

    /* ===== Анкета — клавиатуры для вопросов ===== */

    public static InlineKeyboardMarkup forQuestion(Question q, Set<String> selected) {
        if (selected == null) selected = new LinkedHashSet<>();
        List<InlineKeyboardButton[]> rows = new ArrayList<>();
        final int MAX_LEN = 28, MIN_LEN = 22;

        if (q.getType() == QuestionType.RATING_1_10) {
            InlineKeyboardButton[] r1 = new InlineKeyboardButton[5];
            InlineKeyboardButton[] r2 = new InlineKeyboardButton[5];
            for (int i = 1; i <= 5; i++)
                r1[i - 1] = new InlineKeyboardButton(String.valueOf(i)).callbackData("ans:" + q.getId() + ":r:" + i);
            for (int i = 6; i <= 10; i++)
                r2[i - 6] = new InlineKeyboardButton(String.valueOf(i)).callbackData("ans:" + q.getId() + ":r:" + i);
            rows.add(r1);
            rows.add(r2);
        } else if (q.getType() == QuestionType.SINGLE) {
            for (Option o : q.getOptions()) {
                if (o.isOther()) continue; // «Другое» убираем из кнопок — вводим текстом
                String label = pad(wrapLabel(o.getText(), MAX_LEN), MIN_LEN);
                rows.add(new InlineKeyboardButton[]{
                        new InlineKeyboardButton(label).callbackData("ans:" + q.getId() + ":s:" + o.getId())
                });
            }
        } else if (q.getType() == QuestionType.MULTI) {
            for (Option o : q.getOptions()) {
                if (o.isOther()) continue; // «Другое» — текстом
                boolean on = selected.contains(o.getId());
                String base = (on ? "✅ " : "") + o.getText();
                String label = pad(wrapLabel(base, MAX_LEN), MIN_LEN);
                rows.add(new InlineKeyboardButton[]{
                        new InlineKeyboardButton(label).callbackData("ans:" + q.getId() + ":m:" + o.getId())
                });
            }
        } else if (q.getType() == QuestionType.TEXT) {
            rows.add(new InlineKeyboardButton[]{
                    new InlineKeyboardButton("Напишите ответ текстом").callbackData("noop")
            });
        }
        return new InlineKeyboardMarkup(rows.toArray(new InlineKeyboardButton[0][]));
    }

    /* ===== Вспомогательные ===== */

    // Перенос слов в длинных подписях, чтобы кнопки были «шире»
    private static String wrapLabel(String s, int maxLineLen) {
        if (s == null) return "";
        if (s.length() <= maxLineLen) return s;
        String[] words = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        int cur = 0;
        for (String w : words) {
            if (cur == 0) { out.append(w); cur = w.length(); }
            else if (cur + 1 + w.length() <= maxLineLen) { out.append(' ').append(w); cur += 1 + w.length(); }
            else { out.append('\n').append(w); cur = w.length(); }
        }
        return out.toString();
    }

    // Немного «растягиваем» короткие подписи, чтобы в одной колонке выглядело ровнее
    private static String pad(String s, int minLen) {
        if (s == null) return "";
        int len = s.length();
        if (len >= minLen) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < (minLen - len); i++) sb.append('\u2009'); // тонкий пробел
        return sb.toString();
    }
}