-- Initial schema for Friends plugin (MySQL-compatible; simple SQLite fallback should also work for most types)

CREATE TABLE IF NOT EXISTS players (
  uuid CHAR(36) NOT NULL PRIMARY KEY,
  username VARCHAR(36),
  last_server VARCHAR(64),
  last_seen DATETIME
);

CREATE TABLE IF NOT EXISTS player_settings (
  uuid CHAR(36) NOT NULL PRIMARY KEY,
  notifications BOOLEAN NOT NULL DEFAULT 1,
  request_expiry_minutes INT NOT NULL DEFAULT 15
);

CREATE TABLE IF NOT EXISTS friendships (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_a CHAR(36) NOT NULL,
  player_b CHAR(36) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY unique_friendship (player_a, player_b)
);

CREATE TABLE IF NOT EXISTS friend_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sender CHAR(36) NOT NULL,
  receiver CHAR(36) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME,
  UNIQUE KEY unique_request (sender, receiver)
);

-- Indexes for lookups
CREATE INDEX IF NOT EXISTS idx_requests_receiver ON friend_requests(receiver);
CREATE INDEX IF NOT EXISTS idx_friendships_player_a ON friendships(player_a);
CREATE INDEX IF NOT EXISTS idx_friendships_player_b ON friendships(player_b);
