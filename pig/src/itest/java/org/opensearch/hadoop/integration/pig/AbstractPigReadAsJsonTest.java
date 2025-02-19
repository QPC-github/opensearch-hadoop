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

package org.opensearch.hadoop.integration.pig;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.opensearch.hadoop.HdpBootstrap;
import org.opensearch.hadoop.QueryTestParams;
import org.opensearch.hadoop.OpenSearchAssume;
import org.opensearch.hadoop.mr.HadoopCfgUtils;
import org.opensearch.hadoop.rest.RestUtils;
import org.opensearch.hadoop.util.OpenSearchMajorVersion;
import org.opensearch.hadoop.util.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.LazyTempFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import static org.opensearch.hadoop.util.TestUtils.resource;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertThat;

@RunWith(Parameterized.class)
public class AbstractPigReadAsJsonTest extends AbstractPigTests {

    private static int testInstance = 0;
    private static String previousQuery;
    private boolean readMetadata;
    private OpenSearchMajorVersion testVersion;
    private static Configuration testConfiguration = HdpBootstrap.hadoopConfig();
    private static String workingDir = HadoopCfgUtils.isLocal(testConfiguration) ? Paths.get("").toAbsolutePath().toString() : "/";

    @ClassRule
    public static LazyTempFolder tempFolder = new LazyTempFolder();

    @Parameters
    public static Collection<Object[]> queries() {
        return new QueryTestParams(tempFolder).params();
    }

    private final String query;

    public AbstractPigReadAsJsonTest(String query, boolean metadata) {
        this.query = query;
        this.readMetadata = metadata;
        this.testVersion = TestUtils.getOpenSearchClusterInfo().getMajorVersion();

        if (!query.equals(previousQuery)) {
            previousQuery = query;
            testInstance++;
        }
    }

    private String scriptHead;


    @BeforeClass
    public static void beforeClass() throws Exception {
        // we do this just here since the configuration doesn't get used in Pig scripts.
        new QueryTestParams(tempFolder).provisionQueries(AbstractPigTests.testConfiguration);
    }

    @Before
    public void before() throws Exception {
        RestUtils.refresh("json-pig*");

        this.scriptHead =
                "DEFINE OpenSearchStorage org.opensearch.hadoop.pig.OpenSearchStorage('opensearch.index.read.missing.as.empty=true','opensearch.query=" + query + "','opensearch.read.metadata=" + readMetadata +"','opensearch.output.json=true');";
    }

    @Test
    public void testTuple() throws Exception {
        String script = scriptHead +
                "A = LOAD '"+resource("json-pig-tupleartists", "data", testVersion)+"' USING OpenSearchStorage();" +
                "X = LIMIT A 3;" +
                //"DESCRIBE A;";
                "STORE A INTO '" + tmpPig() + "/testtuple';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testtuple");

        String metaType = "data";
        if (TestUtils.isTypelessVersion(testVersion)) {
            metaType = "_doc";
        }

        List<String> doc1 = Lists.newArrayList(
                "{\"number\":\"12\",\"name\":\"Behemoth\",\"url\":\"http://www.last.fm/music/Behemoth\",\"picture\":\"http://userserve-ak.last.fm/serve/252/54196161.jpg\",\"@timestamp\":\"2001-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc1.add("\"_metadata\":{\"_index\":\"json-pig-tupleartists\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc1.add("\"_type\":\""+metaType+"\"");
            }
            doc1.add("\"_id\":\"");
            doc1.add("\"_score\":");
        }

        List<String> doc2 = Lists.newArrayList(
                "{\"number\":\"918\",\"name\":\"Megadeth\",\"url\":\"http://www.last.fm/music/Megadeth\",\"picture\":\"http://userserve-ak.last.fm/serve/252/8129787.jpg\",\"@timestamp\":\"2017-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc2.add("\"_metadata\":{\"_index\":\"json-pig-tupleartists\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc2.add("\"_type\":\""+metaType+"\"");
            }
            doc2.add("\"_id\":\"");
            doc2.add("\"_score\":");
        }

        List<String> doc3 = Lists.newArrayList(
                "{\"number\":\"982\",\"name\":\"Foo Fighters\",\"url\":\"http://www.last.fm/music/Foo+Fighters\",\"picture\":\"http://userserve-ak.last.fm/serve/252/59495563.jpg\",\"@timestamp\":\"2017-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc3.add("\"_metadata\":{\"_index\":\"json-pig-tupleartists\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc3.add("\"_type\":\""+metaType+"\"");
            }
            doc3.add("\"_id\":\"");
            doc3.add("\"_score\":");
        }

        assertThat(results, stringContainsInOrder(doc1));
        assertThat(results, stringContainsInOrder(doc2));
        assertThat(results, stringContainsInOrder(doc3));
    }

    @Test
    public void testTupleWithSchema() throws Exception {
        String script = scriptHead +
                "A = LOAD '"+resource("json-pig-tupleartists", "data", testVersion)+"' USING OpenSearchStorage() AS (name:chararray);" +
                "B = ORDER A BY name DESC;" +
                "X = LIMIT B 3;" +
                "STORE B INTO '" + tmpPig() + "/testtupleschema';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testtupleschema");

        String metaType = "data";
        if (TestUtils.isTypelessVersion(testVersion)) {
            metaType = "_doc";
        }

        List<String> doc1 = Lists.newArrayList(
                "{\"number\":\"999\",\"name\":\"Thompson Twins\",\"url\":\"http://www.last.fm/music/Thompson+Twins\",\"picture\":\"http://userserve-ak.last.fm/serve/252/6943589.jpg\",\"@timestamp\":\"2017-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc1.add("\"_metadata\":{\"_index\":\"json-pig-tupleartists\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc1.add("\"_type\":\""+metaType+"\"");
            }
            doc1.add("\"_id\":\"");
            doc1.add("\"_score\":");
        }

        List<String> doc2 = Lists.newArrayList(
                "{\"number\":\"12\",\"name\":\"Behemoth\",\"url\":\"http://www.last.fm/music/Behemoth\",\"picture\":\"http://userserve-ak.last.fm/serve/252/54196161.jpg\",\"@timestamp\":\"2001-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc2.add("\"_metadata\":{\"_index\":\"json-pig-tupleartists\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc2.add("\"_type\":\""+metaType+"\"");
            }
            doc2.add("\"_id\":\"");
            doc2.add("\"_score\":");
        }

        List<String> doc3 = Lists.newArrayList(
                "{\"number\":\"230\",\"name\":\"Green Day\",\"url\":\"http://www.last.fm/music/Green+Day\",\"picture\":\"http://userserve-ak.last.fm/serve/252/15291249.jpg\",\"@timestamp\":\"2005-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc3.add("\"_metadata\":{\"_index\":\"json-pig-tupleartists\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc3.add("\"_type\":\""+metaType+"\"");
            }
            doc3.add("\"_id\":\"");
            doc3.add("\"_score\":");
        }

        assertThat(results, stringContainsInOrder(doc1));
        assertThat(results, stringContainsInOrder(doc2));
        assertThat(results, stringContainsInOrder(doc3));
    }

    @Test
    public void testFieldAlias() throws Exception {
        String script = scriptHead
                      + "A = LOAD '"+resource("json-pig-fieldalias", "data", testVersion)+"' USING OpenSearchStorage();"
                      + "X = LIMIT A 3;"
                      + "STORE A INTO '" + tmpPig() + "/testfieldalias';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testfieldalias");

        String metaType = "data";
        if (TestUtils.isTypelessVersion(testVersion)) {
            metaType = "_doc";
        }

        List<String> doc1 = Lists.newArrayList(
                "{\"number\":\"12\",\"name\":\"Behemoth\",\"url\":\"http://www.last.fm/music/Behemoth\",\"picture\":\"http://userserve-ak.last.fm/serve/252/54196161.jpg\",\"@timestamp\":\"2001-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc1.add("\"_metadata\":{\"_index\":\"json-pig-fieldalias\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc1.add("\"_type\":\""+metaType+"\"");
            }
            doc1.add("\"_id\":\"");
            doc1.add("\"_score\":");
        }

        List<String> doc2 = Lists.newArrayList(
                "{\"number\":\"918\",\"name\":\"Megadeth\",\"url\":\"http://www.last.fm/music/Megadeth\",\"picture\":\"http://userserve-ak.last.fm/serve/252/8129787.jpg\",\"@timestamp\":\"2017-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc2.add("\"_metadata\":{\"_index\":\"json-pig-fieldalias\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc2.add("\"_type\":\""+metaType+"\"");
            }
            doc2.add("\"_id\":\"");
            doc2.add("\"_score\":");
        }

        List<String> doc3 = Lists.newArrayList(
                "{\"number\":\"982\",\"name\":\"Foo Fighters\",\"url\":\"http://www.last.fm/music/Foo+Fighters\",\"picture\":\"http://userserve-ak.last.fm/serve/252/59495563.jpg\",\"@timestamp\":\"2017-10-06T19:20:25.000Z\",\"list\":[\"quick\", \"brown\", \"fox\"]"
        );
        if (readMetadata) {
            doc3.add("\"_metadata\":{\"_index\":\"json-pig-fieldalias\"");
            if (testVersion.before(OpenSearchMajorVersion.V_3_X)) {
                doc3.add("\"_type\":\""+metaType+"\"");
            }
            doc3.add("\"_id\":\"");
            doc3.add("\"_score\":");
        }

        assertThat(results, stringContainsInOrder(doc1));
        assertThat(results, stringContainsInOrder(doc2));
        assertThat(results, stringContainsInOrder(doc3));
    }

    @Test
    public void testMissingIndex() throws Exception {
        String script = scriptHead
                      + "A = LOAD '"+resource("foo", "bar", testVersion)+"' USING OpenSearchStorage();"
                      + "X = LIMIT A 3;"
                      + "STORE A INTO '" + tmpPig() + "/testmissingindex';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testmissingindex");
        assertThat(results.length(), is(0));
    }

    @Test
    public void testDynamicPattern() throws Exception {
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-1", "data", testVersion)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-5", "data", testVersion)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-9", "data", testVersion)));
    }

    @Test
    public void testDynamicPatternFormat() throws Exception {
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-format-2001-10-06", "data", testVersion)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-format-2005-10-06", "data", testVersion)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-format-2017-10-06", "data", testVersion)));
    }

    private static String tmpPig() {
        return new Path("tmp-pig/read-json-" + testInstance)
                .makeQualified(FileSystem.getDefaultUri(AbstractPigTests.testConfiguration), new Path(workingDir))
                .toUri()
                .toString();
    }
}