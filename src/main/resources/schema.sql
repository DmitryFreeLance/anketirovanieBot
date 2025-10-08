PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tg_id INTEGER NOT NULL UNIQUE,
  first_name TEXT,
  last_name TEXT,
  username TEXT,
  is_admin INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS responses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('DRAFT','COMPLETED')),
  started_at TEXT NOT NULL,
  completed_at TEXT,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS answers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  response_id INTEGER NOT NULL,
  question_id TEXT NOT NULL,
  answer_text TEXT,           -- для текстов и single/scale
  option_ids_json TEXT,       -- для мультивыбора: JSON-массив строк
  created_at TEXT NOT NULL,
  FOREIGN KEY (response_id) REFERENCES responses(id)
);

-- Прогресс (сохраняется только до завершения, затем удаляется)
CREATE TABLE IF NOT EXISTS user_progress (
  user_id INTEGER PRIMARY KEY,
  response_id INTEGER NOT NULL,
  current_q_index INTEGER NOT NULL,
  awaiting_other_question_id TEXT, -- если ждём "другое -> текст"
  awaiting_other_option_id TEXT,
  multi_selection_json TEXT,       -- JSON-массив выбранных optionId для текущего мультивопроса
  updated_at TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (response_id) REFERENCES responses(id)
);