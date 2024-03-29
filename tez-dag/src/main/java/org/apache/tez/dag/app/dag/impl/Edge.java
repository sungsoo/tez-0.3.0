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

package org.apache.tez.dag.app.dag.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.tez.dag.api.EdgeManager;
import org.apache.tez.dag.api.EdgeManagerContext;
import org.apache.tez.dag.api.EdgeManagerDescriptor;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.EdgeProperty.DataMovementType;
import org.apache.tez.dag.app.dag.Task;
import org.apache.tez.dag.app.dag.Vertex;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventOutputFailed;
import org.apache.tez.dag.app.dag.event.TaskEventAddTezEvent;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.dag.records.TezTaskID;
import org.apache.tez.runtime.RuntimeUtils;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.events.CompositeDataMovementEvent;
import org.apache.tez.runtime.api.events.DataMovementEvent;
import org.apache.tez.runtime.api.events.InputFailedEvent;
import org.apache.tez.runtime.api.events.InputReadErrorEvent;
import org.apache.tez.runtime.api.impl.EventMetaData;
import org.apache.tez.runtime.api.impl.InputSpec;
import org.apache.tez.runtime.api.impl.OutputSpec;
import org.apache.tez.runtime.api.impl.TezEvent;
import org.apache.tez.runtime.api.impl.EventMetaData.EventProducerConsumerType;

import com.google.common.collect.Maps;

public class Edge {

  static class EdgeManagerContextImpl implements EdgeManagerContext {

    private final String srcVertexName;
    private final String destVertexName;
    private final byte[] userPayload;

    EdgeManagerContextImpl(String srcVertexName, String destVertexName, byte[] userPayload) {
      this.srcVertexName = srcVertexName;
      this.destVertexName = destVertexName;
      this.userPayload = userPayload;
    }

    @Override
    public byte[] getUserPayload() {
      return userPayload;
    }

    @Override
    public String getSrcVertexName() {
      return srcVertexName;
    }

    @Override
    public String getDestVertexName() {
      return destVertexName;
    }
  }

  private EdgeProperty edgeProperty;
  private EdgeManagerContext edgeManagerContext;
  private EdgeManager edgeManager;
  @SuppressWarnings("rawtypes")
  private EventHandler eventHandler;
  private AtomicBoolean bufferEvents = new AtomicBoolean(false);
  private List<TezEvent> destinationEventBuffer = new ArrayList<TezEvent>();
  private List<TezEvent> sourceEventBuffer = new ArrayList<TezEvent>();
  private Vertex sourceVertex;
  private Vertex destinationVertex; // this may end up being a list for shared edge
  private EventMetaData destinationMetaInfo;

  @SuppressWarnings("rawtypes")
  public Edge(EdgeProperty edgeProperty, EventHandler eventHandler) {
    this.edgeProperty = edgeProperty;
    this.eventHandler = eventHandler;
    createEdgeManager();
  }

  private void createEdgeManager() {
    switch (edgeProperty.getDataMovementType()) {
      case ONE_TO_ONE:
        edgeManager = new OneToOneEdgeManager();
        break;
      case BROADCAST:
        edgeManager = new BroadcastEdgeManager();
        break;
      case SCATTER_GATHER:
        edgeManager = new ScatterGatherEdgeManager();
        break;
      case CUSTOM:
        String edgeManagerClassName = edgeProperty.getEdgeManagerDescriptor().getClassName();
        edgeManager = RuntimeUtils.createClazzInstance(edgeManagerClassName);
        break;
      default:
        String message = "Unknown edge data movement type: "
            + edgeProperty.getDataMovementType();
        throw new TezUncheckedException(message);
    }
  }

  public void initialize() {
    byte[] bb = null;
    if (edgeProperty.getDataMovementType() == DataMovementType.CUSTOM) {
      bb = edgeProperty.getEdgeManagerDescriptor().getUserPayload();
    }
    edgeManagerContext = new EdgeManagerContextImpl(sourceVertex.getName(),
        destinationVertex.getName(), bb);
    edgeManager.initialize(edgeManagerContext);
    destinationMetaInfo = new EventMetaData(EventProducerConsumerType.INPUT, 
        destinationVertex.getName(), 
        sourceVertex.getName(), 
        null);
  }

  public synchronized void setCustomEdgeManager(EdgeManagerDescriptor descriptor) {
    EdgeProperty modifiedEdgeProperty =
        new EdgeProperty(descriptor,
            edgeProperty.getDataSourceType(),
            edgeProperty.getSchedulingType(),
            edgeProperty.getEdgeSource(),
            edgeProperty.getEdgeDestination());
    this.edgeProperty = modifiedEdgeProperty;
    createEdgeManager();
    initialize();
  }

  public EdgeProperty getEdgeProperty() {
    return this.edgeProperty;
  }
  
  public EdgeManager getEdgeManager() {
    return this.edgeManager;
  }

  public void setSourceVertex(Vertex sourceVertex) {
    if (this.sourceVertex != null && this.sourceVertex != sourceVertex) {
      throw new TezUncheckedException("Source vertex exists: "
          + sourceVertex.getName());
    }
    this.sourceVertex = sourceVertex;
  }

  public void setDestinationVertex(Vertex destinationVertex) {
    if (this.destinationVertex != null
        && this.destinationVertex != destinationVertex) {
      throw new TezUncheckedException("Destination vertex exists: "
          + destinationVertex.getName());
    }
    this.destinationVertex = destinationVertex;
  }

  public InputSpec getDestinationSpec(int destinationTaskIndex) {
    return new InputSpec(sourceVertex.getName(),
        edgeProperty.getEdgeDestination(),
        edgeManager.getNumDestinationTaskPhysicalInputs(sourceVertex.getTotalTasks(),
            destinationTaskIndex));
  }

  public OutputSpec getSourceSpec(int sourceTaskIndex) {
    return new OutputSpec(destinationVertex.getName(),
        edgeProperty.getEdgeSource(), edgeManager.getNumSourceTaskPhysicalOutputs(
        destinationVertex.getTotalTasks(), sourceTaskIndex));
  }
  
  public void startEventBuffering() {
    bufferEvents.set(true);
  }
  
  public void stopEventBuffering() {
    // assume only 1 entity will start and stop event buffering
    bufferEvents.set(false);
    for(TezEvent event : destinationEventBuffer) {
      sendTezEventToDestinationTasks(event);
    }
    destinationEventBuffer.clear();
    for(TezEvent event : sourceEventBuffer) {
      sendTezEventToSourceTasks(event);
    }
    sourceEventBuffer.clear();
  }
  
  @SuppressWarnings("unchecked")
  public void sendTezEventToSourceTasks(TezEvent tezEvent) {
    if (!bufferEvents.get()) {
      switch (tezEvent.getEventType()) {
      case INPUT_READ_ERROR_EVENT:
        InputReadErrorEvent event = (InputReadErrorEvent) tezEvent.getEvent();
        TezTaskAttemptID destAttemptId = tezEvent.getSourceInfo()
            .getTaskAttemptID();
        int destTaskIndex = destAttemptId.getTaskID().getId();
        int srcTaskIndex = edgeManager.routeInputErrorEventToSource(event,
            destTaskIndex);
        int numConsumers = edgeManager.getNumDestinationConsumerTasks(
            srcTaskIndex, destinationVertex.getTotalTasks());
        Task srcTask = sourceVertex.getTask(srcTaskIndex);
        if (srcTask == null) {
          throw new TezUncheckedException("Unexpected null task." +
              " sourceVertex=" + sourceVertex.getVertexId() +
              " srcIndex = " + srcTaskIndex +
              " destAttemptId=" + destAttemptId +
              " destIndex=" + destTaskIndex + 
              " edgeManager=" + edgeManager.getClass().getName());
        }
        TezTaskID srcTaskId = srcTask.getTaskId();
        int taskAttemptIndex = event.getVersion();
        TezTaskAttemptID srcTaskAttemptId = TezTaskAttemptID.getInstance(srcTaskId,
            taskAttemptIndex);
        eventHandler.handle(new TaskAttemptEventOutputFailed(srcTaskAttemptId,
            tezEvent, numConsumers));
        break;
      default:
        throw new TezUncheckedException("Unhandled tez event type: "
            + tezEvent.getEventType());
      }
    } else {
      sourceEventBuffer.add(tezEvent);
    }
  }
  

  private void handleCompositeDataMovementEvent(TezEvent tezEvent) {
    CompositeDataMovementEvent compEvent = (CompositeDataMovementEvent) tezEvent.getEvent();
    EventMetaData srcInfo = tezEvent.getSourceInfo();
    
    for (DataMovementEvent dmEvent : compEvent.getEvents()) {
      TezEvent newEvent = new TezEvent(dmEvent, srcInfo);
      sendTezEventToDestinationTasks(newEvent);
    }
  }
  
  void sendDmEventOrIfEventToTasks(TezEvent tezEvent, int srcTaskIndex,
      boolean isDataMovementEvent,
      Map<Integer, List<Integer>> ifInputIndicesToTaskIndices) {
    int num = 0;
    Event event = tezEvent.getEvent();
    for (Map.Entry<Integer, List<Integer>> entry : ifInputIndicesToTaskIndices.entrySet()) {
      ++num;
      TezEvent tezEventToSend = null;
      if (num == ifInputIndicesToTaskIndices.size()) {
        if (isDataMovementEvent) {
          ((DataMovementEvent) event).setTargetIndex(entry.getKey().intValue());
        } else {
          ((InputFailedEvent) event).setTargetIndex(entry.getKey().intValue());
        }
        tezEventToSend = tezEvent;
      } else {
        Event e;
        if (isDataMovementEvent) {
          DataMovementEvent dmEvent = (DataMovementEvent) event;
          e = new DataMovementEvent(dmEvent.getSourceIndex(), 
              entry.getKey().intValue(), dmEvent.getVersion(), dmEvent.getUserPayload());
        } else {
          InputFailedEvent ifEvent = ((InputFailedEvent) event);
          e = new InputFailedEvent(entry.getKey().intValue(), ifEvent.getVersion());
        }
        tezEventToSend = new TezEvent(e, tezEvent.getSourceInfo());
      }
      tezEventToSend.setDestinationInfo(destinationMetaInfo);
      for(Integer destTaskIndex : entry.getValue()) {
        Task destTask = destinationVertex.getTask(destTaskIndex);
        if (destTask == null) {
          throw new TezUncheckedException("Unexpected null task." +
              " sourceVertex=" + sourceVertex.getVertexId() +
              " srcIndex = " + srcTaskIndex +
              " destAttemptId=" + destinationVertex.getVertexId() +
              " destIndex=" + destTaskIndex + 
              " edgeManager=" + edgeManager.getClass().getName());
        }
        TezTaskID destTaskId = destTask.getTaskId();
        sendEventToTask(destTaskId, tezEventToSend);
      }
    }
  }
  
  public void sendTezEventToDestinationTasks(TezEvent tezEvent) {
    if (!bufferEvents.get()) {
      boolean isDataMovementEvent = true;
      switch (tezEvent.getEventType()) {
      case COMPOSITE_DATA_MOVEMENT_EVENT:
        handleCompositeDataMovementEvent(tezEvent);
        break;
      case INPUT_FAILED_EVENT:
        isDataMovementEvent = false;
        // fall through
      case DATA_MOVEMENT_EVENT:
        Map<Integer, List<Integer>> inputIndicesToTaskIndices = Maps
        .newHashMap();
        TezTaskAttemptID srcAttemptId = tezEvent.getSourceInfo()
            .getTaskAttemptID();
        int srcTaskIndex = srcAttemptId.getTaskID().getId();
        if (isDataMovementEvent) {
          DataMovementEvent dmEvent = (DataMovementEvent)tezEvent.getEvent();
          edgeManager.routeDataMovementEventToDestination(dmEvent,
                srcTaskIndex, destinationVertex.getTotalTasks(),
                inputIndicesToTaskIndices);
        } else {
          edgeManager.routeInputSourceTaskFailedEventToDestination(srcTaskIndex,
              destinationVertex.getTotalTasks(), inputIndicesToTaskIndices);
        }
        if (!inputIndicesToTaskIndices.isEmpty()) {
          sendDmEventOrIfEventToTasks(tezEvent, srcTaskIndex, isDataMovementEvent, inputIndicesToTaskIndices);
        } else {
          throw new TezUncheckedException("Event must be routed." +
              " sourceVertex=" + sourceVertex.getVertexId() +
              " srcIndex = " + srcTaskIndex +
              " destAttemptId=" + destinationVertex.getVertexId() +
              " edgeManager=" + edgeManager.getClass().getName() + 
              " Event type=" + tezEvent.getEventType());
        }
        break;
      default:
        throw new TezUncheckedException("Unhandled tez event type: "
            + tezEvent.getEventType());
      }
    } else {
      destinationEventBuffer.add(tezEvent);
    }
  }
  
  @SuppressWarnings("unchecked")
  private void sendEventToTask(TezTaskID taskId, TezEvent tezEvent) {
    eventHandler.handle(new TaskEventAddTezEvent(taskId, tezEvent));
  }

  public String getSourceVertexName() {
    return this.sourceVertex.getName();
  }

  public String getDestinationVertexName() {
    return this.destinationVertex.getName();
  }

}
