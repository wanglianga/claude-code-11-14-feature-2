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
        jdbc.execute("TRUNCATE flight_change_log, daily_report_item, report_correction, report_diff, "
                + "schedule_version, alert, daily_report, flight_instance RESTART IDENTITY CASCADE");
    }
}
