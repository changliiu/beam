/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.io.debezium;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import io.debezium.DebeziumException;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionRowTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Lists;
import org.hamcrest.Matchers;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

@RunWith(Parameterized.class)
public class DebeziumReadSchemaTransformTest {

  @ClassRule public static TemporaryFolder tempFolder = new TemporaryFolder();

  @ClassRule
  public static final PostgreSQLContainer<?> POSTGRES_SQL_CONTAINER =
      new PostgreSQLContainer<>(
              DockerImageName.parse("quay.io/debezium/example-postgres:latest")
                  .asCompatibleSubstituteFor("postgres"))
          .withPassword("dbz")
          .withUsername("debezium")
          .withExposedPorts(5432)
          .withDatabaseName("inventory");

  @ClassRule
  public static final MySQLContainer<?> MY_SQL_CONTAINER =
      new MySQLContainer<>(
              DockerImageName.parse("debezium/example-mysql:1.4")
                  .asCompatibleSubstituteFor("mysql"))
          .withPassword("debezium")
          .withUsername("mysqluser")
          .withExposedPorts(3306)
          .waitingFor(
              new HttpWaitStrategy()
                  .forPort(3306)
                  .forStatusCodeMatching(response -> response == 200)
                  .withStartupTimeout(Duration.ofMinutes(2)));

  @Parameterized.Parameters
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {POSTGRES_SQL_CONTAINER, "debezium", "dbz", "POSTGRES", 5432},
          {MY_SQL_CONTAINER, "debezium", "dbz", "MYSQL", 3306}
        });
  }

  @Parameterized.Parameter(0)
  public Container<?> databaseContainer;

  @Parameterized.Parameter(1)
  public String userName;

  @Parameterized.Parameter(2)
  public String password;

  @Parameterized.Parameter(3)
  public String database;

  @Parameterized.Parameter(4)
  public Integer port;

  private PTransform<PCollectionRowTuple, PCollectionRowTuple> makePtransform(
      String user, String password, String database, Integer port, String host) {
    return new DebeziumReadSchemaTransformProvider(true, 10, 100L)
        .from(
            DebeziumReadSchemaTransformProvider.DebeziumReadSchemaTransformConfiguration.builder()
                .setDatabase(database)
                .setPassword(password)
                .setUsername(user)
                .setHost(host)
                // In postgres, this field is "schema.table", while in MySQL it
                // is "database.table".
                .setTable("inventory.customers")
                .setPort(port)
                .setDebeziumConnectionProperties(
                    Lists.newArrayList(
                        "database.server.id=579676",
                        "schema.history.internal=io.debezium.storage.file.history.FileSchemaHistory",
                        String.format(
                            "schema.history.internal.file.filename=%s",
                            tempFolder.getRoot().toPath().resolve("schema_history.dat"))))
                .build());
  }

  @Test
  public void testNoProblem() {
    Pipeline readPipeline = Pipeline.create();
    PCollection<Row> result =
        PCollectionRowTuple.empty(readPipeline)
            .apply(
                makePtransform(
                    userName,
                    password,
                    database,
                    databaseContainer.getMappedPort(port),
                    "localhost"))
            .get("output");
    assertThat(
        result.getSchema().getFields().stream()
            .map(field -> field.getName())
            .collect(Collectors.toList()),
        Matchers.containsInAnyOrder(
            "before", "after", "source", "transaction", "op", "ts_ms", "ts_us", "ts_ns"));
  }

  @Test
  public void testWrongUser() {
    Pipeline readPipeline = Pipeline.create();
    DebeziumException ex =
        assertThrows(
            DebeziumException.class,
            () -> {
              PCollectionRowTuple.empty(readPipeline)
                  .apply(
                      makePtransform(
                          "wrongUser",
                          password,
                          database,
                          databaseContainer.getMappedPort(port),
                          "localhost"))
                  .get("output");
            });
    assertThat(ex.getCause().getMessage(), Matchers.containsString("password"));
    assertThat(ex.getCause().getMessage(), Matchers.containsString("wrongUser"));
  }

  @Test
  public void testWrongPassword() {
    Pipeline readPipeline = Pipeline.create();
    DebeziumException ex =
        assertThrows(
            DebeziumException.class,
            () -> {
              PCollectionRowTuple.empty(readPipeline)
                  .apply(
                      makePtransform(
                          userName,
                          "wrongPassword",
                          database,
                          databaseContainer.getMappedPort(port),
                          "localhost"))
                  .get("output");
            });
    assertThat(ex.getCause().getMessage(), Matchers.containsString("password"));
    assertThat(ex.getCause().getMessage(), Matchers.containsString(userName));
  }

  @Test
  public void testWrongPort() {
    Pipeline readPipeline = Pipeline.create();
    DebeziumException ex =
        assertThrows(
            DebeziumException.class,
            () -> {
              PCollectionRowTuple.empty(readPipeline)
                  .apply(makePtransform(userName, password, database, 12345, "localhost"))
                  .get("output");
            });
    Throwable lowestCause = ex.getCause();
    while (lowestCause.getCause() != null) {
      lowestCause = lowestCause.getCause();
    }
    assertThat(lowestCause.getMessage(), Matchers.containsString("Connection refused"));
  }

  @Test
  public void testWrongHost() {
    Pipeline readPipeline = Pipeline.create();
    assertThrows(
        Exception.class,
        () ->
            PCollectionRowTuple.empty(readPipeline)
                .apply(
                    makePtransform(
                        userName,
                        password,
                        database,
                        databaseContainer.getMappedPort(port),
                        "169.254.254.254"))
                .get("output"));
  }
}
