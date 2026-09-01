package io.github.ande1922.moduvera.benchmark.store.candidate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceDependencies;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceProvider;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreRepository;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Creates the MyBatis-Plus backed Store repository required by the frozen seed. */
public final class CandidateStorePersistenceProvider implements StorePersistenceProvider {
  public CandidateStorePersistenceProvider() {}

  @Override
  public StoreRepository create(StorePersistenceDependencies dependencies) {
    Environment environment =
        new Environment(
            "store-benchmark", new JdbcTransactionFactory(), dependencies.dataSource());
    MybatisConfiguration configuration = new MybatisConfiguration(environment);
    configuration.addMapper(StoreSqlMapper.class);
    SqlSessionFactory sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    return new UnifiedStoreRepository(
        sessions, dependencies.contextAccessor(), dependencies.clock());
  }
}
