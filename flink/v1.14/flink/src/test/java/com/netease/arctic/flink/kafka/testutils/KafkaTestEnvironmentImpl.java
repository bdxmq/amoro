/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netease.arctic.flink.kafka.testutils;

import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import kafka.metrics.KafkaMetricsReporter;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import org.apache.commons.collections.list.UnmodifiableList;
import org.apache.commons.io.FileUtils;
import org.apache.curator.test.TestingServer;
import org.apache.flink.core.testutils.CommonTestUtils;
import org.apache.flink.util.NetUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.network.ListenerName;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.mutable.ArraySeq;

import java.io.File;
import java.net.BindException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** An implementation of the KafkaServerProvider. */
public class KafkaTestEnvironmentImpl extends KafkaTestEnvironment {

  protected static final Logger LOG = LoggerFactory.getLogger(KafkaTestEnvironmentImpl.class);
  private final List<KafkaServer> brokers = new ArrayList<>();
  private File tmpZkDir;
  private File tmpKafkaParent;
  private List<File> tmpKafkaDirs;
  private TestingServer zookeeper;
  private String zookeeperConnectionString;
  private String brokerConnectionString = "";
  private Properties standardProps;
  // 6 seconds is default. Seems to be too small for travis. 30 seconds
  private int zkTimeout = 30000;
  private Config config;
  private static final int DELETE_TIMEOUT_SECONDS = 30;

  @Override
  public void prepare(Config config) throws Exception {
    // increase the timeout since in Travis ZK connection takes long time for secure connection.
    if (config.isSecureMode()) {
      // run only one kafka server to avoid multiple ZK connections from many instances -
      // Travis timeout
      config.setKafkaServersNumber(1);
      zkTimeout = zkTimeout * 15;
    }
    this.config = config;

    String os = System.getProperty("os.name");
    if (os.toLowerCase().startsWith("win")) {
      tmpZkDir = createTempDirectory("kafkaITcase-zk-dir-").toFile();
      tmpKafkaParent = createTempDirectory("kafkaITcase-kafka-dir-").toFile();
    } else {
      tmpZkDir =
          createTempDirectory("kafkaITcase-zk-dir-", asFileAttribute(fromString("rwxrwxrwx")))
              .toFile();
      tmpKafkaParent =
          createTempDirectory("kafkaITcase-kafka-dir-", asFileAttribute(fromString("rwxrwxrwx")))
              .toFile();
    }

    tmpKafkaDirs = new ArrayList<>(config.getKafkaServersNumber());
    for (int i = 0; i < config.getKafkaServersNumber(); i++) {
      File tmpDir = new File(tmpKafkaParent, "server-" + i);
      assertTrue("cannot create kafka temp dir", tmpDir.mkdir());
      tmpKafkaDirs.add(tmpDir);
    }

    zookeeper = null;
    brokers.clear();

    zookeeper = new TestingServer(-1, tmpZkDir);
    zookeeperConnectionString = zookeeper.getConnectString();
    LOG.info("Starting Zookeeper with zookeeperConnectionString: {}", zookeeperConnectionString);

    LOG.info("Starting KafkaServer");

    ListenerName listenerName =
        ListenerName.forSecurityProtocol(
            config.isSecureMode() ? SecurityProtocol.SASL_PLAINTEXT : SecurityProtocol.PLAINTEXT);
    for (int i = 0; i < config.getKafkaServersNumber(); i++) {
      KafkaServer kafkaServer = getKafkaServer(i, tmpKafkaDirs.get(i));
      brokers.add(kafkaServer);
      brokerConnectionString +=
          NetUtils.hostAndPortToUrlString(
              KAFKA_HOST, kafkaServer.socketServer().boundPort(listenerName));
      brokerConnectionString += ",";
    }

    standardProps = new Properties();
    standardProps.setProperty("bootstrap.servers", brokerConnectionString);
    standardProps.setProperty("group.id", "arctic-tests");
    standardProps.setProperty("enable.auto.commit", "false");
    standardProps.setProperty("zookeeper.session.timeout.ms", String.valueOf(zkTimeout));
    standardProps.setProperty("zookeeper.connection.timeout.ms", String.valueOf(zkTimeout));
    standardProps.setProperty("auto.offset.reset", "earliest"); // read from the beginning.
    standardProps.setProperty(
        "max.partition.fetch.bytes", "256"); // make a lot of fetches (MESSAGES MUST BE SMALLER!)
    standardProps.setProperty(
        "key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    standardProps.setProperty(
        "value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    standardProps.setProperty(
        "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    standardProps.setProperty(
        "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    LOG.info("ZK and KafkaServer started.");
  }

  @Override
  public void deleteTestTopic(String topic) {
    LOG.info("Deleting topic {}", topic);
    Properties props = getSecureProperties();
    props.putAll(getStandardProperties());
    String clientId = Long.toString(new Random().nextLong());
    props.put("client.id", clientId);
    AdminClient adminClient = AdminClient.create(props);
    // We do not use a try-catch clause here so we can apply a timeout to the admin client
    // closure.
    try {
      tryDelete(adminClient, topic);
    } catch (Exception e) {
      e.printStackTrace();
      fail(String.format("Delete test topic : %s failed, %s", topic, e.getMessage()));
    } finally {
      adminClient.close(Duration.ofMillis(5000L));
      maybePrintDanglingThreadStacktrace(clientId);
    }
  }

  private void tryDelete(AdminClient adminClient, String topic) throws Exception {
    try {
      adminClient
          .deleteTopics(Collections.singleton(topic))
          .all()
          .get(DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      LOG.info(
          "Did not receive delete topic response within {} seconds. Checking if it succeeded",
          DELETE_TIMEOUT_SECONDS);
      if (adminClient
          .listTopics()
          .names()
          .get(DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .contains(topic)) {
        throw new Exception("Topic still exists after timeout");
      }
    }
  }

  @Override
  public void createTestTopic(
      String topic, int numberOfPartitions, int replicationFactor, Properties properties) {
    LOG.info("Creating topic {}", topic);
    try (AdminClient adminClient = AdminClient.create(getStandardProperties())) {
      NewTopic topicObj = new NewTopic(topic, numberOfPartitions, (short) replicationFactor);
      adminClient.createTopics(Collections.singleton(topicObj)).all().get();
      for (KafkaServer kafkaServer : brokers) {
        CommonTestUtils.waitUtil(
            () -> kafkaServer.metadataCache().contains(topic),
            Duration.ofSeconds(10),
            "The topic metadata failed to propagate to Kafka broker.");
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail("Create test topic : " + topic + " failed, " + e.getMessage());
    }
  }

  @Override
  public Properties getStandardProperties() {
    return standardProps;
  }

  @Override
  public Properties getSecureProperties() {
    Properties prop = new Properties();
    if (config.isSecureMode()) {
      prop.put("security.inter.broker.protocol", "SASL_PLAINTEXT");
      prop.put("security.protocol", "SASL_PLAINTEXT");
      prop.put("sasl.kerberos.service.name", "kafka");

      // add special timeout for Travis
      prop.setProperty("zookeeper.session.timeout.ms", String.valueOf(zkTimeout));
      prop.setProperty("zookeeper.connection.timeout.ms", String.valueOf(zkTimeout));
      prop.setProperty("metadata.fetch.timeout.ms", "120000");
    }
    return prop;
  }

  @Override
  public String getBrokerConnectionString() {
    return brokerConnectionString;
  }

  @Override
  public String getVersion() {
    return "2.0";
  }

  @Override
  public List<KafkaServer> getBrokers() {
    return brokers;
  }

  @Override
  public <K, V> Collection<ConsumerRecord<K, V>> getAllRecordsFromTopic(
      Properties properties, String topic, int partition, long timeout) {
    List<ConsumerRecord<K, V>> result = new ArrayList<>();

    try (KafkaConsumer<K, V> consumer = new KafkaConsumer<>(properties)) {
      consumer.assign(Arrays.asList(new TopicPartition(topic, partition)));

      while (true) {
        boolean processedAtLeastOneRecord = false;

        // wait for new records with timeout and break the loop if we didn't get any
        Iterator<ConsumerRecord<K, V>> iterator = consumer.poll(timeout).iterator();
        while (iterator.hasNext()) {
          ConsumerRecord<K, V> record = iterator.next();
          result.add(record);
          processedAtLeastOneRecord = true;
        }

        if (!processedAtLeastOneRecord) {
          break;
        }
      }
      consumer.commitSync();
    }

    return UnmodifiableList.decorate(result);
  }

  @Override
  public KafkaOffsetHandler createOffsetHandler() {
    return new KafkaOffsetHandlerImpl();
  }

  @Override
  public void restartBroker(int leaderId) throws Exception {
    brokers.set(leaderId, getKafkaServer(leaderId, tmpKafkaDirs.get(leaderId)));
  }

  @Override
  public int getLeaderToShutDown(String topic) throws Exception {
    AdminClient client = AdminClient.create(getStandardProperties());
    TopicDescription result =
        client.describeTopics(Collections.singleton(topic)).all().get().get(topic);
    return result.partitions().get(0).leader().id();
  }

  @Override
  public int getBrokerId(KafkaServer server) {
    return server.config().brokerId();
  }

  @Override
  public boolean isSecureRunSupported() {
    return true;
  }

  @Override
  public void shutdown() throws Exception {
    for (KafkaServer broker : brokers) {
      if (broker != null) {
        broker.shutdown();
      }
    }
    brokers.clear();

    if (zookeeper != null) {
      try {
        zookeeper.stop();
      } catch (Exception e) {
        LOG.warn("ZK.stop() failed", e);
      }
      zookeeper = null;
    }

    // clean up the temp spaces

    if (tmpKafkaParent != null && tmpKafkaParent.exists()) {
      try {
        FileUtils.deleteDirectory(tmpKafkaParent);
      } catch (Exception e) {
        // ignore
      }
    }
    if (tmpZkDir != null && tmpZkDir.exists()) {
      try {
        FileUtils.deleteDirectory(tmpZkDir);
      } catch (Exception e) {
        // ignore
      }
    }
  }

  protected KafkaServer getKafkaServer(int brokerId, File tmpFolder) throws Exception {
    Properties kafkaProperties = new Properties();

    // properties have to be Strings
    kafkaProperties.put("advertised.host.name", KAFKA_HOST);
    kafkaProperties.put("broker.id", Integer.toString(brokerId));
    kafkaProperties.put("log.dir", tmpFolder.toString());
    kafkaProperties.put("zookeeper.connect", zookeeperConnectionString);
    kafkaProperties.put("message.max.bytes", String.valueOf(50 * 1024 * 1024));
    kafkaProperties.put("replica.fetch.max.bytes", String.valueOf(50 * 1024 * 1024));
    kafkaProperties.put(
        "transaction.max.timeout.ms", Integer.toString(1000 * 60 * 60 * 2)); // 2hours

    // for CI stability, increase zookeeper session timeout
    kafkaProperties.put("zookeeper.session.timeout.ms", zkTimeout);
    kafkaProperties.put("zookeeper.connection.timeout.ms", zkTimeout);
    if (config.getKafkaServerProperties() != null) {
      kafkaProperties.putAll(config.getKafkaServerProperties());
    }

    final int numTries = 5;

    for (int i = 1; i <= numTries; i++) {
      int kafkaPort = NetUtils.getAvailablePort();
      kafkaProperties.put("port", Integer.toString(kafkaPort));

      // to support secure kafka cluster
      if (config.isSecureMode()) {
        LOG.info("Adding Kafka secure configurations");
        kafkaProperties.put("listeners", "SASL_PLAINTEXT://" + KAFKA_HOST + ":" + kafkaPort);
        kafkaProperties.put(
            "advertised.listeners", "SASL_PLAINTEXT://" + KAFKA_HOST + ":" + kafkaPort);
        kafkaProperties.putAll(getSecureProperties());
      }

      KafkaConfig kafkaConfig = new KafkaConfig(kafkaProperties);

      try {
        scala.Option<String> stringNone = scala.Option.apply(null);
        KafkaServer server =
            new KafkaServer(
                kafkaConfig, Time.SYSTEM, stringNone, new ArraySeq<KafkaMetricsReporter>(0));
        server.startup();
        return server;
      } catch (KafkaException e) {
        if (e.getCause() instanceof BindException) {
          // port conflict, retry...
          LOG.info("Port conflict when starting Kafka Broker. Retrying...");
        } else {
          throw e;
        }
      }
    }

    throw new Exception(
        "Could not start Kafka after " + numTries + " retries due to port conflicts.");
  }

  private class KafkaOffsetHandlerImpl implements KafkaOffsetHandler {

    private final KafkaConsumer<byte[], byte[]> offsetClient;

    public KafkaOffsetHandlerImpl() {
      Properties props = new Properties();
      props.putAll(standardProps);
      props.setProperty(
          "key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
      props.setProperty(
          "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");

      offsetClient = new KafkaConsumer<>(props);
    }

    @Override
    public Long getCommittedOffset(String topicName, int partition) {
      TopicPartition topicPartition = new TopicPartition(topicName, partition);
      Map<TopicPartition, OffsetAndMetadata> committed =
          offsetClient.committed(Collections.singleton(topicPartition));
      return (committed != null) ? committed.get(topicPartition).offset() : null;
    }

    @Override
    public void setCommittedOffset(String topicName, int partition, long offset) {
      Map<TopicPartition, OffsetAndMetadata> partitionAndOffset = new HashMap<>();
      partitionAndOffset.put(
          new TopicPartition(topicName, partition), new OffsetAndMetadata(offset));
      offsetClient.commitSync(partitionAndOffset);
    }

    @Override
    public void close() {
      offsetClient.close();
    }
  }
}
