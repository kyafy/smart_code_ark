ALTER TABLE paper_sources ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'semantic_scholar' AFTER relevance_score;
CREATE INDEX idx_paper_sources_source ON paper_sources(source);
