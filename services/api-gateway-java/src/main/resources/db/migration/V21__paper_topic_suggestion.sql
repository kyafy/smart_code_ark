-- V21: Add topic suggestion fields to paper_topic_session
ALTER TABLE paper_topic_session ADD COLUMN suggested_topics_json TEXT DEFAULT NULL;
ALTER TABLE paper_topic_session ADD COLUMN suggestion_round INT DEFAULT 0;
