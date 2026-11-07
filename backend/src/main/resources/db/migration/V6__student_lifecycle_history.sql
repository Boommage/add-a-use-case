-- Record only evidenced history. Existing assignments are known at this migration
-- boundary, not at the start of their course section or any earlier evaluated week.
CREATE TABLE student_team_membership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    team_id INT NOT NULL,
    section_id INT NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    left_at DATETIME(6) NULL,
    CONSTRAINT fk_membership_student FOREIGN KEY (student_id) REFERENCES peer_evaluation_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_team FOREIGN KEY (team_id) REFERENCES team(team_id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_section FOREIGN KEY (section_id) REFERENCES section(section_id) ON DELETE CASCADE,
    CONSTRAINT chk_membership_interval CHECK (left_at IS NULL OR left_at >= joined_at),
    INDEX idx_membership_student_open (student_id, left_at),
    INDEX idx_membership_section_dates (section_id, joined_at, left_at)
);

CREATE TABLE student_deactivation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    section_id INT NOT NULL,
    former_team_id INT NULL,
    deactivated_at DATETIME(6) NOT NULL,
    cutoff_week VARCHAR(8) NOT NULL,
    CONSTRAINT fk_deactivation_student FOREIGN KEY (student_id) REFERENCES peer_evaluation_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_deactivation_section FOREIGN KEY (section_id) REFERENCES section(section_id) ON DELETE CASCADE,
    CONSTRAINT fk_deactivation_team FOREIGN KEY (former_team_id) REFERENCES team(team_id) ON DELETE SET NULL,
    INDEX idx_deactivation_student_time (student_id, deactivated_at)
);

-- History timestamps are stored in UTC; cutoff weeks use app.timezone.
INSERT INTO student_team_membership (student_id, team_id, section_id, joined_at)
SELECT id, team_team_id, section_section_id, UTC_TIMESTAMP(6)
FROM peer_evaluation_user
WHERE dtype = 'Student' AND team_team_id IS NOT NULL;
