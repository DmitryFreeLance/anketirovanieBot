# === Сборка ===
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app

# Кэшируем зависимости (опционально, если есть отдельный deps слой)
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline

# Копируем исходники и собираем
COPY src ./src
RUN mvn -q clean package -DskipTests

# === Рантайм ===
FROM openjdk:21-jdk-slim
WORKDIR /app

# Создадим директорию для SQLite
RUN mkdir -p /app/data

# Устанавливаем системные библиотеки шрифтов (нужны для Apache POI autoSizeColumn)
# и очищаем кеш apt для уменьшения слоя.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
    libfreetype6 \
    libfontconfig1 \
    libxrender1 \
    libx11-6 \
    fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# Копируем fat-jar, собранный shade-плагином
# Имя совпадает с <finalName> из pom.xml
COPY --from=build /app/target/FosAgroAnket_bot.jar /app/app.jar

# Переменные окружения (можно переопределять при запуске)
# Включаем headless режим JVM, чтобы AWT старался не подниматься.
ENV BOT_TOKEN=""
ENV BOT_USERNAME=""
ENV TZ=Europe/Moscow
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=${TZ} -Djava.awt.headless=true"

# Данные бота (SQLite) – вынесем в volume
VOLUME ["/app/data"]

# Порт не обязателен (long-polling). Удаляем EXPOSE.
# EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]