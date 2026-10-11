package io.github.dream07code.innovationspace.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.dream07code.testsupport.MysqlTestSupport;
import io.github.dream07code.testsupport.PersistenceProbeConfiguration;
import io.github.dream07code.testsupport.PersistenceProbeConfiguration.ProbeEntity;
import io.github.dream07code.testsupport.PersistenceProbeConfiguration.ProbeMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = {"DB_HOST=never-contact.invalid", "DB_PORT=1", "DB_NAME=do_not_use", "DB_USERNAME=do_not_use", "DB_PASSWORD=do_not_use"})
@Import({PersistenceProbeConfiguration.class, io.github.dream07code.testsupport.OwnedDataSourceConfiguration.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MysqlPersistenceIT {
    @Container
    static final MySQLContainer MYSQL = MysqlTestSupport.newMysqlContainer();

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        MysqlTestSupport.registerDataSource(registry, MYSQL);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProbeMapper mapper;

    @BeforeEach
    void prepareOwnedProbeTable() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertEquals(MYSQL.getJdbcUrl(), connection.getMetaData().getURL(), "refuse SQL outside owned container");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS bootstrap_probe (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
        jdbc.update("DELETE FROM bootstrap_probe");
    }

    @Test
    void usesPinnedMysqlDespitePoisonedDevelopmentProperties() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.46");
        assertEquals("bootstrap_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
    }

    @Test
    void mapperInsertsAndFindsAProbe() {
        var probe = new ProbeEntity();
        probe.setId(1L);
        probe.setName("probe-1");
        assertEquals(1, mapper.insert(probe));
        assertEquals("probe-1", mapper.selectById(1L).getName());
        assertNull(mapper.selectById(999L));
    }

    @Test
    void paginationCapsRequestedSizeAndPreservesTotalAndSecondPage() {
        var rows = new ArrayList<Object[]>();
        for (long id = 1; id <= 205; id++) rows.add(new Object[]{id, "probe-" + id});
        jdbc.batchUpdate("INSERT INTO bootstrap_probe (id, name) VALUES (?, ?)", rows);
        var query = new QueryWrapper<ProbeEntity>().orderByAsc("id");
        var first = mapper.selectPage(new Page<>(1, 500), query);
        assertEquals(205L, first.getTotal());
        assertEquals(200, first.getRecords().size());
        assertEquals(1L, first.getRecords().getFirst().getId());
        var second = mapper.selectPage(new Page<>(2, 500), query);
        assertEquals(205L, second.getTotal());
        assertEquals(5, second.getRecords().size());
        assertEquals(201L, second.getRecords().getFirst().getId());
    }

    @Test
    void perRequestLimitCannotRaiseGlobalCapAndSmallerLimitIsAppliedBeforeCount() {
        var rows = new ArrayList<Object[]>();
        for (long id = 1; id <= 205; id++) rows.add(new Object[]{id, "probe-" + id});
        jdbc.batchUpdate("INSERT INTO bootstrap_probe (id, name) VALUES (?, ?)", rows);
        var query = new QueryWrapper<ProbeEntity>().orderByAsc("id");
        var large = new Page<ProbeEntity>(1, 500);
        large.setMaxLimit(1000L);
        assertEquals(200, mapper.selectPage(large, query).getRecords().size());
        var small = new Page<ProbeEntity>(3, 500);
        small.setMaxLimit(100L);
        assertEquals(5, mapper.selectPage(small, query).getRecords().size());
        var unlimited = new Page<ProbeEntity>(1, -1);
        assertEquals(200, mapper.selectPage(unlimited, query).getRecords().size());
    }
}
