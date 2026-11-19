-- Preserve the verbatim basis of role and scope changes without rewriting prior audit rows.
-- Free text remains outside the existing public audit projection and never includes actor passwords.
ALTER TABLE audit_log ADD COLUMN reason TEXT;
