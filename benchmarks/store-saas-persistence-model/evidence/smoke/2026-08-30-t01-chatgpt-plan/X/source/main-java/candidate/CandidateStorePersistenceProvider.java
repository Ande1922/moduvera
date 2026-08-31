package com.gaopc.benchmark.store.candidate;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.gaopc.benchmark.store.shared.StoreError;
import com.gaopc.benchmark.store.shared.StorePersistenceDependencies;
import com.gaopc.benchmark.store.shared.StorePersistenceProvider;
import com.gaopc.benchmark.store.shared.StoreRepository;
import java.io.IOException;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

public final class CandidateStorePersistenceProvider implements StorePersistenceProvider {
  private static final String MAPPER_RESOURCE =
      "com/gaopc/benchmark/store/candidate/CandidateStoreMapper.xml";

  public CandidateStorePersistenceProvider() {}

  @Override
  public StoreRepository create(StorePersistenceDependencies dependencies) {
    if (dependencies == null) {
      throw new StoreError("store.persistence-failure");
    }
    try {
      return new CandidateStoreRepository(sqlSessionFactory(dependencies), dependencies);
    } catch (IOException | RuntimeException failure) {
      throw new StoreError("store.persistence-failure");
    }
  }

  private static SqlSessionFactory sqlSessionFactory(StorePersistenceDependencies dependencies)
      throws IOException {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setEnvironment(
        new Environment(
            "candidate-store", new JdbcTransactionFactory(), dependencies.dataSource()));

    try (InputStream mapperXml = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
      XMLMapperBuilder mapperBuilder =
          new XMLMapperBuilder(
              mapperXml, configuration, MAPPER_RESOURCE, configuration.getSqlFragments());
      mapperBuilder.parse();
    }
    return new MybatisSqlSessionFactoryBuilder().build(configuration);
  }
}
