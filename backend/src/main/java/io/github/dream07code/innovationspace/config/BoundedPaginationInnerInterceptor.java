package io.github.dream07code.innovationspace.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.toolkit.ParameterUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.sql.SQLException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/** Applies the effective limit before the count/overflow check, not just before SQL rewriting. */
final class BoundedPaginationInnerInterceptor extends PaginationInnerInterceptor {
    private static final long GLOBAL_LIMIT = 200L;

    BoundedPaginationInnerInterceptor() {
        super(DbType.MYSQL);
        setMaxLimit(GLOBAL_LIMIT);
    }

    @Override
    public boolean willDoQuery(Executor executor, MappedStatement statement, Object parameter,
            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        ParameterUtils.findPage(parameter).ifPresent(page -> {
            Long requestedLimit = page.maxLimit();
            long limit = requestedLimit != null && requestedLimit > 0
                    ? Math.min(GLOBAL_LIMIT, requestedLimit) : GLOBAL_LIMIT;
            if (page.getSize() < 0 || page.getSize() > limit) page.setSize(limit);
        });
        return super.willDoQuery(executor, statement, parameter, rowBounds, resultHandler, boundSql);
    }
}
