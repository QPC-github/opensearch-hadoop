/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */
 
/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.opensearch.spark.integration;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Seconds;
import org.apache.spark.streaming.StreamingContextState;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.scheduler.StreamingListener;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchCompleted;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchSubmitted;
import org.apache.spark.streaming.scheduler.StreamingListenerOutputOperationCompleted;
import org.apache.spark.streaming.scheduler.StreamingListenerOutputOperationStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverError;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStopped;
import org.apache.spark.streaming.scheduler.StreamingListenerStreamingStarted;
import org.opensearch.hadoop.OpenSearchHadoopIllegalArgumentException;
import org.opensearch.hadoop.rest.RestUtils;
import org.opensearch.hadoop.util.OpenSearchMajorVersion;
import org.opensearch.hadoop.util.StringUtils;
import org.opensearch.hadoop.util.TestSettings;
import org.opensearch.hadoop.util.TestUtils;
import org.opensearch.spark.rdd.Metadata;
import org.opensearch.spark.rdd.api.java.JavaOpenSearchSpark;
import org.opensearch.spark.streaming.api.java.JavaOpenSearchSparkStreaming;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized;
import scala.Option;
import scala.Tuple2;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.opensearch.hadoop.cfg.ConfigurationOptions.*;
import static org.opensearch.hadoop.util.TestUtils.docEndpoint;
import static org.opensearch.hadoop.util.TestUtils.resource;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static scala.collection.JavaConversions.propertiesAsScalaMap;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(Parameterized.class)
public class AbstractJavaOpenSearchSparkStreamingTest implements Serializable {

    private static final transient SparkConf conf = new SparkConf()
            .setMaster("local")
            .setAppName("opensearchtest")
            .setJars(SparkUtils.OPENSEARCH_SPARK_TESTING_JAR);

    private static transient JavaSparkContext sc = null;

    @Parameterized.Parameters
    public static Collection<Object[]> testParams() {
        Collection<Object[]> params = new ArrayList<>();
        params.add(new Object[] {"java-stream-default", false});
        return params;
    }

    @BeforeClass
    public static void setup() {
        conf.setAll(propertiesAsScalaMap(TestSettings.TESTING_PROPS));
        sc = new JavaSparkContext(conf);
    }

    @AfterClass
    public static void clean() throws Exception {
        if (sc != null) {
            sc.stop();
            // wait for jetty & spark to properly shutdown
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));
        }
    }

    private String prefix;
    private Map<String, String> cfg = new HashMap<>();
    private JavaStreamingContext ssc = null;
    private OpenSearchMajorVersion version = TestUtils.getOpenSearchClusterInfo().getMajorVersion();

    public AbstractJavaOpenSearchSparkStreamingTest(String prefix, boolean readMetadata) {
        this.prefix = prefix;
        this.cfg.put(OPENSEARCH_READ_METADATA, Boolean.toString(readMetadata));
    }

    @Before
    public void createStreamingContext() throws Exception {
        ssc = new JavaStreamingContext(sc, Seconds.apply(1));
    }

    @After
    public void tearDownStreamingContext() throws Exception {
        if (ssc != null && ssc.getState() != StreamingContextState.STOPPED) {
            ssc.stop(false, true);
        }
    }

    @Test
    public void testOpenSearchRDDWriteIndexCreationDisabled() throws Exception {
        ExpectingToThrow expecting = expectingToThrow(OpenSearchHadoopIllegalArgumentException.class).from(ssc);

        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("one", null);
        Set<String> values = new HashSet<>();
        values.add("2");
        doc1.put("two", values);
        doc1.put("three", ".");

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("OTP", "Otopeni");
        doc2.put("SFO", "San Fran");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc1);
        docs.add(doc2);

        String target = wrapIndex(resource("spark-test-nonexisting-scala-basic-write", "data", version));

        Map<String, String> localConf = new HashMap<>(cfg);
        localConf.put(OPENSEARCH_INDEX_AUTO_CREATE, "no");

        JavaRDD<Map<String, Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaInputDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue, true);
        // apply closure
        JavaOpenSearchSparkStreaming.saveToOpenSearch(dstream, target, localConf);
        ssc.start();
        TimeUnit.SECONDS.sleep(2); // Let the processing happen
        ssc.stop(false, true);

        assertTrue(!RestUtils.exists(target));
        expecting.assertExceptionFound();
    }

    @Test
    public void testOpenSearchDataFrame1Write() throws Exception {
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("one", null);
        Set<String> values = new HashSet<>();
        values.add("2");
        doc1.put("two", values);
        doc1.put("three", ".");

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("OTP", "Otopeni");
        doc2.put("SFO", "San Fran");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc1);
        docs.add(doc2);

        String target = wrapIndex(resource("spark-streaming-test-scala-basic-write", "data", version));

        JavaRDD<Map<String, Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue, true);
        JavaOpenSearchSparkStreaming.saveToOpenSearch(dstream, target, cfg);
        ssc.start();
        TimeUnit.SECONDS.sleep(2); // Let the processing happen
        ssc.stop(false, true);

        assertTrue(RestUtils.exists(target));
        assertThat(RestUtils.get(target + "/_search?"), containsString("OTP"));
        assertThat(RestUtils.get(target + "/_search?"), containsString("two"));
    }

    @Test
    public void testOpenSearchRDDWriteWIthMappingId() throws Exception {
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("number", 1);
        doc1.put("one", null);
        Set<String> values = new HashSet<>();
        values.add("2");
        doc1.put("two", values);
        doc1.put("three", ".");

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("number", 2);
        doc2.put("OTP", "Otopeni");
        doc2.put("SFO", "San Fran");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc1);
        docs.add(doc2);

        Map<String, String> localConf = new HashMap<>(cfg);
        localConf.put("opensearch.mapping.id", "number");

        String target = wrapIndex(resource("spark-streaming-test-scala-id-write", "data", version));
        String docEndpoint = wrapIndex(docEndpoint("spark-streaming-test-scala-id-write", "data", version));

        JavaRDD<Map<String,Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue);
        JavaOpenSearchSparkStreaming.saveToOpenSearch(dstream, target, localConf);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertEquals(2, JavaOpenSearchSpark.opensearchRDD(sc, target).count());
        assertTrue(RestUtils.exists(docEndpoint + "/1"));
        assertTrue(RestUtils.exists(docEndpoint + "/2"));

        assertThat(RestUtils.get(target + "/_search?"), containsString("SFO"));
    }

    @Test
    public void testOpenSearchRDDWriteWithDynamicMapping() throws Exception {
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("number", 3);
        doc1.put("one", null);
        Set<String> values = new HashSet<>();
        values.add("2");
        doc1.put("two", values);
        doc1.put("three", ".");

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("number", 4);
        doc2.put("OTP", "Otopeni");
        doc2.put("SFO", "San Fran");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc1);
        docs.add(doc2);

        String target = wrapIndex(resource("spark-streaming-test-scala-dyn-id-write", "data", version));
        String docEndpoint = wrapIndex(docEndpoint("spark-streaming-test-scala-dyn-id-write", "data", version));

        JavaRDD<Map<String,Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue);

        JavaPairDStream<Integer, Map<String, Object>> metaDstream = dstream.mapToPair(new ExtractIDFunction());

        JavaOpenSearchSparkStreaming.saveToOpenSearchWithMeta(metaDstream, target, cfg);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertEquals(2, JavaOpenSearchSpark.opensearchRDD(sc, target).count());
        assertTrue(RestUtils.exists(docEndpoint + "/3"));
        assertTrue(RestUtils.exists(docEndpoint + "/4"));

        assertThat(RestUtils.get(target + "/_search?"), containsString("SFO"));
    }

    public static class ExtractIDFunction implements PairFunction<Map<String, Object>, Integer, Map<String, Object>>, Serializable {
        @Override
        public Tuple2<Integer, Map<String, Object>> call(Map<String, Object> stringObjectMap) throws Exception {
            Integer key = (Integer) stringObjectMap.remove("number");
            return new Tuple2<Integer, Map<String, Object>>(key, stringObjectMap);
        }
    }

    @Test
    public void testOpenSearchRDDWriteWithDynamicMapMapping() throws Exception {
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("id", 5);
        doc1.put("version", "3");
        doc1.put("one", null);
        Set<String> values = new HashSet<>();
        values.add("2");
        doc1.put("two", values);
        doc1.put("three", ".");

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("id", 6);
        doc1.put("version", "5");
        doc2.put("OTP", "Otopeni");
        doc2.put("SFO", "San Fran");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc1);
        docs.add(doc2);

        String target = wrapIndex(resource("spark-streaming-test-scala-dyn-id-write-map", "data", version));
        String docEndpoint = wrapIndex(docEndpoint("spark-streaming-test-scala-dyn-id-write-map", "data", version));

        JavaRDD<Map<String,Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue);

        JavaPairDStream<Map<Metadata, Object>, Map<String, Object>> metaDstream = dstream.mapToPair(new ExtractMetaMap());

        JavaOpenSearchSparkStreaming.saveToOpenSearchWithMeta(metaDstream, target, cfg);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertEquals(2, JavaOpenSearchSpark.opensearchRDD(sc, target).count());
        assertTrue(RestUtils.exists(docEndpoint + "/5"));
        assertTrue(RestUtils.exists(docEndpoint + "/6"));

        assertThat(RestUtils.get(target + "/_search?"), containsString("SFO"));
    }

    public static class ExtractMetaMap implements PairFunction<Map<String, Object>, Map<Metadata, Object>, Map<String, Object>>, Serializable {
        @Override
        public Tuple2<Map<Metadata, Object>, Map<String, Object>> call(Map<String, Object> record) throws Exception {
            Integer key = (Integer) record.remove("id");
            String version = (String) record.remove("version");
            Map<Metadata, Object> metadata = new HashMap<Metadata, Object>();
            metadata.put(Metadata.ID, key);
            metadata.put(Metadata.VERSION, version);
            return new Tuple2<Map<Metadata, Object>, Map<String, Object>>(metadata, record);
        }
    }

    @Test
    public void testOpenSearchRDDWriteWithMappingExclude() throws Exception {
        Map<String, Object> trip1 = new HashMap<>();
        trip1.put("reason", "business");
        trip1.put("airport", "SFO");

        Map<String, Object> trip2 = new HashMap<>();
        trip2.put("participants", 5);
        trip2.put("airport", "OTP");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(trip1);
        docs.add(trip2);

        String target = wrapIndex(resource("spark-streaming-test-scala-write-exclude", "data", version));

        Map<String, String> localConf = new HashMap<>(cfg);
        localConf.put(OPENSEARCH_MAPPING_EXCLUDE, "airport");

        JavaRDD<Map<String, Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue);
        JavaOpenSearchSparkStreaming.saveToOpenSearch(dstream, target, localConf);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertTrue(RestUtils.exists(target));
        assertThat(RestUtils.get(target + "/_search?"), containsString("business"));
        assertThat(RestUtils.get(target +  "/_search?"), containsString("participants"));
        assertThat(RestUtils.get(target +  "/_search?"), not(containsString("airport")));
    }

    @Test
    public void testOpenSearchRDDIngest() throws Exception {

        RestUtils.ExtendedRestClient client = new RestUtils.ExtendedRestClient();
        String pipelineName =  prefix + "-pipeline";
        String pipeline = "{\"description\":\"Test Pipeline\",\"processors\":[{\"set\":{\"field\":\"pipeTEST\",\"value\":true,\"override\":true}}]}";
        client.put("/_ingest/pipeline/" + pipelineName, StringUtils.toUTF(pipeline));
        client.close();

        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("one", null);
        Set<String> values = new HashSet<>();
        values.add("2");
        doc1.put("two", values);
        doc1.put("three", ".");

        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("OTP", "Otopeni");
        doc2.put("SFO", "San Fran");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(doc1);
        docs.add(doc2);

        String target = wrapIndex(resource("spark-streaming-test-scala-ingest-write", "data", version));

        Map<String, String> localConf = new HashMap<>(cfg);
        localConf.put(OPENSEARCH_INGEST_PIPELINE, pipelineName);
        localConf.put(OPENSEARCH_NODES_INGEST_ONLY, "true");

        JavaRDD<Map<String, Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue);
        JavaOpenSearchSparkStreaming.saveToOpenSearch(dstream, target, localConf);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertTrue(RestUtils.exists(target));
        assertThat(RestUtils.get(target+"/_search?"), containsString("\"pipeTEST\":true"));
    }

    @Test
    public void testMultiIndexRDDWrite() throws Exception {
        Map<String, Object> trip1 = new HashMap<>();
        trip1.put("reason", "business");
        trip1.put("airport", "sfo");

        Map<String, Object> trip2 = new HashMap<>();
        trip2.put("participants", 5);
        trip2.put("airport", "otp");

        List<Map<String, Object>> docs = new ArrayList<>();
        docs.add(trip1);
        docs.add(trip2);

        String target = wrapIndex(resource("spark-streaming-test-trip-{airport}", "data", version));

        JavaRDD<Map<String, Object>> batch = sc.parallelize(docs);
        Queue<JavaRDD<Map<String, Object>>> rddQueue = new LinkedList<>();
        rddQueue.add(batch);
        JavaDStream<Map<String, Object>> dstream = ssc.queueStream(rddQueue);
        JavaOpenSearchSparkStreaming.saveToOpenSearch(dstream, target, cfg);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertTrue(RestUtils.exists(wrapIndex(resource("spark-streaming-test-trip-otp", "data", version))));
        assertTrue(RestUtils.exists(wrapIndex(resource("spark-streaming-test-trip-sfo", "data", version))));

        assertThat(RestUtils.get(wrapIndex(resource("spark-streaming-test-trip-sfo", "data", version) + "/_search?")), containsString("business"));
        assertThat(RestUtils.get(wrapIndex(resource("spark-streaming-test-trip-otp", "data", version) + "/_search?")), containsString("participants"));
    }

    @Test
    public void testOpenSearchWriteAsJsonMultiWrite() throws Exception {
        String json1 = "{\"reason\" : \"business\",\"airport\" : \"sfo\"}";
        String json2 = "{\"participants\" : 5,\"airport\" : \"otp\"}";

        List<String> docs = new ArrayList<>();
        docs.add(json1);
        docs.add(json2);

        String jsonTarget = wrapIndex(resource("spark-streaming-test-json-{airport}", "data", version));

        JavaRDD<String> batch1 = sc.parallelize(docs);
        Queue<JavaRDD<String>> rddQueue1 = new LinkedList<>();
        rddQueue1.add(batch1);
        JavaDStream<String> dstream = ssc.queueStream(rddQueue1);
        JavaOpenSearchSparkStreaming.saveJsonToEs(dstream, jsonTarget, cfg);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);
        ssc = new JavaStreamingContext(sc, Seconds.apply(1));

        byte[] json1BA = json1.getBytes();
        byte[] json2BA = json2.getBytes();

        List<byte[]> byteDocs = new ArrayList<>();
        byteDocs.add(json1BA);
        byteDocs.add(json2BA);

        String jsonBATarget = wrapIndex(resource("spark-streaming-test-json-ba-{airport}", "data", version));

        JavaRDD<byte[]> batch2 = sc.parallelize(byteDocs);
        Queue<JavaRDD<byte[]>> rddQueue2 = new LinkedList<>();
        rddQueue2.add(batch2);
        JavaDStream<byte[]> dStreamBytes = ssc.queueStream(rddQueue2);
        JavaOpenSearchSparkStreaming.saveJsonByteArrayToEs(dStreamBytes, jsonBATarget, cfg);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertTrue(RestUtils.exists(wrapIndex(resource("spark-streaming-test-json-sfo", "data", version))));
        assertTrue(RestUtils.exists(wrapIndex(resource("spark-streaming-test-json-otp", "data", version))));

        assertTrue(RestUtils.exists(wrapIndex(resource("spark-streaming-test-json-ba-sfo", "data", version))));
        assertTrue(RestUtils.exists(wrapIndex(resource("spark-streaming-test-json-ba-otp", "data", version))));

        assertThat(RestUtils.get(wrapIndex(resource("spark-streaming-test-json-sfo", "data", version) + "/_search?")), containsString("business"));
        assertThat(RestUtils.get(wrapIndex(resource("spark-streaming-test-json-otp", "data", version) + "/_search?")), containsString("participants"));
    }

    @Test
    public void testOpenSearchRDDWriteWithUpsertScriptUsingBothObjectAndRegularString() throws Exception {
        String keyword = "keyword";

        String mapping = "{\"properties\":{\"id\":{\"type\":\""+keyword+"\"},\"note\":{\"type\":\""+keyword+"\"},\"address\":{\"type\":\"nested\",\"properties\":{\"id\":{\"type\":\""+keyword+"\"},\"zipcode\":{\"type\":\""+keyword+"\"}}}}}";
        if (!TestUtils.isTypelessVersion(version)) {
            mapping = "{\"data\":"+mapping+"}";
        }
        String index = wrapIndex("spark-streaming-test-contact");
        String type = "data";
        String target = resource(index, type, version);
        String docEndpoint = docEndpoint(index, type, version);

        RestUtils.touch(index);
        RestUtils.putMapping(index, type, mapping.getBytes());
        RestUtils.postData(docEndpoint+"/1", "{\"id\":\"1\",\"note\":\"First\",\"address\":[]}".getBytes());
        RestUtils.postData(docEndpoint+"/2", "{\"id\":\"2\",\"note\":\"First\",\"address\":[]}".getBytes());

        String lang = "painless";
        Map<String, String> props = new HashMap<>();
        props.put("opensearch.write.operation", "upsert");
        props.put("opensearch.input.json", "true");
        props.put("opensearch.mapping.id", "id");
        props.put("opensearch.update.script.lang", lang);

        String doc1 = "{\"id\":\"1\",\"address\":{\"zipcode\":\"12345\",\"id\":\"1\"}}";
        List<String> docs1 = new ArrayList<>();
        docs1.add(doc1);
        String upParams = "new_address:address";
        String upScript = "ctx._source.address.add(params.new_address)";

        Map<String, String> localConf1 = new HashMap<>(props);
        localConf1.put("opensearch.update.script.params", upParams);
        localConf1.put("opensearch.update.script", upScript);

        JavaRDD<String> batch1 = sc.parallelize(docs1);
        Queue<JavaRDD<String>> rddQueue1 = new LinkedList<>();
        rddQueue1.add(batch1);
        JavaDStream<String> dstream1 = ssc.queueStream(rddQueue1);
        JavaOpenSearchSparkStreaming.saveJsonToEs(dstream1, target, localConf1);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);
        ssc = new JavaStreamingContext(sc, Seconds.apply(1));

        String doc2 = "{\"id\":\"2\",\"note\":\"Second\"}";
        List<String> docs2 = new ArrayList<>();
        docs2.add(doc2);
        String noteUpParams = "new_note:note";
        String noteUpScript = "ctx._source.note = params.new_note";

        Map<String, String> localConf2 = new HashMap<>(props);
        localConf2.put("opensearch.update.script.params", noteUpParams);
        localConf2.put("opensearch.update.script", noteUpScript);

        JavaRDD<String> batch2 = sc.parallelize(docs2);
        Queue<JavaRDD<String>> rddQueue2 = new LinkedList<>();
        rddQueue2.add(batch2);
        JavaDStream<String> dstream2 = ssc.queueStream(rddQueue2);
        JavaOpenSearchSparkStreaming.saveJsonToEs(dstream2, target, localConf2);
        ssc.start();
        TimeUnit.SECONDS.sleep(2);
        ssc.stop(false, true);

        assertTrue(RestUtils.exists(docEndpoint + "/1"));
        assertThat(RestUtils.get(docEndpoint + "/1"), both(containsString("\"zipcode\":\"12345\"")).and(containsString("\"note\":\"First\"")));

        assertTrue(RestUtils.exists(docEndpoint + "/2"));
        assertThat(RestUtils.get(docEndpoint + "/2"), both(not(containsString("\"zipcode\":\"12345\""))).and(containsString("\"note\":\"Second\"")));
    }

    private String wrapIndex(String index) {
        return prefix + index;
    }

    private static ExpectingToThrow expectingToThrow(Class<? extends Throwable> expected) {
        return new ExpectingToThrow(expected);
    }

    /**
     * We need to write this convoluted event listener to watch for exceptions in jobs
     * because spark streaming does not throw an exception that describes the exception
     * from a job.
     *
     * Instead, we have to write this thing to make sure that the expected exceptions
     * occur.
     */
    private static class ExpectingToThrow implements StreamingListener {

        private Class<?> expectedException;
        private boolean foundException = false;
        private String exceptionType = null;

        public ExpectingToThrow(Class<? extends Throwable> expectedException) {
            this.expectedException = expectedException;
        }

        @Override
        public void onOutputOperationCompleted(StreamingListenerOutputOperationCompleted outputOperationCompleted) {
            String exceptionName = null;
            Option<String> failureReason = outputOperationCompleted.outputOperationInfo().failureReason();
            if (failureReason.isDefined()) {
                String value = failureReason.get();
                exceptionName = value.substring(0, value.indexOf(':'));
            }

            foundException = foundException || expectedException.getCanonicalName().equals(exceptionName);
            if (foundException) {
                exceptionType = exceptionName;
            }
        }

        @Override
        public void onStreamingStarted(StreamingListenerStreamingStarted streamingStarted) {
            // not implemented
        }

        @Override
        public void onReceiverStarted(StreamingListenerReceiverStarted receiverStarted) {
            // not implemented
        }

        @Override
        public void onReceiverError(StreamingListenerReceiverError receiverError) {
            // not implemented
        }

        @Override
        public void onReceiverStopped(StreamingListenerReceiverStopped receiverStopped) {
            // not implemented
        }

        @Override
        public void onBatchSubmitted(StreamingListenerBatchSubmitted batchSubmitted) {
            // not implemented
        }

        @Override
        public void onBatchStarted(StreamingListenerBatchStarted batchStarted) {
            // not implemented
        }

        @Override
        public void onBatchCompleted(StreamingListenerBatchCompleted batchCompleted) {
            // not implemented
        }

        @Override
        public void onOutputOperationStarted(StreamingListenerOutputOperationStarted outputOperationStarted) {
            // not implemented
        }

        public ExpectingToThrow from(JavaStreamingContext ssc) {
            ssc.addStreamingListener(this);
            return this;
        }

        public void assertExceptionFound() throws Exception {
            if (!foundException) {
                if (exceptionType != null) {
                    Assert.fail("Expected " + expectedException.getCanonicalName() + " but got " + exceptionType.toString());
                } else {
                    Assert.fail("Expected " + expectedException.getCanonicalName() + " but no Exceptions were thrown");
                }
            }
        }
    }
}