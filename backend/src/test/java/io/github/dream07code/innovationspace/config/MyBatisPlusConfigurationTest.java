package io.github.dream07code.innovationspace.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MyBatisPlusConfigurationTest {
    @Test
    void paginationIsWiredForMysqlWithABoundedPageSize() {
        var interceptors = new MyBatisPlusConfiguration().mybatisPlusInterceptor().getInterceptors();
        assertThat(interceptors).hasSize(1);
        var pagination = (PaginationInnerInterceptor) interceptors.getFirst();
        assertEquals(DbType.MYSQL, pagination.getDbType());
        assertEquals(200L, pagination.getMaxLimit());
    }
}
