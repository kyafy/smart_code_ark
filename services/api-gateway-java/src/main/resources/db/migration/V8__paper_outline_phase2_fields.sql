ALTER TABLE paper_outline_versions
    ADD COLUMN manuscript_json JSON NULL AFTER outline_json;

ALTER TABLE paper_outline_versions
    ADD COLUMN quality_score DECIMAL(5,2) NULL AFTER quality_report_json;

ALTER TABLE paper_outline_versions
    ADD COLUMN rewrite_round INT NOT NULL DEFAULT 0 AFTER quality_score;
