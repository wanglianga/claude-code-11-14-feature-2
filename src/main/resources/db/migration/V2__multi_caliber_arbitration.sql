-- 多口径并行计算与发布仲裁子系统 schema
-- 四块：口径注册库 / 并行物化结果（每口径一份，口径定义与结果双版本绑定）/ 发布仲裁与切换评审 / 下游绑定台账。
-- 取舍：不做"查询时按口径现算"，每套口径各存一份物化结果——口径改动只生成新版本，
-- 已锁定月报按当时绑定版本复现，历史不追溯改写。

-- ============ 口径注册库：每套口径的时间戳来源、分母规则与生效版本（改口径 → 新版本，旧版保留） ============
CREATE TABLE caliber_definition (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(32)  NOT NULL,          -- AIRPORT 机场放行 / AIRLINE 航司起降 / GROUP 集团报送 / 自定义
    name             VARCHAR(128) NOT NULL,
    version          INTEGER      NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- DRAFT / ACTIVE / RETIRED
    timestamp_source VARCHAR(16)  NOT NULL,          -- PUSHBACK 推出 / DEPARTURE 起飞 / ARRIVAL 到达
    denominator_rule VARCHAR(32)  NOT NULL,          -- FLOWN_ONLY / FLOWN_PLUS_MISSING / ALL_REGISTERED
    tolerance_minutes INTEGER,                        -- 该口径自有容差；null 时取 app.release-tolerance-minutes
    description      VARCHAR(2048) NOT NULL,
    change_summary   VARCHAR(1024),                  -- 本版相对上版改了什么（空串=首版）
    effective_from   DATE         NOT NULL,          -- 该版本口径的生效日期（发布日）
    superseded_at    TIMESTAMPTZ,
    created_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (code, version)
);

-- ============ 口径物化运行（分区表）：同一批变更事件，每个口径各跑一份带版本的结果 ============
CREATE SEQUENCE caliber_run_id_seq;

CREATE TABLE caliber_run (
    id               BIGINT       NOT NULL DEFAULT nextval('caliber_run_id_seq'),
    service_date     DATE         NOT NULL,
    caliber_code     VARCHAR(32)  NOT NULL,
    caliber_version  INTEGER      NOT NULL,          -- 结果绑定的口径定义版本（双版本绑定）
    run_no           INTEGER      NOT NULL,          -- 同口径同日期内递增的结果版本
    status           VARCHAR(16)  NOT NULL DEFAULT 'COMPUTED', -- COMPUTED / PUBLISHED / SUPERSEDED / BLOCKED
    -- 口径定义快照（定义再改也不影响这份结果的复现）
    timestamp_source VARCHAR(16)  NOT NULL,
    denominator_rule VARCHAR(32)  NOT NULL,
    tolerance_minutes INTEGER     NOT NULL,
    backfill_scope   VARCHAR(16)  NOT NULL DEFAULT 'ORIGINAL', -- ORIGINAL 原始区间 / BACKFILL 补齐区间
    total_instances  INTEGER      NOT NULL DEFAULT 0,
    counted          INTEGER      NOT NULL DEFAULT 0,
    normal           INTEGER      NOT NULL DEFAULT 0,
    rate             NUMERIC(6,2),
    reason           VARCHAR(512),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    PRIMARY KEY (id, service_date),
    UNIQUE (service_date, caliber_code, run_no)
) PARTITION BY RANGE (service_date);

-- ============ 口径结果明细（分区表）：每口径每航班一行，物化存储，不随口径改动而变 ============
CREATE TABLE caliber_result_item (
    run_id               BIGINT      NOT NULL,
    service_date         DATE        NOT NULL,
    flight_no            VARCHAR(10) NOT NULL,
    leg_no               INTEGER     NOT NULL,
    final_status         VARCHAR(16) NOT NULL,
    counted              BOOLEAN     NOT NULL,
    normal               BOOLEAN,
    delay_minutes        INTEGER,
    reference_time       TIMESTAMPTZ,                    -- 该口径时间戳来源对应的事实时间（推出/起飞/到达）
    schedule_version_id  BIGINT,                         -- 判定绑定的计划时刻版本
    last_change_version  INTEGER     NOT NULL,           -- 截至本次计算该实例的最大变更版本
    backfill_scope       VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL', -- 该航班是否受补录事件影响
    PRIMARY KEY (run_id, service_date, flight_no, leg_no),
    FOREIGN KEY (run_id, service_date) REFERENCES caliber_run (id, service_date)
) PARTITION BY RANGE (service_date);

CREATE INDEX idx_caliber_item_date ON caliber_result_item (service_date);
CREATE INDEX idx_caliber_item_caliber ON caliber_result_item (service_date, flight_no, leg_no);

-- ============ 补偿留痕（分区表）：超窗补录/窗口内更新必须向所有并行口径逐口径补偿，禁止只补主口径 ============
CREATE SEQUENCE caliber_compensation_id_seq;

CREATE TABLE caliber_compensation (
    id                      BIGINT       NOT NULL DEFAULT nextval('caliber_compensation_id_seq'),
    service_date            DATE         NOT NULL,
    caliber_code            VARCHAR(32)  NOT NULL,
    caliber_version         INTEGER      NOT NULL,
    kind                    VARCHAR(24)  NOT NULL,       -- REALTIME_UPDATE / LATE_BACKFILL
    flight_no               VARCHAR(10)  NOT NULL,
    leg_no                  INTEGER      NOT NULL,
    from_run_id             BIGINT,                      -- 补偿前该口径最近一次结果（无则 null）
    to_run_id               BIGINT,                      -- 补偿后新产出的结果（首期尚未计算时 null）
    trigger_change_versions VARCHAR(256) NOT NULL,       -- 触发补偿的变更版本号列表，如 "12,13"
    source                  VARCHAR(16)  NOT NULL,
    late                    BOOLEAN      NOT NULL,
    note                    VARCHAR(512),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, service_date)
) PARTITION BY RANGE (service_date);

CREATE INDEX idx_caliber_comp_date ON caliber_compensation (service_date);
CREATE INDEX idx_caliber_comp_flight ON caliber_compensation (service_date, flight_no, leg_no);

-- ============ 发布前逐航班比对差额（分区表）：差额必须归因到具体状态变更，不可解释则拦截发布 ============
CREATE TABLE caliber_release_diff (
    service_date       DATE         NOT NULL,
    caliber_code       VARCHAR(32)  NOT NULL,
    publish_run_id     BIGINT       NOT NULL,
    base_run_id        BIGINT       NOT NULL,
    flight_no          VARCHAR(10)  NOT NULL,
    leg_no             INTEGER      NOT NULL,
    field              VARCHAR(32)  NOT NULL,           -- counted / normal / delay_minutes
    old_value          VARCHAR(64),
    new_value          VARCHAR(64),
    attribution        VARCHAR(16)  NOT NULL,           -- EXPLAINED 可解释 / UNEXPLAINED 不可解释
    caused_by_changes  VARCHAR(256),                    -- 归因到的具体变更版本号列表
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (service_date, publish_run_id, flight_no, leg_no, field)
) PARTITION BY RANGE (service_date);

CREATE INDEX idx_caliber_rdiff_date ON caliber_release_diff (service_date);

-- ============ 切换评审：主口径切换必须走评审；撞日报锁定则锁定优先、顺延并留冲突记录 ============
CREATE TABLE caliber_switch_review (
    id                 BIGSERIAL PRIMARY KEY,
    service_date       DATE         NOT NULL,           -- 目标发布周期（业务日期）
    from_caliber_code  VARCHAR(32)  NOT NULL,
    to_caliber_code    VARCHAR(32)  NOT NULL,
    to_caliber_version INTEGER      NOT NULL,
    status             VARCHAR(24)  NOT NULL,          -- PROPOSED / APPROVED / REJECTED / CONFLICT_DEFERRED / APPLIED
    reason             VARCHAR(1024) NOT NULL,
    decision_comment   VARCHAR(1024),
    requested_by       VARCHAR(64)  NOT NULL DEFAULT 'manager',
    decided_by         VARCHAR(64),
    effective_date     DATE,                            -- 实际生效日期（顺延后 != service_date）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at         TIMESTAMPTZ,
    applied_at         TIMESTAMPTZ
);

CREATE INDEX idx_switch_review_date ON caliber_switch_review (service_date);

-- ============ 切换冲突记录：评审撞上日报锁定，锁定优先，切换顺延到下一发布周期 ============
CREATE TABLE caliber_switch_conflict (
    id               BIGSERIAL PRIMARY KEY,
    review_id        BIGINT       NOT NULL REFERENCES caliber_switch_review (id),
    service_date     DATE         NOT NULL,
    locked_report_id BIGINT       NOT NULL,             -- 撞上的锁定日报
    locked_at        TIMESTAMPTZ  NOT NULL,
    deferred_to_date DATE         NOT NULL,             -- 顺延到的下一发布周期
    reason           VARCHAR(1024) NOT NULL,
    resolved         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 下游绑定台账：大屏 / 月报 / 对外报送各引用哪一版（口径版本 + 结果运行双绑定） ============
CREATE TABLE downstream_binding (
    id              BIGSERIAL PRIMARY KEY,
    target          VARCHAR(32)  NOT NULL,              -- SCREEN 大屏 / MONTHLY_REPORT 月报 / EXTERNAL_REPORT 对外报送
    period          VARCHAR(16),                        -- 报送周期，如 2026-11（大屏可为 null）
    caliber_code    VARCHAR(32)  NOT NULL,
    caliber_version INTEGER      NOT NULL,
    run_id          BIGINT,                             -- 绑定的具体物化结果（月报/报送必填，大屏可只绑口径）
    service_date    DATE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    bound_by        VARCHAR(64)  NOT NULL DEFAULT 'manager',
    note            VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_binding_target ON downstream_binding (target, active);
CREATE INDEX idx_binding_period ON downstream_binding (target, period);

-- ============ 历史补齐区间标注：补齐区间与原始区间分列，哪版口径、影响哪些航班可逐条查 ============
CREATE TABLE caliber_backfill_segment (
    id              BIGSERIAL PRIMARY KEY,
    caliber_code    VARCHAR(32)  NOT NULL,
    caliber_version INTEGER      NOT NULL,
    range_from      DATE         NOT NULL,
    range_to        DATE         NOT NULL,
    segment_type    VARCHAR(16)  NOT NULL,              -- ORIGINAL 原始区间 / BACKFILL 补齐区间
    reason          VARCHAR(1024) NOT NULL,
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'manager',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_backfill_segment_caliber ON caliber_backfill_segment (caliber_code, range_from);

-- ============ 按月分区：2026-01 ~ 2027-12 + DEFAULT ============
DO $$
DECLARE
    m DATE;
BEGIN
    FOR m IN SELECT generate_series(DATE '2026-01-01', DATE '2027-12-01', INTERVAL '1 month')
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS caliber_run_%s PARTITION OF caliber_run FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS caliber_result_item_%s PARTITION OF caliber_result_item FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS caliber_compensation_%s PARTITION OF caliber_compensation FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
        EXECUTE format('CREATE TABLE IF NOT EXISTS caliber_release_diff_%s PARTITION OF caliber_release_diff FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYYMM'), m, m + INTERVAL '1 month');
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS caliber_run_default PARTITION OF caliber_run DEFAULT;
CREATE TABLE IF NOT EXISTS caliber_result_item_default PARTITION OF caliber_result_item DEFAULT;
CREATE TABLE IF NOT EXISTS caliber_compensation_default PARTITION OF caliber_compensation DEFAULT;
CREATE TABLE IF NOT EXISTS caliber_release_diff_default PARTITION OF caliber_release_diff DEFAULT;

-- ============ 种子：三套并行口径（机场看放行 / 航司看起飞或到达 / 集团第三套分母规则） ============
INSERT INTO caliber_definition
    (id, code, name, version, status, timestamp_source, denominator_rule, tolerance_minutes,
     description, change_summary, effective_from, created_by)
VALUES
    (1, 'AIRPORT', '机场放行正常性口径', 1, 'ACTIVE', 'PUSHBACK', 'FLOWN_PLUS_MISSING', 20,
     '机场视角：以实际推出时刻对比计划起飞判定放行是否准点；分母=已推出/起飞班次+应飞未飞（记不正常），取消不计入。推出与起飞之间的等待不计入机场延误。',
     '首版发布', DATE '2000-01-01', 'system'),
    (2, 'AIRLINE', '航司起降正常性口径', 1, 'ACTIVE', 'DEPARTURE', 'FLOWN_ONLY', 15,
     '航司视角：以实际起飞时刻对比计划起飞判定是否准点；分母仅含已起飞班次，取消与未起飞班次不计入。推出后的跑道等待计入航司延误，故同航班机场判正常、航司判延误为常态。',
     '首版发布', DATE '2000-01-01', 'system'),
    (3, 'GROUP', '集团报送正常性口径', 1, 'ACTIVE', 'ARRIVAL', 'ALL_REGISTERED', 15,
     '集团报送视角：以实际到达时刻对比计划到达判定；分母=当日全部登记实例（含取消、应飞未飞，均记不正常），用于对外报送的第三套分母规则。',
     '首版发布', DATE '2000-01-01', 'system');

-- 初始主口径 = 机场放行口径（首条 APPLIED 评审记录，生效日 2000-01-01，主口径解析以此为基线）
INSERT INTO caliber_switch_review
    (service_date, from_caliber_code, to_caliber_code, to_caliber_version, status, reason,
     decision_comment, requested_by, decided_by, effective_date, decided_at, applied_at)
VALUES
    (DATE '2000-01-01', 'AIRPORT', 'AIRPORT', 1, 'APPLIED',
     '系统初始化：主口径默认为机场放行正常性口径', '初始化基线', 'system', 'system',
     DATE '2000-01-01', now(), now());

-- 显式插入种子行后推进序列，避免后续 IDENTITY 自增与种子 id 冲突
SELECT setval(pg_get_serial_sequence('caliber_definition', 'id'),
              (SELECT max(id) FROM caliber_definition));
SELECT setval(pg_get_serial_sequence('caliber_switch_review', 'id'),
              (SELECT max(id) FROM caliber_switch_review));
