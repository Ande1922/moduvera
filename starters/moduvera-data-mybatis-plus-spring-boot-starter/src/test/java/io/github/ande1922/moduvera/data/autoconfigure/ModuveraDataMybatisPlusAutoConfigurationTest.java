package io.github.ande1922.moduvera.data.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.data.mybatis.ExecutionContextTenantLineHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ModuveraDataMybatisPlusAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModuveraDataMybatisPlusAutoConfiguration.class));

    @Test
    void suppliesTheTransactionBoundaryWhenATransactionManagerExists() {
        runner.withBean(StubTransactionManager.class).run(context -> {
            assertThat(context).hasSingleBean(TransactionBoundary.class);
            assertThat(context).hasSingleBean(ExecutionContextTenantLineHandler.class);
            assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
        });
    }

    @Test
    void failsFastWhenNoTransactionManagerExists() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void keepsTenantIsolationWhenAConsumerProvidesAnInterceptor() {
        InnerInterceptor consumerInterceptor = new InnerInterceptor() {};

        runner.withBean(StubTransactionManager.class)
                .withBean(MybatisPlusInterceptor.class, () -> {
                    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
                    interceptor.addInnerInterceptor(consumerInterceptor);
                    return interceptor;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);

                    MybatisPlusInterceptor configured = context.getBean(MybatisPlusInterceptor.class);
                    assertThat(configured.getInterceptors())
                            .hasSize(2)
                            .first()
                            .isInstanceOf(TenantLineInnerInterceptor.class);
                    assertThat(configured.getInterceptors()).contains(consumerInterceptor);
                });
    }

    private static final class StubTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
