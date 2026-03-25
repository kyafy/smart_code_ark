-- V22: Add chapter-evidence mapping to paper_outline_versions for traceability
ALTER TABLE paper_outline_versions ADD COLUMN chapter_evidence_map_json TEXT DEFAULT NULL;
