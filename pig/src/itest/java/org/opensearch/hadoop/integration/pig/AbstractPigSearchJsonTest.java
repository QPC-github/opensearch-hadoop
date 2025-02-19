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

import java.nio.file.Paths;
import java.util.Collection;

import org.apache.commons.logging.LogFactory;
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

import static org.opensearch.hadoop.util.TestUtils.resource;
import static org.junit.Assert.*;

import static org.hamcrest.Matchers.*;

@RunWith(Parameterized.class)
public class AbstractPigSearchJsonTest extends AbstractPigTests {

    private static int testInstance = 0;
    private static String previousQuery;
    private boolean readMetadata;
    private final OpenSearchMajorVersion VERSION = TestUtils.getOpenSearchClusterInfo().getMajorVersion();
    private static Configuration testConfiguration = HdpBootstrap.hadoopConfig();
    private static String workingDir = HadoopCfgUtils.isLocal(testConfiguration)
            ? Paths.get("").toAbsolutePath().toString()
            : "/";

    @ClassRule
    public static LazyTempFolder tempFolder = new LazyTempFolder();

    @Parameters
    public static Collection<Object[]> queries() {
        return new QueryTestParams(tempFolder).params();
    }

    private final String query;

    public AbstractPigSearchJsonTest(String query, boolean metadata) {
        this.query = query;
        this.readMetadata = metadata;

        if (!query.equals(previousQuery)) {
            previousQuery = query;
            testInstance++;
        }
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        // we do this just here since the configuration doesn't get used in Pig scripts.
        new QueryTestParams(tempFolder).provisionQueries(AbstractPigTests.testConfiguration);
    }

    @Before
    public void before() throws Exception {
        RestUtils.refresh("json-pig*");
    }

    @Test
    public void testTuple() throws Exception {
        String script = "DEFINE OpenSearchStorage org.opensearch.hadoop.pig.OpenSearchStorage('opensearch.query="
                + query + "','opensearch.read.metadata=" + readMetadata
                + "','opensearch.output.json=true');" +
                "A = LOAD '" + resource("json-pig-tupleartists", "data", VERSION) + "' USING OpenSearchStorage() AS (name:chararray);" +
                "X = LIMIT A 3;" +
                // "DESCRIBE A;";
                "STORE A INTO '" + tmpPig() + "/testtuple';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testtuple");

        LogFactory.getLog(AbstractPigSearchJsonTest.class).info("Search JSON results " + results);

        // remove time itself
        assertThat(results, containsString("1"));
        assertThat(results, containsString("2"));
        assertThat(results, containsString("3"));
    }

    @Test
    public void testTupleWithSchema() throws Exception {
        String script = "DEFINE OpenSearchStorage org.opensearch.hadoop.pig.OpenSearchStorage('opensearch.query="
                + query + "','opensearch.read.metadata=" + readMetadata
                + "','opensearch.output.json=true');" +
                "A = LOAD '" + resource("json-pig-tupleartists", "data", VERSION)
                + "' USING OpenSearchStorage() AS (name:chararray);" +
                "B = ORDER A BY name DESC;" +
                "X = LIMIT B 3;" +
                "STORE B INTO '" + tmpPig() + "/testtupleschema';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testtupleschema");
        assertThat(results, containsString("999"));
        assertThat(results, containsString("12"));
        assertThat(results, containsString("230"));
    }

    @Test
    public void testMissingIndex() throws Exception {
        String script = "DEFINE OpenSearchStorage org.opensearch.hadoop.pig.OpenSearchStorage('opensearch.index.read.missing.as.empty=true','opensearch.query="
                + query + "','opensearch.read.metadata=" + readMetadata + "','opensearch.output.json=true');" +
                "A = LOAD '" + resource("foo", "bar", VERSION) + "' USING OpenSearchStorage();"
                + "X = LIMIT A 3;"
                + "STORE A INTO '" + tmpPig() + "/testmissingindex';";
        pig.executeScript(script);

        String results = getResults("" + tmpPig() + "/testmissingindex");
        assertThat(results.length(), is(0));
    }

    @Test
    public void testDynamicPattern() throws Exception {
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-1", "data", VERSION)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-5", "data", VERSION)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-9", "data", VERSION)));
    }

    @Test
    public void testDynamicPatternFormat() throws Exception {
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-format-2001-10-06", "data", VERSION)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-format-2005-10-06", "data", VERSION)));
        Assert.assertTrue(RestUtils.exists(resource("json-pig-pattern-format-2017-10-06", "data", VERSION)));
    }

    private static String tmpPig() {
        return new Path("tmp-pig/search-json-" + testInstance)
                .makeQualified(FileSystem.getDefaultUri(AbstractPigTests.testConfiguration), new Path(workingDir))
                .toUri()
                .toString();
    }
}