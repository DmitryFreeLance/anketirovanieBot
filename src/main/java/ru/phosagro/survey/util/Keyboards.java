package ru.phosagro.survey.util;

import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import ru.phosagro.survey.model.Option;
import ru.phosagro.survey.model.Question;
import ru.phosagro.survey.model.QuestionType;

import java.util.*;

public class Keyboards {

    public static InlineKeyboardMarkup startKeyboard(String caption) {
        return new InlineKeyboardMarkup(new InlineKeyboardButton(caption).callbackData("start"));
    }

    public static InlineKeyboardMarkup adminMenu() {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton("🔢 Общая статистика").callbackData("admin:stats"),
                new InlineKeyboardButton("👥 Пользователи").callbackData("admin:users")
        );
    }

    /** Навигация по страницам общей статистики: слева ➡️ следующий, справа 🏠 домой. */
    public static InlineKeyboardMarkup adminStatsNav(int pageIndex, int totalPages) {
        List<InlineKeyboardButton[]> rows = new ArrayList<>();
        if (pageIndex < totalPages - 1) {
            rows.add(new InlineKeyboardButton[] {
                    new InlineKeyboardButton("➡️ следующий вопрос").callbackData("admin:stats:next:" + (pageIndex + 1)),
                    new InlineKeyboardButton("🏠 в админ панель").callbackData("admin:menu")
            });
        } else {
            rows.add(new InlineKeyboardButton[] {
                    new InlineKeyboardButton("🏠 в админ панель").callbackData("admin:menu")
            });
        }
        return new InlineKeyboardMarkup(rows.toArray(new InlineKeyboardButton[0][]));
    }

    /** Навигация по страницам списка пользователей (только «вперёд»). */
    public static InlineKeyboardMarkup adminUsersNext(int nextPageIndex) {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton("➡️ следующая страница").callbackData("admin:users:page:" + nextPageIndex)
        );
    }

    /** Мягкий перенос длинных надписей в inline-кнопках. */
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

    /** Паддинг тонкими пробелами (U+2009) до минимальной длины — визуально расширяет кнопку. */
    private static String padThinSpaces(String s, int minLen) {
        if (s == null) return "";
        int len = s.length();
        if (len >= minLen) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < (minLen - len); i++) sb.append('\u2009'); // THIN SPACE
        return sb.toString();
    }

    public static InlineKeyboardMarkup forQuestion(Question q, Set<String> selected) {
        if (selected == null) selected = new LinkedHashSet<>();
        List<InlineKeyboardButton[]> rows = new ArrayList<>();

        final int MAX_LEN = 28;   // перенос строки в кнопке
        final int MIN_LEN = 22;   // минимальная «ширина» кнопки тонкими пробелами

        if (q.getType() == QuestionType.RATING_1_10) {
            InlineKeyboardButton[] r1 = new InlineKeyboardButton[5];
            InlineKeyboardButton[] r2 = new InlineKeyboardButton[5];
            for (int i=1;i<=5;i++) r1[i-1] = new InlineKeyboardButton(String.valueOf(i)).callbackData("ans:" + q.getId() + ":r:" + i);
            for (int i=6;i<=10;i++) r2[i-6] = new InlineKeyboardButton(String.valueOf(i)).callbackData("ans:" + q.getId() + ":r:" + i);
            rows.add(r1); rows.add(r2);
        } else if (q.getType() == QuestionType.SINGLE) {
            for (Option o: q.getOptions()) {
                String base = o.getText(); // БЕЗ " (напишите в чат)"
                String label = padThinSpaces(wrapLabel(base, MAX_LEN), MIN_LEN);
                rows.add(new InlineKeyboardButton[] {
                        new InlineKeyboardButton(label).callbackData("ans:" + q.getId() + ":s:" + o.getId())
                });
            }
        } else if (q.getType() == QuestionType.MULTI) {
            for (Option o: q.getOptions()) {
                boolean on = selected.contains(o.getId()); // отмечаем выбранные по ID
                String base = o.getText(); // БЕЗ " (напишите в чат)"
                String prefix = on ? "✅ " : "";
                String label = padThinSpaces(wrapLabel(prefix + base, MAX_LEN), MIN_LEN);
                rows.add(new InlineKeyboardButton[] {
                        new InlineKeyboardButton(label).callbackData("ans:" + q.getId() + ":m:" + o.getId())
                });
            }
            // Кнопку «Готово» не добавляем — автопереход.
        } else if (q.getType() == QuestionType.TEXT) {
            rows.add(new InlineKeyboardButton[] {
                    new InlineKeyboardButton("Напишите ответ текстом").callbackData("noop")
            });
        }
        return new InlineKeyboardMarkup(rows.toArray(new InlineKeyboardButton[0][]));
    }
}