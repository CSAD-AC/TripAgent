ALTER TABLE memory_raw_event
    ADD COLUMN result_status VARCHAR(24) NULL AFTER event_status,
    ADD COLUMN candidate_json TEXT NULL AFTER content,
    ADD COLUMN result_json TEXT NULL AFTER candidate_json,
    ADD COLUMN error_message VARCHAR(500) NULL AFTER result_json;
