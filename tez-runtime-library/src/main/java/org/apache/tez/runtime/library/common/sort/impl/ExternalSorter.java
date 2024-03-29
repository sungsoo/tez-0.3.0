/**
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

package org.apache.tez.runtime.library.common.sort.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.util.IndexedSorter;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.common.counters.TaskCounter;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.runtime.api.MemoryUpdateCallback;
import org.apache.tez.runtime.api.TezOutputContext;
import org.apache.tez.runtime.library.api.Partitioner;
import org.apache.tez.runtime.library.common.ConfigUtils;
import org.apache.tez.runtime.library.common.TezRuntimeUtils;
import org.apache.tez.runtime.library.common.combine.Combiner;
import org.apache.tez.runtime.library.common.shuffle.impl.ShuffleHeader;
import org.apache.tez.runtime.library.common.sort.impl.IFile.Writer;
import org.apache.tez.runtime.library.common.task.local.output.TezTaskOutput;
import org.apache.tez.runtime.library.hadoop.compat.NullProgressable;

import com.google.common.base.Preconditions;

@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class ExternalSorter implements MemoryUpdateCallback {

  private static final Log LOG = LogFactory.getLog(ExternalSorter.class);

  public abstract void close() throws IOException;

  public abstract void flush() throws IOException;

  public abstract void write(Object key, Object value) throws IOException;
  
  private int initialMemRequestMb;
  protected Progressable nullProgressable = new NullProgressable();
  protected TezOutputContext outputContext;
  protected Combiner combiner;
  protected Partitioner partitioner;
  protected Configuration conf;
  protected FileSystem rfs;
  protected TezTaskOutput mapOutputFile;
  protected int partitions;
  protected Class keyClass;
  protected Class valClass;
  protected RawComparator comparator;
  protected SerializationFactory serializationFactory;
  protected Serializer keySerializer;
  protected Serializer valSerializer;
  
  protected boolean ifileReadAhead;
  protected int ifileReadAheadLength;
  protected int ifileBufferSize;

  protected volatile int availableMemoryMb;

  protected IndexedSorter sorter;

  // Compression for map-outputs
  protected CompressionCodec codec;

  // Counters
  // TODO TEZ Rename all counter variables [Mapping of counter to MR for compatibility in the MR layer]
  protected TezCounter mapOutputByteCounter;
  protected TezCounter mapOutputRecordCounter;
  protected TezCounter fileOutputByteCounter;
  protected TezCounter spilledRecordsCounter;

  @Private
  public void initialize(TezOutputContext outputContext, Configuration conf, int numOutputs) throws IOException {
    this.outputContext = outputContext;
    this.conf = conf;
    this.partitions = numOutputs;

    rfs = ((LocalFileSystem)FileSystem.getLocal(this.conf)).getRaw();

    initialMemRequestMb = 
        this.conf.getInt(
            TezJobConfig.TEZ_RUNTIME_IO_SORT_MB, 
            TezJobConfig.DEFAULT_TEZ_RUNTIME_IO_SORT_MB);
    Preconditions.checkArgument(initialMemRequestMb != 0, "io.sort.mb should be larger than 0");
    long reqBytes = initialMemRequestMb << 20;
    outputContext.requestInitialMemory(reqBytes, this);
    LOG.info("Requested SortBufferSize (io.sort.mb): " + initialMemRequestMb);

    // sorter
    sorter = ReflectionUtils.newInstance(this.conf.getClass(
        TezJobConfig.TEZ_RUNTIME_INTERNAL_SORTER_CLASS, QuickSort.class,
        IndexedSorter.class), this.conf);

    comparator = ConfigUtils.getIntermediateOutputKeyComparator(this.conf);

    // k/v serialization
    keyClass = ConfigUtils.getIntermediateOutputKeyClass(this.conf);
    valClass = ConfigUtils.getIntermediateOutputValueClass(this.conf);
    serializationFactory = new SerializationFactory(this.conf);
    keySerializer = serializationFactory.getSerializer(keyClass);
    valSerializer = serializationFactory.getSerializer(valClass);

    //    counters
    mapOutputByteCounter =
        outputContext.getCounters().findCounter(TaskCounter.MAP_OUTPUT_BYTES);
    mapOutputRecordCounter =
        outputContext.getCounters().findCounter(TaskCounter.MAP_OUTPUT_RECORDS);
    fileOutputByteCounter =
        outputContext.getCounters().findCounter(TaskCounter.MAP_OUTPUT_MATERIALIZED_BYTES);
    spilledRecordsCounter =
        outputContext.getCounters().findCounter(TaskCounter.SPILLED_RECORDS);
    // compression
    if (ConfigUtils.shouldCompressIntermediateOutput(this.conf)) {
      Class<? extends CompressionCodec> codecClass =
          ConfigUtils.getIntermediateOutputCompressorClass(this.conf, DefaultCodec.class);
      codec = ReflectionUtils.newInstance(codecClass, this.conf);
    } else {
      codec = null;
    }

    this.ifileReadAhead = this.conf.getBoolean(
        TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD,
        TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_DEFAULT);
    if (this.ifileReadAhead) {
      this.ifileReadAheadLength = conf.getInt(
          TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_BYTES,
          TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_BYTES_DEFAULT);
    } else {
      this.ifileReadAheadLength = 0;
    }
    this.ifileBufferSize = conf.getInt("io.file.buffer.size",
        TezJobConfig.TEZ_RUNTIME_IFILE_BUFFER_SIZE_DEFAULT);

    
    // Task outputs
    mapOutputFile = TezRuntimeUtils.instantiateTaskOutputManager(conf, outputContext);
    
    LOG.info("Instantiating Partitioner: [" + conf.get(TezJobConfig.TEZ_RUNTIME_PARTITIONER_CLASS) + "]");
    this.conf.setInt(TezJobConfig.TEZ_RUNTIME_NUM_EXPECTED_PARTITIONS, this.partitions);
    this.partitioner = TezRuntimeUtils.instantiatePartitioner(this.conf);
    this.combiner = TezRuntimeUtils.instantiateCombiner(this.conf, outputContext);
  }
  
  /**
   * Used to start the actual Output. Typically, this involves allocating
   * buffers, starting required threads, etc
   */
  @Private
  public abstract void start() throws Exception;

  /**
   * Exception indicating that the allocated sort buffer is insufficient to hold
   * the current record.
   */
  @SuppressWarnings("serial")
  public static class MapBufferTooSmallException extends IOException {
    public MapBufferTooSmallException(String s) {
      super(s);
    }
  }

  @Private
  public TezTaskOutput getMapOutput() {
    return mapOutputFile;
  }

  protected void runCombineProcessor(TezRawKeyValueIterator kvIter,
      Writer writer) throws IOException {
    try {
      combiner.combine(kvIter, writer);
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  /**
   * Rename srcPath to dstPath on the same volume. This is the same as
   * RawLocalFileSystem's rename method, except that it will not fall back to a
   * copy, and it will create the target directory if it doesn't exist.
   */
  protected void sameVolRename(Path srcPath, Path dstPath) throws IOException {
    RawLocalFileSystem rfs = (RawLocalFileSystem) this.rfs;
    File src = rfs.pathToFile(srcPath);
    File dst = rfs.pathToFile(dstPath);
    if (!dst.getParentFile().exists()) {
      if (!dst.getParentFile().mkdirs()) {
        throw new IOException("Unable to rename " + src + " to " + dst
            + ": couldn't create parent directory");
      }
    }

    if (!src.renameTo(dst)) {
      throw new IOException("Unable to rename " + src + " to " + dst);
    }
  }

  public InputStream getSortedStream(int partition) {
    throw new UnsupportedOperationException("getSortedStream isn't supported!");
  }

  public ShuffleHeader getShuffleHeader(int reduce) {
    throw new UnsupportedOperationException("getShuffleHeader isn't supported!");
  }
  
  @Override
  public void memoryAssigned(long assignedSize) {
    this.availableMemoryMb = (int) (assignedSize >> 20);
    if (this.availableMemoryMb == 0) {
      LOG.warn("AssignedMemoryMB: " + this.availableMemoryMb
          + " is too low. Falling back to initial ask: " + initialMemRequestMb);
      this.availableMemoryMb = initialMemRequestMb;
    }
  }
}
