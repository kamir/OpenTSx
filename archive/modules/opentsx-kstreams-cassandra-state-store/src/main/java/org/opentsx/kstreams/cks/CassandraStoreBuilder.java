package org.opentsx.kstreams.cks;

import org.apache.kafka.streams.state.StoreBuilder;

import java.util.Map;

public class CassandraStoreBuilder implements StoreBuilder<CassandraStateStore> {

  private final String hostAddr;
  private Map<String, String> config;

  public CassandraStoreBuilder() {
    this("127.0.0.1");
  }

  public CassandraStoreBuilder(String hostAddr) {
    this.hostAddr = hostAddr;
  }

  @Override
  public StoreBuilder<CassandraStateStore>  withCachingEnabled() {
    return this;
  }

  @Override
  public StoreBuilder<CassandraStateStore>  withCachingDisabled() {
    return this;
  }

  @Override
  public StoreBuilder<CassandraStateStore> withLoggingEnabled(Map<String, String> config) {
    this.config = config;
    return this;
  }

  @Override
  public StoreBuilder<CassandraStateStore>  withLoggingDisabled() {
    return this;
  }

  @Override
  public CassandraStateStore build() {
    return new CassandraStateStore(hostAddr);
  }

  @Override
  public Map<String, String> logConfig() {
    return config;
  }

  @Override
  public boolean loggingEnabled() {
    return false;
  }

  @Override
  public String name() {
    return CassandraStateStore.STORE_NAME;
  }
}
