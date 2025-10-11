CREATE TABLE IF NOT EXISTS interview_session (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  start_time TIMESTAMP NULL,
  end_time TIMESTAMP NULL,
  config_json TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS interview_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;