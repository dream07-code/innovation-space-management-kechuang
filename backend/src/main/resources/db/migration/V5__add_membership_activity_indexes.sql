CREATE INDEX idx_report_member_activity
  ON report(member_id, report_type, status, first_submitted_at);

CREATE INDEX idx_member_auto_removal
  ON member(membership_status, membership_started_at);
