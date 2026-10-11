CREATE TABLE bootstrap_lock (
    lock_name VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (lock_name)
);

INSERT INTO bootstrap_lock (lock_name, created_at) VALUES ('ADMIN_BOOTSTRAP', UTC_TIMESTAMP(6));
