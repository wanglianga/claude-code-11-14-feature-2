-- 航班正常性统计服务 schema
-- 实例明细、变更日志、日报明细、更正记录、差异记录均使用 PostgreSQL 分区表（按业务日期 RANGE 分区）。

-- ============ 航班实例（分区表）：主键 = 航班号 + 执行日期 + 航段号 ============
CREATE TABLE flight_instance (
    flight_no            VARCHAR(10)  NOT NULL,
    service_date         DATE         NOT NULL,
    leg_no               INTEGER      NOT NULL DEFAULT 1,
    origin               VARCHAR(8)   NOT NULL,
    destination          VARCHAR(8)   NOT NULL,
    instance_kind        VARCHAR(16)  NOT NULL DEFAULT 'REGULAR', -- REGULAR 正班 / EXTRA 加班 / FERRY 补班
    current_status       VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    current_version      INTEGER      NOT NULL DEFAULT 0,          -- 实例内变更序号（变更日志版本）
    -- 放行判定：绑定判定时生效的计划时刻版本，事后改计划不回写
    release_version_id   BIGINT,
    release_normal       BOOLEAN,
    release_delay_minutes INTEGER,
    release_judged_at    TIMESTAMPTZ,
    first_departure_time TIMESTAMPTZ,                               -- 首次实际起飞（判定基准）
    actual_arrival_time  TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,            -- 乐观锁
    PRIMARY KEY (flight_no, service_date, leg_no)
) PARTITION BY RANGE (service_date);

-- ============ 计划时刻版本：航司改计划产生新版本，旧版本保留 ============
CREATE TABLE schedule_version (
    id              BIGSERIAL PRIMARY KEY,
    flight_no       VARCHAR(10) NOT NULL,
    service_date    DATE        NOT NULL,
    leg_no          INTEGER     NOT NULL,
    version         INTEGER     NOT NULL,
    sched_departure TIMESTAMPTZ NOT NULL,
    sched_arrival   TIMESTAMPTZ NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL,  -- 该版本生效时刻（判定按 event_time 匹配当时生效版本）
    source          VARCHAR(32) NOT NULL DEFAULT 'SCHEDULE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (flight_no, service_date, leg_no, version),
    FOREIGN KEY (flight_no, service_date, leg_no)
        REFERENCES flight_instance (flight_no, service_date, leg_no)
);

-- ============ 变更日志（分区表）：校验通过的变更不覆盖旧值，带正负号与版本写入 ============
CREATE TABLE flight_change_log (
    flight_no       VARCHAR(10)  NOT NULL,
    service_date    DATE         NOT NULL,
    leg_no          INTEGER      NOT NULL,
    change_version  INTEGER      NOT NULL,   -- 实例内递增版本，与实例 current_version 对应
    event_type      VARCHAR(24)  NOT NULL,   -- STATUS_CHANGE / SCHEDULE_CHANGE / TIME_CORRECTION / REGISTER
    from_status     VARCHAR(16),
    to_status       VARCHAR(16),
    sign            SMALLINT     NOT NULL,   -- +1 计入 / -1 冲抵，下游按实例主键逐条冲抵
    event_time      TIMESTAMPTZ,             -- 业务发生时间（状态事实对应的时间）
    ingest_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source          VARCHAR(16)  NOT NULL DEFAULT 'REALTIME', -- REALTIME / BACKFILL / SCHEDULE
    late            BOOLEAN      NOT NULL DEFAULT FALSE,
    note            VARCHAR(512),
    PRIMARY KEY (flight_no, service_date, leg_no, change_version)
) PARTITION BY RANGE (service_date);

CREATE INDEX idx_change_log_date ON flight_change_log (service_date);

-- ============ 告警：非法状态流转（如未推出直接落地）拒绝并记录 ============
CREATE TABLE alert (
    id           BIGSERIAL PRIMARY KEY,
    flight_no    VARCHAR(10) NOT NULL,
    service_date DATE        NOT NULL,
    leg_no       INTEGER     NOT NULL,
    rule         VARCHAR(64) NOT NULL,
    message      VARCHAR(1024),
    acknowledged BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 日报版本（分区表）：锁定后更正另出一份 ============
CREATE SEQUENCE daily_report_id_seq;

CREATE TABLE daily_report (
    id              BIGINT       NOT NULL DEFAULT nextval('daily_report_id_seq'),
    report_date     DATE         NOT NULL,
    version         INTEGER      NOT NULL,
    kind            VARCHAR(16)  NOT NULL,           -- ORIGINAL / CORRECTION
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN', -- OPEN / LOCKED
    reason          VARCHAR(1024),                   -- 更正原因（更正版必填）
    based_on_id     BIGINT,                          -- 更正基于哪一版
    total_instances INTEGER      NOT NULL DEFAULT 0,
    counted         INTEGER      NOT NULL DEFAULT 0, -- 日报口径分母
    normal          INTEGER      NOT NULL DEFAULT 0,
    rate            NUMERIC(6,2),                    -- 日报口径正常率 %
    realtime_counted INTEGER     NOT NULL DEFAULT 0, -- 实时口径分母（已判定班次）
    realtime_normal  INTEGER     NOT NULL DEFAULT 0,
    realtime_rate    NUMERIC(6,2),                   -- 实时口径正常率 %
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_at       TIMESTAMPTZ,
    PRIMARY KEY (id, report_date),
    UNIQUE (report_date, version)
) PARTITION BY RANGE (report_date);

-- ============ 日报明细（分区表）：日报口径与实时口径分列 ============
CREATE TABLE daily_report_item (
    report_id           BIGINT       NOT NULL,
    flight_no           VARCHAR(10)  NOT NULL,
    service_date        DATE         NOT NULL,
    leg_no              INTEGER      NOT NULL,
    final_status        VARCHAR(16)  NOT NULL,
    counted             BOOLEAN      NOT NULL,           -- 日报口径是否计入分母
    normal              BOOLEAN,                         -- 日报口径放行是否正常
    delay_minutes       INTEGER,                         -- 日报口径延误分钟
    schedule_version_id BIGINT,                          -- 判定绑定的计划版本
    realtime_counted    BOOLEAN      NOT NULL,           -- 实时口径当时是否计入
    realtime_normal     BOOLEAN,                         -- 实时口径结论
    realtime_delay_minutes INTEGER,                      -- 实时口径延误分钟
    last_change_version INTEGER      NOT NULL,           -- 定位：该实例截至重算的最大变更版本
    PRIMARY KEY (report_id, flight_no, service_date, leg_no),
    FOREIGN KEY (report_id, service_date) REFERENCES daily_report (id, report_date)
) PARTITION BY RANGE (service_date);

CREATE INDEX idx_report_item_date ON daily_report_item (service_date);

-- ============ 更正记录（分区表）：原值、更正值、原因同时可查 ============
CREATE TABLE report_correction (
    report_date       DATE         NOT NULL,
    correction_report_id BIGINT    NOT NULL,
    flight_no         VARCHAR(10)  NOT NULL,
    leg_no            INTEGER      NOT NULL,
    field             VARCHAR(32)  NOT NULL,   -- counted / normal / delay_minutes
    old_value         VARCHAR(64),
    new_value         VARCHAR(64),
    reason            VARCHAR(1024) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (report_date, correction_report_id, flight_no, leg_no, field),
    FOREIGN KEY (correction_report_id, report_date) REFERENCES daily_report (id, report_date)
) PARTITION BY RANGE (report_date);

-- ============ 版本间差异（分区表）：差异超阈值定位到具体那次变更 ============
CREATE TABLE report_diff (
    report_date    DATE         NOT NULL,
    from_version   INTEGER      NOT NULL,
    to_version     INTEGER      NOT NULL,
    flight_no      VARCHAR(10)  NOT NULL,
    leg_no         INTEGER      NOT NULL,
    field          VARCHAR(32)  NOT NULL,
    old_value      VARCHAR(64),
    new_value      VARCHAR(64),
    exceeds_threshold BOOLEAN   NOT NULL,
    caused_by_changes VARCHAR(256),  -- 定位到具体变更：change_version 列表（如 "5,6"）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (report_date, from_version, to_version, flight_no, leg_no, field)
) PARTITION BY RANGE (report_date);

-- ============ 按月分区：2026-01 ~ 2027-12 + DEFAULT ============
DO $$
DECLARE
    m DATE;
BEGIN
    FOR m IN SELECT generate_series(DATE '2026-01-01', DATE '2027-12-01', INTERVAL '1 month')
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS flight_instance_%s PARTITION OF flight_instance FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS flight_change_log_%s PARTITION OF flight_change_log FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS daily_report_%s PARTITION OF daily_report FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS daily_report_item_%s PARTITION OF daily_report_item FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS report_correction_%s PARTITION OF report_correction FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS report_diff_%s PARTITION OF report_diff FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS flight_instance_default PARTITION OF flight_instance DEFAULT;
CREATE TABLE IF NOT EXISTS flight_change_log_default PARTITION OF flight_change_log DEFAULT;
CREATE TABLE IF NOT EXISTS daily_report_default PARTITION OF daily_report DEFAULT;
CREATE TABLE IF NOT EXISTS daily_report_item_default PARTITION OF daily_report_item DEFAULT;
CREATE TABLE IF NOT EXISTS report_correction_default PARTITION OF report_correction DEFAULT;
CREATE TABLE IF NOT EXISTS report_diff_default PARTITION OF report_diff DEFAULT;
