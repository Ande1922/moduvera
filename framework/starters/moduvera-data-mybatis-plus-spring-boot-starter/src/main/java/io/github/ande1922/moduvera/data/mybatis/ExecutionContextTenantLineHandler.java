package io.github.ande1922.moduvera.data.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

public final class ExecutionContextTenantLineHandler implements TenantLineHandler {

    @Override
    public Expression getTenantId() {
        return new StringValue(ExecutionContextHolder.require().requireTenantId().value());
    }
}
