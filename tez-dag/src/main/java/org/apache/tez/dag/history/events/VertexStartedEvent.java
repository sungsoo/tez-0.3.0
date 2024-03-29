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
import org.apache.tez.dag.history.ats.EntityTypes;
import org.apache.tez.dag.history.utils.ATSConstants;
import org.apache.tez.dag.records.TezVertexID;
import org.apache.tez.dag.recovery.records.RecoveryProtos.VertexStartedProto;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class VertexStartedEvent implements HistoryEvent {

  private TezVertexID vertexID;
  private long startRequestedTime;
  private long startTime;

  public VertexStartedEvent() {
  }

  public VertexStartedEvent(TezVertexID vertexId,
      long startRequestedTime, long startTime) {
    this.vertexID = vertexId;
    this.startRequestedTime = startRequestedTime;
    this.startTime = startTime;
  }

  @Override
  public HistoryEventType getEventType() {
    return HistoryEventType.VERTEX_STARTED;
  }

  @Override
  public JSONObject convertToATSJSON() throws JSONException {
    JSONObject jsonObject = new JSONObject();
    jsonObject.put(ATSConstants.ENTITY, vertexID.toString());
    jsonObject.put(ATSConstants.ENTITY_TYPE, EntityTypes.TEZ_VERTEX_ID.name());

    // Related entities
    JSONArray relatedEntities = new JSONArray();
    JSONObject vertexEntity = new JSONObject();
    vertexEntity.put(ATSConstants.ENTITY, vertexID.getDAGId().toString());
    vertexEntity.put(ATSConstants.ENTITY_TYPE, EntityTypes.TEZ_DAG_ID.name());
    relatedEntities.put(vertexEntity);
    jsonObject.put(ATSConstants.RELATED_ENTITIES, relatedEntities);

    // Events
    JSONArray events = new JSONArray();
    JSONObject startEvent = new JSONObject();
    startEvent.put(ATSConstants.TIMESTAMP, startTime);
    startEvent.put(ATSConstants.EVENT_TYPE,
        HistoryEventType.VERTEX_STARTED.name());
    events.put(startEvent);
    jsonObject.put(ATSConstants.EVENTS, events);

    // Other info
    // TODO fix requested times to be events
    JSONObject otherInfo = new JSONObject();
    otherInfo.put(ATSConstants.START_REQUESTED_TIME, startRequestedTime);
    otherInfo.put(ATSConstants.START_TIME, startTime);
    jsonObject.put(ATSConstants.OTHER_INFO, otherInfo);

    return jsonObject;
  }

  @Override
  public boolean isRecoveryEvent() {
    return false;
  }

  @Override
  public boolean isHistoryEvent() {
    return true;
  }

  public VertexStartedProto toProto() {
    return VertexStartedProto.newBuilder()
        .setVertexId(vertexID.toString())
        .setStartRequestedTime(startRequestedTime)
        .setStartTime(startTime)
        .build();
  }

  public void fromProto(VertexStartedProto proto) {
    this.vertexID = TezVertexID.fromString(proto.getVertexId());
    this.startRequestedTime = proto.getStartRequestedTime();
    this.startTime = proto.getStartTime();
  }

  @Override
  public void toProtoStream(OutputStream outputStream) throws IOException {
    toProto().writeDelimitedTo(outputStream);
  }

  @Override
  public void fromProtoStream(InputStream inputStream) throws IOException {
    VertexStartedProto proto = VertexStartedProto.parseDelimitedFrom(inputStream);
    fromProto(proto);
  }

  @Override
  public String toString() {
    return "vertexId=" + vertexID
        + ", startRequestedTime=" + startRequestedTime
        + ", startedTime=" + startTime;
  }

}
