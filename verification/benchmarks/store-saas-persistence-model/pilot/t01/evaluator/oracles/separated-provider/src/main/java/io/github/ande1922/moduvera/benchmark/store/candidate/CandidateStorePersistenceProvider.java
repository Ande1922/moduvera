package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceDependencies;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceProvider;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.io.Resources;

public final class CandidateStorePersistenceProvider implements StorePersistenceProvider {
  private static final String MAPPER_RESOURCE =
      "io/github/ande1922/moduvera/benchmark/store/candidate/StoreSqlMapper.xml";

  public CandidateStorePersistenceProvider() {}

  @Override
  public StoreRepository create(StorePersistenceDependencies dependencies) {
    StorePersistenceDependencies required =
        Objects.requireNonNull(dependencies, "dependencies");
    SqlSessionFactory sessions = createSessionFactory(required);
    return new MyBatisStoreRepository(
        sessions, required.contextAccessor(), required.clock(), new StoreObjectMapper());
  }

  private static SqlSessionFactory createSessionFactory(
      StorePersistenceDependencies dependencies) {
    Environment environment =
        new Environment(
            "store-candidate",
            new JdbcTransactionFactory(),
            dependencies.dataSource());
    Configuration configuration = new Configuration(environment);
    configuration.setAutoMappingBehavior(AutoMappingBehavior.NONE);

    try (InputStream stream = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
      XMLMapperBuilder parser =
          new XMLMapperBuilder(
              stream,
              configuration,
              MAPPER_RESOURCE,
              configuration.getSqlFragments());
      parser.parse();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load Store MyBatis mapper", exception);
    }

    return new SqlSessionFactoryBuilder().build(configuration);
  }
}
