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

package org.apache.tez.dag.app.rm.container;

import org.apache.hadoop.yarn.api.records.ContainerStatus;

public class AMContainerEventCompleted extends AMContainerEvent {

  private final ContainerStatus containerStatus;
  private final boolean isPreempted;

  public AMContainerEventCompleted(ContainerStatus containerStatus) {
    this(containerStatus, false);
  }
  
  public AMContainerEventCompleted(ContainerStatus containerStatus, 
      boolean isPreempted) {
    super(containerStatus.getContainerId(), AMContainerEventType.C_COMPLETED);
    this.containerStatus = containerStatus;
    this.isPreempted = isPreempted;
  }

  public ContainerStatus getContainerStatus() {
    return this.containerStatus;
  }
  
  public boolean isPreempted() {
    return isPreempted;
  }

}
