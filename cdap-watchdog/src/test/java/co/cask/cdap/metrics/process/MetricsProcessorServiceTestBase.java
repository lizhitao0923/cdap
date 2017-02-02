/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.metrics.process;

import co.cask.cdap.api.messaging.TopicNotFoundException;
import co.cask.cdap.api.metrics.MetricStore;
import co.cask.cdap.api.metrics.MetricType;
import co.cask.cdap.api.metrics.MetricValues;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.IOModule;
import co.cask.cdap.common.guice.NonCustomLocationUnitTestModule;
import co.cask.cdap.common.io.BinaryEncoder;
import co.cask.cdap.common.io.Encoder;
import co.cask.cdap.common.kerberos.DefaultOwnerAdmin;
import co.cask.cdap.common.kerberos.OwnerAdmin;
import co.cask.cdap.common.namespace.guice.NamespaceClientRuntimeModule;
import co.cask.cdap.common.security.UGIProvider;
import co.cask.cdap.common.security.UnsupportedUGIProvider;
import co.cask.cdap.data.runtime.DataFabricModules;
import co.cask.cdap.data.runtime.DataSetServiceModules;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.explore.guice.ExploreClientModule;
import co.cask.cdap.messaging.client.StoreRequestBuilder;
import co.cask.cdap.metrics.MetricsTestBase;
import co.cask.cdap.metrics.store.DefaultMetricDatasetFactory;
import co.cask.cdap.metrics.store.DefaultMetricStore;
import co.cask.cdap.metrics.store.MetricDatasetFactory;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.authorization.AuthorizationTestModule;
import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.util.Modules;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

abstract class MetricsProcessorServiceTestBase extends MetricsTestBase {

  protected static final String EXPECTED_METRIC_PREFIX = "system.";
  protected static final String COUNTER_METRIC_NAME = "counter_metric";
  protected static final int PARTITION_SIZE = 2;
  protected static final long START_TIME = 1;
  protected static final String EXPECTED_COUNTER_METRIC_NAME = EXPECTED_METRIC_PREFIX + COUNTER_METRIC_NAME;
  protected static final Map<String, String> METRICS_CONTEXT = ImmutableMap.<String, String>builder()
    .put(Constants.Metrics.Tag.NAMESPACE, "NS_1")
    .put(Constants.Metrics.Tag.APP, "APP_1")
    .put(Constants.Metrics.Tag.FLOW, "FLOW_1")
    .put(Constants.Metrics.Tag.RUN_ID, "RUN_1")
    .put(Constants.Metrics.Tag.FLOWLET, "FLOWLET_1").build();

  protected final ByteArrayOutputStream encoderOutputStream = new ByteArrayOutputStream(1024);
  // Map containing expected metrics' names and values
  protected final Map<String, Long> expected = new HashMap<>();
  private final Encoder encoder = new BinaryEncoder(encoderOutputStream);

  protected void publishMessagingMetrics(int i, Map<String, String> metricsContext, Map<String, Long> expected,
                                       MetricType metricType)
    throws IOException, TopicNotFoundException {
    getMetricValuesAddToExpected(i, metricsContext, expected, metricType);
    messagingService.publish(StoreRequestBuilder.of(NamespaceId.SYSTEM.topic(TOPIC_PREFIX + (i % PARTITION_SIZE)))
                               .addPayloads(encoderOutputStream.toByteArray()).build());
    encoderOutputStream.reset();
  }

  /**
   * Returns expected {@link MetricValues} of the given {@link MetricType}. Add the {@link MetricValues} to the
   * {@code expected} metrics map. If the {@link MetricValues} is of type {@code MetricType.COUNTER} and is present
   * in {@code expected}, increment the existing value of it.
   */
  protected MetricValues getMetricValuesAddToExpected(int i, Map<String, String> metricsContext,
                                                    Map<String, Long> expected, MetricType metricType)
    throws TopicNotFoundException, IOException {
    MetricValues metric;
    if (MetricType.GAUGE.equals(metricType)) {
      String metricName = "gauge_metric" + i;
      metric =
        new MetricValues(metricsContext, metricName, START_TIME + i, i, metricType);
      expected.put(EXPECTED_METRIC_PREFIX + metricName, (long) i);
    } else {
      metric =
        new MetricValues(metricsContext, COUNTER_METRIC_NAME, START_TIME + i, 1, metricType);
      Long currentValue = expected.get(EXPECTED_COUNTER_METRIC_NAME);
      if (currentValue == null) {
        expected.put(EXPECTED_COUNTER_METRIC_NAME, 1L);
      } else {
        expected.put(EXPECTED_COUNTER_METRIC_NAME, currentValue + 1);
      }
    }

    recordWriter.encode(metric, encoder);
    return metric;
  }

  @Override
  protected List<Module> getAdditionalModules() {
    List<Module> list = new ArrayList<>();
    list.add(new DataSetsModules().getStandaloneModules());
    list.add(new IOModule());
    list.add(Modules.override(
      new NonCustomLocationUnitTestModule().getModule(),
      new DataFabricModules().getInMemoryModules(),
      new DataSetServiceModules().getInMemoryModules(),
      new ExploreClientModule(),
      new NamespaceClientRuntimeModule().getInMemoryModules(),
      new AuthorizationTestModule(),
      new AuthorizationEnforcementModule().getInMemoryModules(),
      new AuthenticationContextModules().getMasterModule()
    ).with(new AbstractModule() {
      @Override
      protected void configure() {
        bind(UGIProvider.class).to(UnsupportedUGIProvider.class);
        bind(OwnerAdmin.class).to(DefaultOwnerAdmin.class);
        bind(MetricDatasetFactory.class).to(DefaultMetricDatasetFactory.class).in(Scopes.SINGLETON);
        bind(MetricStore.class).to(DefaultMetricStore.class).in(Scopes.SINGLETON);
      }
    }));
    return list;
  }
}