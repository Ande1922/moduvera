package com.gaopc.benchmark.store.candidate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.gaopc.benchmark.store.shared.StorePersistenceDependencies;
import com.gaopc.benchmark.store.shared.StorePersistenceProvider;
import com.gaopc.benchmark.store.shared.StoreRepository;
import java.util.Objects;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** MyBatis-Plus backed Store persistence entry point. */
public final class CandidateStorePersistenceProvider implements StorePersistenceProvider {
  public CandidateStorePersistenceProvider() {}

  @Override
  public StoreRepository create(StorePersistenceDependencies dependencies) {
    Objects.requireNonNull(dependencies, "dependencies");

    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setAutoMappingBehavior(AutoMappingBehavior.NONE);
    configuration.setEnvironment(
        new Environment(
            "candidate-store",
            new JdbcTransactionFactory(),
            dependencies.dataSource()));
    configuration.addMapper(StoreRowMapper.class);

    SqlSessionFactory sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new MyBatisStoreRepository(
        sessions, dependencies.contextAccessor(), dependencies.clock(), new StoreObjectMapper());
  }
}
