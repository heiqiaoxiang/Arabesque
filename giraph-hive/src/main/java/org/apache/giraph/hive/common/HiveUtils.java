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

package org.apache.giraph.hive.common;

import org.apache.giraph.conf.ImmutableClassesGiraphConfiguration;
import org.apache.giraph.conf.StrConfOption;
import org.apache.giraph.hive.input.edge.HiveToEdge;
import org.apache.giraph.hive.input.vertex.HiveToVertex;
import org.apache.giraph.hive.output.VertexToHive;
import org.apache.giraph.utils.ReflectionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.facebook.hiveio.input.HiveApiInputFormat;
import com.facebook.hiveio.input.HiveInputDescription;
import com.facebook.hiveio.output.HiveApiOutputFormat;
import com.facebook.hiveio.output.HiveOutputDescription;
import com.facebook.hiveio.schema.HiveTableSchema;
import com.facebook.hiveio.schema.HiveTableSchemas;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.System.getenv;
import static org.apache.giraph.hive.common.GiraphHiveConstants.HIVE_EDGE_INPUT;
import static org.apache.giraph.hive.common.GiraphHiveConstants.HIVE_VERTEX_INPUT;
import static org.apache.giraph.hive.common.GiraphHiveConstants.VERTEX_TO_HIVE_CLASS;
import static org.apache.giraph.utils.ReflectionUtils.newInstance;

/**
 * Utility methods for Hive IO
 */
public class HiveUtils {
  /** Logger */
  private static final Logger LOG = Logger.getLogger(HiveUtils.class);

  /** Do not instantiate */
  private HiveUtils() {
  }

  /**
   * Initialize hive input, prepare Configuration parameters
   *
   * @param hiveInputFormat HiveApiInputFormat
   * @param inputDescription HiveInputDescription
   * @param profileId profile ID
   * @param conf Configuration
   */
  public static void initializeHiveInput(HiveApiInputFormat hiveInputFormat,
      HiveInputDescription inputDescription, String profileId,
      Configuration conf) {
    hiveInputFormat.setMyProfileId(profileId);
    HiveApiInputFormat.setProfileInputDesc(conf, inputDescription, profileId);
    HiveTableSchema schema = HiveTableSchemas.lookup(conf,
        inputDescription.getTableDesc());
    HiveTableSchemas.put(conf, profileId, schema);
  }

  /**
   * Initialize hive output, prepare Configuration parameters
   *
   * @param hiveOutputFormat HiveApiOutputFormat
   * @param outputDesc HiveOutputDescription
   * @param profileId Profile id
   * @param conf Configuration
   */
  public static void initializeHiveOutput(HiveApiOutputFormat hiveOutputFormat,
      HiveOutputDescription outputDesc, String profileId, Configuration conf) {
    hiveOutputFormat.setMyProfileId(profileId);
    try {
      HiveApiOutputFormat.initProfile(conf, outputDesc, profileId);
    } catch (TException e) {
      throw new IllegalStateException(
          "initializeHiveOutput: TException occurred", e);
    }
    HiveTableSchema schema = HiveTableSchemas.lookup(conf,
        outputDesc.getTableDesc());
    HiveTableSchemas.put(conf, profileId, schema);
  }

  /**
   * @param outputTablePartitionString table partition string
   * @return Map
   */
  public static Map<String, String> parsePartitionValues(
      String outputTablePartitionString) {
    if (outputTablePartitionString == null) {
      return null;
    }
    Splitter commaSplitter = Splitter.on(',').omitEmptyStrings().trimResults();
    Splitter equalSplitter = Splitter.on('=').omitEmptyStrings().trimResults();
    Map<String, String> partitionValues = Maps.newHashMap();
    for (String keyValStr : commaSplitter.split(outputTablePartitionString)) {
      List<String> keyVal = Lists.newArrayList(equalSplitter.split(keyValStr));
      if (keyVal.size() != 2) {
        throw new IllegalArgumentException(
            "Unrecognized partition value format: " +
                outputTablePartitionString);
      }
      partitionValues.put(keyVal.get(0), keyVal.get(1));
    }
    return partitionValues;
  }

  /**
   * Lookup index of column in {@link HiveTableSchema}, or throw if not found.
   *
   * @param schema {@link HiveTableSchema}
   * @param columnName column name
   * @return column index
   */
  public static int columnIndexOrThrow(HiveTableSchema schema,
      String columnName) {
    int index = schema.positionOf(columnName);
    if (index == -1) {
      throw new IllegalArgumentException("Column " + columnName +
          " not found in table " + schema.getTableDesc());
    }
    return index;
  }

  /**
   * Lookup index of column in {@link HiveTableSchema}, or throw if not found.
   *
   * @param schema {@link HiveTableSchema}
   * @param conf {@link Configuration}
   * @param confOption {@link StrConfOption}
   * @return column index
   */
  public static int columnIndexOrThrow(HiveTableSchema schema,
      Configuration conf, StrConfOption confOption) {
    String columnName = confOption.get(conf);
    if (columnName == null) {
      throw new IllegalArgumentException("Column " + confOption.getKey() +
          " not set in configuration");
    }
    return columnIndexOrThrow(schema, columnName);
  }

  /**
   * Add hive-site.xml file to tmpfiles in Configuration.
   *
   * @param conf Configuration
   */
  public static void addHiveSiteXmlToTmpFiles(Configuration conf) {
    // When output partitions are used, workers register them to the
    // metastore at cleanup stage, and on HiveConf's initialization, it
    // looks for hive-site.xml.
    addToHiveFromClassLoader(conf, "hive-site.xml");
  }

  /**
   * Add hive-site-custom.xml to tmpfiles in Configuration.
   *
   * @param conf Configuration
   */
  public static void addHiveSiteCustomXmlToTmpFiles(Configuration conf) {
    addToHiveFromClassLoader(conf, "hive-site-custom.xml");
  }

  /**
   * Add a file to Configuration tmpfiles from ClassLoader resource
   *
   * @param conf Configuration
   * @param name file name
   */
  private static void addToHiveFromClassLoader(Configuration conf,
      String name) {
    URL url = conf.getClassLoader().getResource(name);
    checkNotNull(url);
    if (LOG.isInfoEnabled()) {
      LOG.info("addToHiveFromClassLoader: Adding " + name + " at " +
          url + " to Configuration tmpfiles");
    }
    addToStringCollection(conf, "tmpfiles", url.toString());
  }

  /**
   * Add jars from HADOOP_CLASSPATH environment variable to tmpjars property
   * in Configuration.
   *
   * @param conf Configuration
   */
  public static void addHadoopClasspathToTmpJars(Configuration conf) {
    // Or, more effectively, we can provide all the jars client needed to
    // the workers as well
    String[] hadoopJars = getenv("HADOOP_CLASSPATH").split(File.pathSeparator);
    if (hadoopJars.length > 0) {
      if (LOG.isInfoEnabled()) {
        LOG.info("addHadoopClasspathToTmpJars: Adding HADOOP_CLASSPATH jars " +
            "at " + Arrays.toString(hadoopJars) + " to Configuration tmpjars");
      }
      List<String> hadoopJarURLs = Lists.newArrayList();
      for (String jarPath : hadoopJars) {
        File file = new File(jarPath);
        if (file.exists() && file.isFile()) {
          hadoopJarURLs.add(file.toURI().toString());
        }
      }
      HiveUtils.addToStringCollection(conf, "tmpjars", hadoopJarURLs);
    }
  }

  /**
   * Handle -hiveconf options, adding them to Configuration
   *
   * @param hiveconfArgs array of hiveconf args
   * @param conf Configuration
   */
  public static void processHiveconfOptions(String[] hiveconfArgs,
      Configuration conf) {
    for (String hiveconf : hiveconfArgs) {
      processHiveconfOption(conf, hiveconf);
    }
  }

  /**
   * Process -hiveconf option, adding it to Configuration appropriately.
   *
   * @param conf Configuration
   * @param hiveconf option to process
   */
  public static void processHiveconfOption(Configuration conf,
      String hiveconf) {
    String[] keyval = hiveconf.split("=", 2);
    if (keyval.length == 2) {
      String name = keyval[0];
      String value = keyval[1];
      if (name.equals("tmpjars") || name.equals("tmpfiles")) {
        addToStringCollection(conf, name, value);
      } else {
        conf.set(name, value);
      }
    }
  }

  /**
   * Add string to collection
   *
   * @param conf Configuration
   * @param key key to add
   * @param values values for collection
   */
  public static void addToStringCollection(Configuration conf, String key,
      String... values) {
    addToStringCollection(conf, key, Arrays.asList(values));
  }

  /**
   * Add string to collection
   *
   * @param conf Configuration
   * @param key to add
   * @param values values for collection
   */
  public static void addToStringCollection(
      Configuration conf, String key, Collection<String> values) {
    Collection<String> strings = conf.getStringCollection(key);
    strings.addAll(values);
    conf.setStrings(key, strings.toArray(new String[strings.size()]));
  }

  /**
   * Create a new VertexToHive
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf Configuration
   * @param schema Hive table schema
   * @return VertexToHive
   * @throws IOException on any instantiation errors
   */
  public static <I extends WritableComparable, V extends Writable,
      E extends Writable> VertexToHive<I, V, E> newVertexToHive(
      ImmutableClassesGiraphConfiguration<I, V, E> conf,
      HiveTableSchema schema) throws IOException {
    Class<? extends VertexToHive> klass = VERTEX_TO_HIVE_CLASS.get(conf);
    if (klass == null) {
      throw new IOException(VERTEX_TO_HIVE_CLASS.getKey() +
          " not set in conf");
    }
    VertexToHive<I, V, E> vertexToHive = newInstance(klass, conf);
    HiveTableSchemas.configure(vertexToHive, schema);
    return vertexToHive;
  }

  /**
   * Create a new HiveToEdge
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf Configuration
   * @param schema Hive table schema
   * @return HiveToVertex
   */
  public static <I extends WritableComparable, V extends Writable,
        E extends Writable> HiveToEdge<I, E> newHiveToEdge(
      ImmutableClassesGiraphConfiguration<I, V, E> conf,
      HiveTableSchema schema) {
    Class<? extends HiveToEdge> klass = HIVE_EDGE_INPUT.getClass(conf);
    if (klass == null) {
      throw new IllegalArgumentException(
          HIVE_EDGE_INPUT.getClassOpt().getKey() + " not set in conf");
    }
    HiveToEdge hiveToEdge = ReflectionUtils.newInstance(klass, conf);
    HiveTableSchemas.configure(hiveToEdge, schema);
    return hiveToEdge;
  }

  /**
   * Create a new HiveToVertex
   *
   * @param <I> Vertex ID
   * @param <V> Vertex Value
   * @param <E> Edge Value
   * @param conf Configuration
   * @param schema Hive table schema
   * @return HiveToVertex
   */
  public static <I extends WritableComparable, V extends Writable,
        E extends Writable> HiveToVertex<I, V, E> newHiveToVertex(
      ImmutableClassesGiraphConfiguration<I, V, E> conf,
      HiveTableSchema schema) {
    Class<? extends HiveToVertex> klass = HIVE_VERTEX_INPUT.getClass(conf);
    if (klass == null) {
      throw new IllegalArgumentException(
          HIVE_VERTEX_INPUT.getClassOpt().getKey() + " not set in conf");
    }
    HiveToVertex hiveToVertex = ReflectionUtils.newInstance(klass, conf);
    HiveTableSchemas.configure(hiveToVertex, schema);
    return hiveToVertex;
  }
}
