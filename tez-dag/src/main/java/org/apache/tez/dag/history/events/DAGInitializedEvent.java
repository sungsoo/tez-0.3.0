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

package org.apache.tez.dag.history.events;

import org.apache.tez.dag.history.HistoryEvent;
import org.apache.tez.dag.history.HistoryEventType;
import org.apache.tez.dag.records.TezDAGID;
import org.apache.tez.dag.recovery.records.RecoveryProtos;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// TODO fix class
public class DAGInitializedEvent implements HistoryEvent {

  private TezDAGID dagID;
  private long initTime;

  public DAGInitializedEvent() {
  }

  public DAGInitializedEvent(TezDAGID dagID, long initTime) {
    this.dagID = dagID;
    this.initTime = initTime;
  }

  @Override
  public HistoryEventType getEventType() {
    return HistoryEventType.DAG_INITIALIZED;
  }

  @Override
  public JSONObject convertToATSJSON() throws JSONException {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isRecoveryEvent() {
    return true;
  }

  @Override
  public boolean isHistoryEvent() {
    return true;
  }

  @Override
  public String toString() {
    return "dagID=" + dagID
        + ", initTime=" + initTime;
  }

  public RecoveryProtos.DAGInitializedProto toProto() {
    return RecoveryProtos.DAGInitializedProto.newBuilder()
        .setDagId(dagID.toString())
        .setInitTime(initTime)
        .build();
  }

  public void fromProto(RecoveryProtos.DAGInitializedProto proto) {
    this.dagID = TezDAGID.fromString(proto.getDagId());
    this.initTime = proto.getInitTime();
  }

  @Override
  public void toProtoStream(OutputStream outputStream) throws IOException {
    toProto().writeDelimitedTo(outputStream);
  }

  @Override
  public void fromProtoStream(InputStream inputStream) throws IOException {
    RecoveryProtos.DAGInitializedProto proto =
        RecoveryProtos.DAGInitializedProto.parseDelimitedFrom(inputStream);
    fromProto(proto);
  }

}
