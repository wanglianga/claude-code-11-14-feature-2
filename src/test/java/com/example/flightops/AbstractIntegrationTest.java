package com.example.flightops;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成测试基类：使用容器内 PostgreSQL，每个用例前清空全部业务表。 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // 多口径子系统的种子口径定义（id 1-3）与基线主口径评审（id 1）由 V2 迁移写入，测试间保留；
        // 仅清理运行期数据与测试自建的口径/评审，保证并行计算始终有三套生效口径可用。
        jdbc.execute("TRUNCATE flight_change_log, daily_report_item, report_correction, report_diff, "
                + "schedule_version, alert, daily_report, flight_instance, "
                + "caliber_release_diff, caliber_result_item, caliber_compensation, caliber_switch_conflict, "
                + "downstream_binding, caliber_backfill_segment, caliber_run RESTART IDENTITY CASCADE");
        jdbc.execute("DELETE FROM caliber_switch_review WHERE id > 1");
        jdbc.execute("DELETE FROM caliber_definition WHERE id > 3");
        // 复位种子口径状态（部分用例会把种子口径改版退役，用例间需还原为 ACTIVE）
        jdbc.execute("UPDATE caliber_definition SET status = 'ACTIVE', superseded_at = NULL WHERE id <= 3");
    }
}
