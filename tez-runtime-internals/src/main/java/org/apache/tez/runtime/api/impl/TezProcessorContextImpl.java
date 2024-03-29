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

package org.apache.tez.runtime.api.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.tez.common.counters.TezCounters;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.runtime.InputReadyTracker;
import org.apache.tez.runtime.RuntimeTask;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.Input;
import org.apache.tez.runtime.api.TezProcessorContext;
import org.apache.tez.runtime.api.impl.EventMetaData.EventProducerConsumerType;
import org.apache.tez.runtime.common.resources.MemoryDistributor;

public class TezProcessorContextImpl extends TezTaskContextImpl implements TezProcessorContext {

  private static final Log LOG = LogFactory.getLog(TezProcessorContextImpl.class);
  
  private final byte[] userPayload;
  private final EventMetaData sourceInfo;
  private final InputReadyTracker inputReadyTracker;

  public TezProcessorContextImpl(Configuration conf, int appAttemptNumber,
      TezUmbilical tezUmbilical, String vertexName,
      TezTaskAttemptID taskAttemptID, TezCounters counters,
      byte[] userPayload, RuntimeTask runtimeTask,
      Map<String, ByteBuffer> serviceConsumerMetadata,
      Map<String, String> auxServiceEnv, MemoryDistributor memDist,
      ProcessorDescriptor processorDescriptor, InputReadyTracker inputReadyTracker) {
    super(conf, appAttemptNumber, vertexName, taskAttemptID,
        counters, runtimeTask, tezUmbilical, serviceConsumerMetadata,
        auxServiceEnv, memDist, processorDescriptor);
    this.userPayload = userPayload;
    this.sourceInfo = new EventMetaData(EventProducerConsumerType.PROCESSOR,
        taskVertexName, "", taskAttemptID);
    this.inputReadyTracker = inputReadyTracker;
  }

  @Override
  public void sendEvents(List<Event> events) {
    List<TezEvent> tezEvents = new ArrayList<TezEvent>(events.size());
    for (Event e : events) {
      TezEvent tEvt = new TezEvent(e, sourceInfo);
      tezEvents.add(tEvt);
    }
    tezUmbilical.addEvents(tezEvents);
  }

  @Override
  public byte[] getUserPayload() {
    return userPayload;
  }

  @Override
  public void setProgress(float progress) {
    runtimeTask.setProgress(progress);
  }

  @Override
  public void fatalError(Throwable exception, String message) {
    super.signalFatalError(exception, message, sourceInfo);
  }

  @Override
  public boolean canCommit() throws IOException {
    return tezUmbilical.canCommit(this.taskAttemptID);
  }

  @Override
  public Input waitForAnyInputReady(Collection<Input> inputs) throws InterruptedException {
    return inputReadyTracker.waitForAnyInputReady(inputs);
  }

  @Override
  public void waitForAllInputsReady(Collection<Input> inputs) throws InterruptedException {
    inputReadyTracker.waitForAllInputsReady(inputs);
  }
}
