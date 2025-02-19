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
package org.opensearch.hadoop.integration.hive;

import java.io.IOException;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.opensearch.hadoop.fs.HdfsUtils;
import org.opensearch.hadoop.HdpBootstrap;
import org.opensearch.hadoop.fixtures.LocalOpenSearch;
import org.opensearch.hadoop.Provisioner;
import org.opensearch.hadoop.util.StringUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.ChainedExternalResource;
import org.junit.rules.ExternalResource;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({ AbstractHiveSaveTest.class, AbstractHiveSaveJsonTest.class, AbstractHiveSearchTest.class, AbstractHiveSearchJsonTest.class, AbstractHiveReadJsonTest.class, AbstractHiveExtraTests.class})
//@Suite.SuiteClasses({ AbstractHiveSaveJsonTest.class, AbstractHiveSearchJsonTest.class })
//@Suite.SuiteClasses({ AbstractHiveSaveJsonTest.class, AbstractHiveReadJsonTest.class })
//@Suite.SuiteClasses({ AbstractHiveSaveTest.class })
//@Suite.SuiteClasses({ AbstractHiveExtraTests.class })
public class HiveSuite {

    static Log LOG = LogFactory.getLog(HiveSuite.class);

    static HiveInstance server;
    static boolean isLocal = true;

    static String cleanDdl = "DROP DATABASE IF EXISTS test CASCADE";
    static String createDB = "CREATE DATABASE test";
    static String useDB = "USE test";

    static String originalResource;
    static String originalJsonResource;
    static String hdfsResource;
    static String hdfsJsonResource;
    static String hdfsOpenSearchLib;
    static Configuration hadoopConfig;


    static {
        try {
            originalResource = HiveSuite.class.getClassLoader().getResource("hive-compound.dat").toURI().toString();
            hdfsResource = originalResource;

            originalJsonResource = HiveSuite.class.getClassLoader().getResource("hive-compound.json").toURI().toString();
            hdfsJsonResource = originalJsonResource;

        } catch (Exception ex) {
            throw new RuntimeException("Cannot find resource", ex);
        }
    }

    public static ExternalResource hive = new ExternalResource() {
        @Override
        protected void before() throws Throwable {
            //Properties props = HdpBootstrap.asProperties(QueryTestParams.provisionQueries(HdpBootstrap.hadoopConfig()));
            Properties props = HdpBootstrap.asProperties(HdpBootstrap.hadoopConfig());
            String hive = props.getProperty("hive", "local");

            isLocal = "local".equals(hive);
            LOG.info("Executing hive tests against " + (isLocal ? "embedded hiveserver" : "jdbc connection"));
            server = (isLocal ? new HiveEmbeddedServer2(props) : new HiveJdbc(hive));
            server.start();

            if (isLocal) {
                HdfsUtils.copyFromLocal(Provisioner.OPENSEARCHHADOOP_TESTING_JAR, Provisioner.HDFS_OPENSEARCH_HDP_LIB);
                String jar = "ADD JAR " + HdfsUtils.qualify(Provisioner.HDFS_OPENSEARCH_HDP_LIB, HdpBootstrap.hadoopConfig());
                server.execute(jar);
            }
            server.execute(cleanDdl);
            server.execute(createDB);
            server.execute(useDB);
        }

        @Override
        protected void after() {
            try {
                server.execute(cleanDdl);
                server.stop();
            } catch (Exception ex) {
            }
        }
    };

    @ClassRule
    public static ExternalResource resource = new ChainedExternalResource(new LocalOpenSearch(), hive);

    @SuppressWarnings("deprecation")
    @BeforeClass
    public static void setup() throws Exception {
        if (!isLocal) {
            hadoopConfig = HdpBootstrap.hadoopConfig();

            HdfsUtils.copyFromLocal(Provisioner.OPENSEARCHHADOOP_TESTING_JAR, Provisioner.HDFS_OPENSEARCH_HDP_LIB);
            hdfsOpenSearchLib = HdfsUtils.qualify(Provisioner.HDFS_OPENSEARCH_HDP_LIB, hadoopConfig);
            // copy jar to DistributedCache
            try {
                DistributedCache.addArchiveToClassPath(new Path(Provisioner.HDFS_OPENSEARCH_HDP_LIB), hadoopConfig);
            } catch (IOException ex) {
                throw new RuntimeException("Cannot provision Hive", ex);
            }

            hdfsResource = "/eshdp/hive/hive-compund.dat";
            HdfsUtils.copyFromLocal(originalResource, hdfsResource);
            hdfsResource = HdfsUtils.qualify(hdfsResource, hadoopConfig);

            hdfsJsonResource = "/eshdp/hive/hive-compund.json";
            HdfsUtils.copyFromLocal(originalResource, hdfsJsonResource);
            hdfsJsonResource = HdfsUtils.qualify(hdfsJsonResource, hadoopConfig);
        }
    }


    public static String tableProps(String resource, String query, String... params) {
        StringBuilder sb = new StringBuilder("STORED BY 'org.opensearch.hadoop.hive.OpenSearchStorageHandler' ");

        sb.append("TBLPROPERTIES('opensearch.resource'='" + resource + "'");

        if (StringUtils.hasText(query)) {
            sb.append(",'opensearch.query'='" + query + "'");
        }

        if (params != null && params.length > 0) {
            for (String string : params) {
                sb.append(",");
                sb.append(string);
            }
        }

        if (!isLocal) {
            String host = hadoopConfig.get("opensearch.host");
            if (StringUtils.hasText(host)) {
                sb.append(",'opensearch.host'='" + host + "'");
            }
            String port = hadoopConfig.get("opensearch.port");
            sb.append(",'opensearch.port'='" + port + "'");
        }

        sb.append(")");
        return sb.toString();
    }

    public static void before() throws Exception {
        provisionOpenSearchLib();
    }

    public static void after() throws Exception {
        if (server instanceof HiveEmbeddedServer2) {
            ((HiveEmbeddedServer2) server).removeESSettings();
        }
    }

    public static void provisionOpenSearchLib() throws Exception {
        // provision on each test run since LOAD DATA _moves_ the file
        if (!isLocal) {
            hdfsResource = "/eshdp/hive/hive-compund.dat";
            HdfsUtils.copyFromLocal(originalResource, hdfsResource);

            hdfsJsonResource = "/eshdp/hive/hive-compund.json";
            HdfsUtils.copyFromLocal(originalJsonResource, hdfsJsonResource);
        }

        String jar = "ADD JAR " + HiveSuite.hdfsOpenSearchLib;

        if (!isLocal) {
            System.out.println(server.execute(jar));
        }
    }
}