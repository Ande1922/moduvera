package io.github.ande1922.moduvera.data.autoconfigure;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import io.github.ande1922.moduvera.data.TransactionBoundary;
import io.github.ande1922.moduvera.data.mybatis.ExecutionContextTenantLineHandler;
import io.github.ande1922.moduvera.data.spring.SpringTransactionBoundary;
import java.util.ArrayList;
import java.util.Properties;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
public class ModuveraDataMybatisPlusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DatabaseIdProvider.class)
    DatabaseIdProvider moduveraDatabaseIdProvider() {
        var provider = new VendorDatabaseIdProvider();
        var vendors = new Properties();
        vendors.setProperty("PostgreSQL", "postgresql");
        vendors.setProperty("MySQL", "mysql");
        provider.setProperties(vendors);
        return provider;
    }

    @Bean
    @ConditionalOnMissingBean
    ExecutionContextTenantLineHandler moduveraTenantLineHandler() {
        return new ExecutionContextTenantLineHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    MybatisPlusInterceptor moduveraMybatisPlusInterceptor(
            ExecutionContextTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        return interceptor;
    }

    @Bean
    SmartInitializingSingleton moduveraTenantLineInstaller(
            MybatisPlusInterceptor interceptor,
            ExecutionContextTenantLineHandler tenantLineHandler) {
        return () -> {
            boolean tenantLineInstalled = interceptor.getInterceptors().stream()
                    .anyMatch(TenantLineInnerInterceptor.class::isInstance);
            if (!tenantLineInstalled) {
                ArrayList<com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor> interceptors =
                        new ArrayList<>(interceptor.getInterceptors());
                interceptors.add(0, new TenantLineInnerInterceptor(tenantLineHandler));
                interceptor.setInterceptors(interceptors);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionBoundary moduveraTransactionBoundary(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return new SpringTransactionBoundary(template);
    }
}
