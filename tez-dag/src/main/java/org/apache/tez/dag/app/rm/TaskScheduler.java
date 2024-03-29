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

package org.apache.tez.dag.app.rm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.RackResolver;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.DAGAppMasterState;
import org.apache.tez.dag.app.rm.TaskScheduler.TaskSchedulerAppCallback.AppFinalStatus;
import org.apache.tez.dag.app.rm.container.ContainerSignatureMatcher;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/* TODO not yet updating cluster nodes on every allocate response
 * from RMContainerRequestor
   import org.apache.tez.dag.app.rm.node.AMNodeEventNodeCountUpdated;
    if (clusterNmCount != lastClusterNmCount) {
      LOG.info("Num cluster nodes changed from " + lastClusterNmCount + " to "
          + clusterNmCount);
      eventHandler.handle(new AMNodeEventNodeCountUpdated(clusterNmCount));
    }
 */
public class TaskScheduler extends AbstractService
                             implements AMRMClientAsync.CallbackHandler {
  private static final Log LOG = LogFactory.getLog(TaskScheduler.class);

  public interface TaskSchedulerAppCallback {
    public class AppFinalStatus {
      public final FinalApplicationStatus exitStatus;
      public final String exitMessage;
      public final String postCompletionTrackingUrl;
      public AppFinalStatus(FinalApplicationStatus exitStatus,
                             String exitMessage,
                             String posCompletionTrackingUrl) {
        this.exitStatus = exitStatus;
        this.exitMessage = exitMessage;
        this.postCompletionTrackingUrl = posCompletionTrackingUrl;
      }
    }
    // upcall to app must be outside locks
    public void taskAllocated(Object task,
                               Object appCookie,
                               Container container);
    // this may end up being called for a task+container pair that the app
    // has not heard about. this can happen because of a race between
    // taskAllocated() upcall and deallocateTask() downcall
    public void containerCompleted(Object taskLastAllocated,
                                    ContainerStatus containerStatus);
    public void containerBeingReleased(ContainerId containerId);
    public void nodesUpdated(List<NodeReport> updatedNodes);
    public void appShutdownRequested();
    public void setApplicationRegistrationData(
                                Resource maxContainerCapability,
                                Map<ApplicationAccessType, String> appAcls,
                                ByteBuffer clientAMSecretKey
                                );
    public void onError(Throwable t);
    public float getProgress();
    public void preemptContainer(ContainerId containerId);
    public AppFinalStatus getFinalAppStatus();
  }

  final TezAMRMClientAsync<CookieContainerRequest> amRmClient;
  final TaskSchedulerAppCallback realAppClient;
  final TaskSchedulerAppCallback appClientDelegate;
  final ContainerSignatureMatcher containerSignatureMatcher;
  ExecutorService appCallbackExecutor;

  // Container Re-Use configuration
  private boolean shouldReuseContainers;
  private boolean reuseRackLocal;
  private boolean reuseNonLocal;

  Map<Object, CookieContainerRequest> taskRequests =
                  new HashMap<Object, CookieContainerRequest>();
  // LinkedHashMap is need in getProgress()
  LinkedHashMap<Object, Container> taskAllocations =
                  new LinkedHashMap<Object, Container>();
  /**
   * Tracks last task assigned to a known container.
   */
  Map<ContainerId, Object> containerAssignments =
                  new HashMap<ContainerId, Object>();
  HashMap<ContainerId, Object> releasedContainers =
                  new HashMap<ContainerId, Object>();
  /**
   * Map of containers currently being held by the TaskScheduler.
   */
  Map<ContainerId, HeldContainer> heldContainers =
      new HashMap<ContainerId, HeldContainer>();
  
  Set<NodeId> blacklistedNodes = Collections
      .newSetFromMap(new ConcurrentHashMap<NodeId, Boolean>());
  
  Resource totalResources = Resource.newInstance(0, 0);
  Resource allocatedResources = Resource.newInstance(0, 0);
  
  final String appHostName;
  final int appHostPort;
  final String appTrackingUrl;
  final AppContext appContext;

  boolean isStopped = false;

  private ContainerAssigner NODE_LOCAL_ASSIGNER = new NodeLocalContainerAssigner();
  private ContainerAssigner RACK_LOCAL_ASSIGNER = new RackLocalContainerAssigner();
  private ContainerAssigner NON_LOCAL_ASSIGNER = new NonLocalContainerAssigner();

  DelayedContainerManager delayedContainerManager;
  long localitySchedulingDelay;
  long sessionDelay;
  
  class CRCookie {
    // Do not use these variables directly. Can caused mocked unit tests to fail.
    private Object task;
    private Object appCookie;
    private Object containerSignature;
    
    CRCookie(Object task, Object appCookie, Object containerSignature) {
      this.task = task;
      this.appCookie = appCookie;
      this.containerSignature = containerSignature;
    }

    Object getTask() {
      return task;
    }

    Object getAppCookie() {
      return appCookie;
    }
    
    Object getContainerSignature() {
      return containerSignature;
    }
  }

  class CookieContainerRequest extends ContainerRequest {
    CRCookie cookie;

    public CookieContainerRequest(
        Resource capability,
        String[] hosts,
        String[] racks,
        Priority priority,
        CRCookie cookie) {
      super(capability, hosts, racks, priority);
      this.cookie = cookie;
    }

    CRCookie getCookie() {
      return cookie;
    }
  }

  public TaskScheduler(TaskSchedulerAppCallback appClient,
                        ContainerSignatureMatcher containerSignatureMatcher,
                        String appHostName,
                        int appHostPort,
                        String appTrackingUrl,
                        AppContext appContext) {
    super(TaskScheduler.class.getName());
    this.realAppClient = appClient;
    this.appCallbackExecutor = createAppCallbackExecutorService();
    this.containerSignatureMatcher = containerSignatureMatcher;
    this.appClientDelegate = createAppCallbackDelegate(appClient);
    this.amRmClient = TezAMRMClientAsync.createAMRMClientAsync(1000, this);
    this.appHostName = appHostName;
    this.appHostPort = appHostPort;
    this.appTrackingUrl = appTrackingUrl;
    this.appContext = appContext;
  }

  @Private
  @VisibleForTesting
  TaskScheduler(TaskSchedulerAppCallback appClient,
      ContainerSignatureMatcher containerSignatureMatcher,
      String appHostName,
      int appHostPort,
      String appTrackingUrl,
      TezAMRMClientAsync<CookieContainerRequest> client,
      AppContext appContext) {
    super(TaskScheduler.class.getName());
    this.realAppClient = appClient;
    this.appCallbackExecutor = createAppCallbackExecutorService();
    this.containerSignatureMatcher = containerSignatureMatcher;
    this.appClientDelegate = createAppCallbackDelegate(appClient);
    this.amRmClient = client;
    this.appHostName = appHostName;
    this.appHostPort = appHostPort;
    this.appTrackingUrl = appTrackingUrl;
    this.appContext = appContext;
  }

  private ExecutorService createAppCallbackExecutorService() {
    return Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
        .setNameFormat("TaskSchedulerAppCaller #%d").setDaemon(true).build());
  }
  
  public Resource getAvailableResources() {
    return amRmClient.getAvailableResources();
  }

  public int getClusterNodeCount() {
    // this can potentially be cheaper after YARN-1722
    return amRmClient.getClusterNodeCount();
  }

  TaskSchedulerAppCallback createAppCallbackDelegate(
      TaskSchedulerAppCallback realAppClient) {
    return new TaskSchedulerAppCallbackWrapper(realAppClient,
        appCallbackExecutor);
  }
  
  // AbstractService methods
  @Override
  public synchronized void serviceInit(Configuration conf) {

    amRmClient.init(conf);
    int heartbeatIntervalMax = conf.getInt(
        TezConfiguration.TEZ_AM_RM_HEARTBEAT_INTERVAL_MS_MAX,
        TezConfiguration.TEZ_AM_RM_HEARTBEAT_INTERVAL_MS_MAX_DEFAULT);
    amRmClient.setHeartbeatInterval(heartbeatIntervalMax);

    shouldReuseContainers = conf.getBoolean(
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_ENABLED,
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_ENABLED_DEFAULT);
    reuseRackLocal = conf.getBoolean(
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_RACK_FALLBACK_ENABLED,
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_RACK_FALLBACK_ENABLED_DEFAULT);
    reuseNonLocal = conf
      .getBoolean(
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_NON_LOCAL_FALLBACK_ENABLED,
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_NON_LOCAL_FALLBACK_ENABLED_DEFAULT);
    Preconditions.checkArgument(
      ((!reuseRackLocal && !reuseNonLocal) || (reuseRackLocal)),
      "Re-use Rack-Local cannot be disabled if Re-use Non-Local has been"
      + " enabled");

    localitySchedulingDelay = conf.getLong(
      TezConfiguration.TEZ_AM_CONTAINER_REUSE_LOCALITY_DELAY_ALLOCATION_MILLIS,
      TezConfiguration.TEZ_AM_CONTAINER_REUSE_LOCALITY_DELAY_ALLOCATION_MILLIS_DEFAULT);
    Preconditions.checkArgument(localitySchedulingDelay >= 0,
        "Locality Scheduling delay should be >=0");

    sessionDelay = conf.getLong(
        TezConfiguration.TEZ_AM_CONTAINER_SESSION_DELAY_ALLOCATION_MILLIS,
        TezConfiguration.TEZ_AM_CONTAINER_SESSION_DELAY_ALLOCATION_MILLIS_DEFAULT);
    Preconditions.checkArgument(sessionDelay >= 0 || sessionDelay == -1,
      "Session delay should be either -1 or >=0");

    delayedContainerManager = new DelayedContainerManager();
    LOG.info("TaskScheduler initialized with configuration: " +
            "maxRMHeartbeatInterval: " + heartbeatIntervalMax +
            ", containerReuseEnabled: " + shouldReuseContainers +
            ", reuseRackLocal: " + reuseRackLocal +
            ", reuseNonLocal: " + reuseNonLocal + 
            ", localitySchedulingDelay: " + localitySchedulingDelay +
            ", sessionDelay=" + sessionDelay);
  }

  @Override
  public void serviceStart() {
    try {
      RegisterApplicationMasterResponse response;
      synchronized (this) {
        amRmClient.start();
        response = amRmClient.registerApplicationMaster(appHostName,
                                                        appHostPort,
                                                        appTrackingUrl);
      }
      // upcall to app outside locks
      appClientDelegate.setApplicationRegistrationData(
          response.getMaximumResourceCapability(),
          response.getApplicationACLs(),
          response.getClientToAMTokenMasterKey());

      delayedContainerManager.start();
    } catch (YarnException e) {
      LOG.error("Yarn Exception while registering", e);
      throw new TezUncheckedException(e);
    } catch (IOException e) {
      LOG.error("IO Exception while registering", e);
      throw new TezUncheckedException(e);
    }
  }

  @Override
  public void serviceStop() throws InterruptedException {
    // upcall to app outside of locks
    AppFinalStatus status = appClientDelegate.getFinalAppStatus();
    try {
      delayedContainerManager.shutdown();
      // Wait for contianers to be released.
      delayedContainerManager.join(2000l);
      // TODO TEZ-36 dont unregister automatically after reboot sent by RM
      synchronized (this) {
        isStopped = true;
        amRmClient.unregisterApplicationMaster(status.exitStatus,
                                               status.exitMessage,
                                               status.postCompletionTrackingUrl);
      }

      // call client.stop() without lock client will attempt to stop the callback
      // operation and at the same time the callback operation might be trying
      // to get our lock.
      amRmClient.stop();
      appCallbackExecutor.shutdown();
      appCallbackExecutor.awaitTermination(1000l, TimeUnit.MILLISECONDS);
    } catch (YarnException e) {
      LOG.error("Yarn Exception while unregistering ", e);
      throw new TezUncheckedException(e);
    } catch (IOException e) {
      LOG.error("IOException while unregistering ", e);
      throw new TezUncheckedException(e);
    }
  }

  // AMRMClientAsync interface methods
  @Override
  public void onContainersCompleted(List<ContainerStatus> statuses) {
    if(isStopped) {
      return;
    }
    Map<Object, ContainerStatus> appContainerStatus =
                        new HashMap<Object, ContainerStatus>(statuses.size());
    synchronized (this) {
      for(ContainerStatus containerStatus : statuses) {
        ContainerId completedId = containerStatus.getContainerId();
        HeldContainer delayedContainer = heldContainers.get(completedId);

        Object task = releasedContainers.remove(completedId);
        if(task != null){
          if (delayedContainer != null) {
            LOG.warn("Held container should be null since releasedContainer is not");
          }
          // TODO later we may want to check if exit code matched expectation
          // e.g. successful container should not come back fail exit code after
          // being released
          // completion of a container we had released earlier
          // an allocated container completed. notify app
          LOG.info("Released container completed:" + completedId +
                   " last allocated to task: " + task);
          appContainerStatus.put(task, containerStatus);
          continue;
        }

        // not found in released containers. check currently allocated containers
        // no need to release this container as the RM has already completed it
        task = unAssignContainer(completedId, false);
        if (delayedContainer != null) {
          heldContainers.remove(completedId);
          Resources.subtract(allocatedResources, delayedContainer.getContainer().getResource());
        } else {
          LOG.warn("Held container expected to be not null for a non-AM-released container");
        }
        if(task != null) {
          // completion of a container we have allocated currently
          // an allocated container completed. notify app
          LOG.info("Allocated container completed:" + completedId +
                   " last allocated to task: " + task);
          appContainerStatus.put(task, containerStatus);
          continue;
        }

        // container neither allocated nor released
        LOG.info("Ignoring unknown container: " + containerStatus.getContainerId());
      }
    }

    // upcall to app must be outside locks
    for (Entry<Object, ContainerStatus> entry : appContainerStatus.entrySet()) {
      appClientDelegate.containerCompleted(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void onContainersAllocated(List<Container> containers) {
    if (isStopped) {
      return;
    }
    Map<CookieContainerRequest, Container> assignedContainers;

    if (LOG.isDebugEnabled()) {
      StringBuilder sb = new StringBuilder();
      for (Container container: containers) {
        sb.append(container.getId()).append(", ");
      }
      LOG.debug("Assigned New Containers: " + sb.toString());
    }

    synchronized (this) {
      if (!shouldReuseContainers) {
        List<Container> modifiableContainerList = Lists.newLinkedList(containers);
        assignedContainers = assignNewlyAllocatedContainers(
            modifiableContainerList);
      } else {
        // unify allocations
        pushNewContainerToDelayed(containers);
        return;
      }
    }

    // upcall to app must be outside locks
    informAppAboutAssignments(assignedContainers);
  }

  /**
   * Tries assigning the list of specified containers. Optionally, release
   * containers or add them to the delayed container queue.
   *
   * The flags apply to all containers in the specified lists. So, separate
   * calls should be made based on the expected behaviour.
   *
   * @param containers
   *          The list of containers to be assigned. The list *may* be modified
   *          in place based on allocations and releases.
   * @return Assignments.
   */
  private synchronized Map<CookieContainerRequest, Container>
      assignNewlyAllocatedContainers(Iterable<Container> containers) {

    Map<CookieContainerRequest, Container> assignedContainers =
        new HashMap<CookieContainerRequest, Container>();
    assignNewContainersWithLocation(containers,
      NODE_LOCAL_ASSIGNER, assignedContainers);
    assignNewContainersWithLocation(containers,
      RACK_LOCAL_ASSIGNER, assignedContainers);
    assignNewContainersWithLocation(containers,
      NON_LOCAL_ASSIGNER, assignedContainers);

    // Release any unassigned containers given by the RM
    releaseUnassignedContainers(containers);

    return assignedContainers;
  }

  private synchronized Map<CookieContainerRequest, Container>
      tryAssignReUsedContainers(Iterable<Container> containers) {

    Map<CookieContainerRequest, Container> assignedContainers =
      new HashMap<CookieContainerRequest, Container>();

    // Honor locality and match as many as possible
    assignReUsedContainersWithLocation(containers,
      NODE_LOCAL_ASSIGNER, assignedContainers, true);
    assignReUsedContainersWithLocation(containers,
      RACK_LOCAL_ASSIGNER, assignedContainers, true);
    assignReUsedContainersWithLocation(containers,
      NON_LOCAL_ASSIGNER, assignedContainers, true);

    return assignedContainers;
  }

  /**
   * Try to assign a re-used container
   * @param heldContainer Container to be used to assign to tasks
   * @return Assigned container map
   */

  private synchronized Map<CookieContainerRequest, Container>
      assignDelayedContainer(HeldContainer heldContainer) {

    DAGAppMasterState state = appContext.getAMState();
    boolean isNew = heldContainer.isNew();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Trying to assign a delayed container"
        + ", containerId=" + heldContainer.getContainer().getId()
        + ", nextScheduleTime=" + heldContainer.getNextScheduleTime()
        + ", containerExpiryTime=" + heldContainer.getContainerExpiryTime()
        + ", AMState=" + state
        + ", matchLevel=" + heldContainer.getLocalityMatchLevel()
        + ", taskRequestsCount=" + taskRequests.size()
        + ", heldContainers=" + heldContainers.size()
        + ", delayedContainers=" + delayedContainerManager.delayedContainers.size()
        + ", isNew=" + isNew);
    }

    if (state.equals(DAGAppMasterState.IDLE) || taskRequests.isEmpty()) {
      // reset locality level on held container
      // if sessionDelay defined, push back into delayed queue if not already
      // done so

      heldContainer.resetLocalityMatchLevel();
      long currentTime = System.currentTimeMillis();
      if (isNew || (heldContainer.getContainerExpiryTime() <= currentTime
          && sessionDelay != -1)) {
        LOG.info("No taskRequests. Container's session delay expired or is new. " +
        	"Releasing container"
          + ", containerId=" + heldContainer.container.getId()
          + ", containerExpiryTime="
          + heldContainer.getContainerExpiryTime()
          + ", sessionDelay=" + sessionDelay
          + ", taskRequestsCount=" + taskRequests.size()
          + ", heldContainers=" + heldContainers.size()
          + ", delayedContainers=" + delayedContainerManager.delayedContainers.size()
          + ", isNew=" + isNew);
        releaseUnassignedContainers(
            Lists.newArrayList(heldContainer.container));
      } else {
        if (!appContext.isSession()) {
          releaseUnassignedContainers(
            Lists.newArrayList(heldContainer.container));
        } else {
          // only put back in queue if this is a session
          heldContainer.resetLocalityMatchLevel();
          delayedContainerManager.addDelayedContainer(
            heldContainer.getContainer(),
            currentTime + localitySchedulingDelay);
        }
      }
    } else if (state.equals(DAGAppMasterState.RUNNING)) {
      HeldContainer.LocalityMatchLevel localityMatchLevel =
        heldContainer.getLocalityMatchLevel();
      Map<CookieContainerRequest, Container> assignedContainers =
        new HashMap<CookieContainerRequest, Container>();

      Container containerToAssign = heldContainer.container;

      // Each time a container is seen, we try node, rack and non-local in that
      // order depending on matching level allowed

      // if match level is NEW or NODE, match only at node-local
      // always try node local matches for other levels
      if (isNew
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.NEW)
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.NODE)
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.RACK)
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.NON_LOCAL)) {
        assignReUsedContainerWithLocation(containerToAssign,
            NODE_LOCAL_ASSIGNER, assignedContainers, true);
        if (LOG.isDebugEnabled() && assignedContainers.isEmpty()) {
          LOG.info("Failed to assign tasks to delayed container using node"
            + ", containerId=" + heldContainer.getContainer().getId());
        }
      }

      // if re-use allowed at rack
      // match against rack if match level is RACK or NON-LOCAL
      // if scheduling delay is 0, match at RACK allowed without a sleep
      if (assignedContainers.isEmpty()) {
        if ((reuseRackLocal || isNew) && (localitySchedulingDelay == 0 ||
          (localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.RACK)
            || localityMatchLevel.equals(
              HeldContainer.LocalityMatchLevel.NON_LOCAL)))) {
          assignReUsedContainerWithLocation(containerToAssign,
              RACK_LOCAL_ASSIGNER, assignedContainers, false);
          if (LOG.isDebugEnabled() && assignedContainers.isEmpty()) {
            LOG.info("Failed to assign tasks to delayed container using rack"
              + ", containerId=" + heldContainer.getContainer().getId());
          }
        }
      }

      // if re-use allowed at non-local
      // match against rack if match level is NON-LOCAL
      // if scheduling delay is 0, match at NON-LOCAL allowed without a sleep
      if (assignedContainers.isEmpty()) {
        if ((reuseNonLocal || isNew) && (localitySchedulingDelay == 0
            || localityMatchLevel.equals(
                HeldContainer.LocalityMatchLevel.NON_LOCAL))) {
         assignReUsedContainerWithLocation(containerToAssign,
              NON_LOCAL_ASSIGNER, assignedContainers, false);
          if (LOG.isDebugEnabled() && assignedContainers.isEmpty()) {
            LOG.info("Failed to assign tasks to delayed container using non-local"
                + ", containerId=" + heldContainer.getContainer().getId());
          }
        }
      }

      if (assignedContainers.isEmpty()) {

        long currentTime = System.currentTimeMillis();

        // Release container if final expiry time is reached
        // Dont release a new container. The RM may not give us new ones
        if (!isNew && heldContainer.getContainerExpiryTime() <= currentTime
          && sessionDelay != -1) {
          LOG.info("Container's session delay expired. Releasing container"
            + ", containerId=" + heldContainer.container.getId()
            + ", containerExpiryTime="
            + heldContainer.getContainerExpiryTime()
            + ", sessionDelay=" + sessionDelay);
          releaseUnassignedContainers(
            Lists.newArrayList(heldContainer.container));
        } else {

          // Let's decide if this container has hit the end of the road

          // EOL true if container's match level is NON-LOCAL
          boolean hitFinalMatchLevel = localityMatchLevel.equals(
            HeldContainer.LocalityMatchLevel.NON_LOCAL);
          if (!hitFinalMatchLevel) {
            // EOL also true if locality delay is 0
            // or rack-local or non-local is disabled
            heldContainer.incrementLocalityMatchLevel();
            if (localitySchedulingDelay == 0 ||
                (!reuseRackLocal
                  || (!reuseNonLocal &&
                    heldContainer.getLocalityMatchLevel().equals(
                        HeldContainer.LocalityMatchLevel.NON_LOCAL)))) {
              hitFinalMatchLevel = true;
            }
            // the above if-stmt does not apply to new containers since they will
            // be matched at all locality levels. So there finalMatchLevel cannot
            // be short-circuited
            if (localitySchedulingDelay > 0 && isNew) {
              hitFinalMatchLevel = false;
            }
          }
          
          if (hitFinalMatchLevel) {
            boolean safeToRelease = true;
            Priority topPendingPriority = amRmClient.getTopPriority();
            Priority containerPriority = heldContainer.container.getPriority();
            if (isNew && topPendingPriority != null &&
                containerPriority.compareTo(topPendingPriority) < 0) {
              // this container is of lower priority and given to us by the RM for
              // a task that will be matched after the current top priority. Keep 
              // this container for those pending tasks since the RM is not going
              // to give this container to us again
              safeToRelease = false;
            }
            
            // Are there any pending requests at any priority?
            // release if there are tasks or this is not a session
            if (safeToRelease && 
                (!taskRequests.isEmpty() || !appContext.isSession())) {
              LOG.info("Releasing held container as either there are pending but "
                + " unmatched requests or this is not a session"
                + ", containerId=" + heldContainer.container.getId()
                + ", pendingTasks=" + !taskRequests.isEmpty()
                + ", isSession=" + appContext.isSession()
                + ". isNew=" + isNew);
              releaseUnassignedContainers(
                Lists.newArrayList(heldContainer.container));
            } else {
              // if no tasks, treat this like an idle session
              heldContainer.resetLocalityMatchLevel();
              delayedContainerManager.addDelayedContainer(
                heldContainer.getContainer(),
                currentTime + localitySchedulingDelay);
            }
          } else {
            // Schedule delay container to match at a later try
            delayedContainerManager.addDelayedContainer(
                heldContainer.getContainer(),
                currentTime + localitySchedulingDelay);
          }
        }
      } else if (LOG.isDebugEnabled()) {
        LOG.debug("Delayed container assignment successful"
            + ", containerId=" + heldContainer.getContainer().getId());
      }

      return assignedContainers;
    } else {
      // ignore all other cases?
      LOG.warn("Received a request to assign re-used containers when AM was "
        + " in state: " + state + ". Ignoring request and releasing container"
        + ": " + heldContainer.getContainer().getId());
      releaseUnassignedContainers(Lists.newArrayList(heldContainer.container));
    }

    return null;
  }

  public synchronized void resetMatchLocalityForAllHeldContainers() {
    for (HeldContainer heldContainer : heldContainers.values()) {
      heldContainer.resetLocalityMatchLevel();
    }
    synchronized(delayedContainerManager) {
      delayedContainerManager.notify();
    }
  }

  @Override
  public void onShutdownRequest() {
    if(isStopped) {
      return;
    }
    // upcall to app must be outside locks
    appClientDelegate.appShutdownRequested();
  }

  @Override
  public void onNodesUpdated(List<NodeReport> updatedNodes) {
    if(isStopped) {
      return;
    }
    // ignore bad nodes for now
    // upcall to app must be outside locks
    appClientDelegate.nodesUpdated(updatedNodes);
  }

  @Override
  public float getProgress() {
    if(isStopped) {
      return 1;
    }

    if(totalResources.getMemory() == 0) {
      // assume this is the first allocate callback. nothing is allocated.
      // available resource = totalResource
      // TODO this will not handle dynamic changes in resources
      totalResources = Resources.clone(getAvailableResources());
      LOG.info("App total resource memory: " + totalResources.getMemory() +
               " cpu: " + totalResources.getVirtualCores() +
               " taskAllocations: " + taskAllocations.size());
    }

    preemptIfNeeded();

    return appClientDelegate.getProgress();
  }

  @Override
  public void onError(Throwable t) {
    if(isStopped) {
      return;
    }
    appClientDelegate.onError(t);
  }

  public Resource getTotalResources() {
    return totalResources;
  }

  public synchronized void blacklistNode(NodeId nodeId) {
    amRmClient.addNodeToBlacklist(nodeId);
    blacklistedNodes.add(nodeId);
  }
  
  public synchronized void unblacklistNode(NodeId nodeId) {
    if (blacklistedNodes.remove(nodeId)) {
      amRmClient.removeNodeFromBlacklist(nodeId);
    }
  }
  
  public synchronized void allocateTask(
      Object task,
      Resource capability,
      String[] hosts,
      String[] racks,
      Priority priority,
      Object containerSignature,
      Object clientCookie) {

    // XXX Have ContainerContext implement an interface defined by TaskScheduler.
    // TODO check for nulls etc
    // TODO extra memory allocation
    CRCookie cookie = new CRCookie(task, clientCookie, containerSignature);
    CookieContainerRequest request = new CookieContainerRequest(
      capability, hosts, racks, priority, cookie);

    addTaskRequest(task, request);
    // See if any of the delayedContainers can be used for this task.
    delayedContainerManager.triggerScheduling(true);
    LOG.info("Allocation request for task: " + task +
      " with request: " + request + 
      " host: " + ((hosts!=null&&hosts.length>0)?hosts[0]:"null") +
      " rack: " + ((racks!=null&&racks.length>0)?racks[0]:"null"));
  }

  /**
   * @param task
   *          the task to de-allocate.
   * @param taskSucceeded
   *          specify whether the task succeeded or failed.
   * @return true if a container is assigned to this task.
   */
  public boolean deallocateTask(Object task, boolean taskSucceeded) {
    Map<CookieContainerRequest, Container> assignedContainers = null;

    synchronized (this) {
      CookieContainerRequest request = removeTaskRequest(task);
      if (request != null) {
        // task not allocated yet
        LOG.info("Deallocating task: " + task + " before allocation");
        return false;
      }

      // task request not present. Look in allocations
      Container container = doBookKeepingForTaskDeallocate(task);
      if (container == null) {
        // task neither requested nor allocated.
        LOG.info("Ignoring removal of unknown task: " + task);
        return false;
      } else {
        LOG.info("Deallocated task: " + task + " from container: "
            + container.getId());

        if (!taskSucceeded || !shouldReuseContainers) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Releasing container, containerId=" + container.getId()
                + ", taskSucceeded=" + taskSucceeded
                + ", reuseContainersFlag=" + shouldReuseContainers);
          }
          releaseContainer(container.getId());
        } else {
          // Don't attempt to delay containers if delay is 0.
          HeldContainer heldContainer = heldContainers.get(container.getId());
          if (heldContainer != null) {
            heldContainer.resetLocalityMatchLevel();
            long currentTime = System.currentTimeMillis();
            if (sessionDelay > 0) {
              heldContainer.setContainerExpiryTime(currentTime + sessionDelay);
            }
            assignedContainers = assignDelayedContainer(heldContainer);
          } else {
            LOG.info("Skipping container after task deallocate as container is"
                + " no longer running, containerId=" + container.getId());
          }
        }
      }
    }

    // up call outside of the lock.
    if (assignedContainers != null && assignedContainers.size() == 1) {
      informAppAboutAssignments(assignedContainers);
    }
    return true;
  }
  
  public synchronized Object deallocateContainer(ContainerId containerId) {
    Object task = unAssignContainer(containerId, true);
    if(task != null) {
      LOG.info("Deallocated container: " + containerId +
        " from task: " + task);
      return task;
    }

    LOG.info("Ignoring dealloction of unknown container: " + containerId);
    return null;
  }

  void preemptIfNeeded() {
    ContainerId preemptedContainer = null;
    synchronized (this) {
      Resource freeResources = Resources.subtract(totalResources,
        allocatedResources);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Allocated resource memory: " + allocatedResources.getMemory() +
          " cpu:" + allocatedResources.getVirtualCores() + 
          " delayedContainers: " + delayedContainerManager.delayedContainers.size());
      }
      assert freeResources.getMemory() >= 0;
      
      if (delayedContainerManager.delayedContainers.size() > 0) {
        // if we are holding onto containers then nothing to preempt from outside
        return;
      }
  
      CookieContainerRequest highestPriRequest = null;
      for(CookieContainerRequest request : taskRequests.values()) {
        if(highestPriRequest == null) {
          highestPriRequest = request;
        } else if(isHigherPriority(request.getPriority(),
                                     highestPriRequest.getPriority())){
          highestPriRequest = request;
        }
      }
      if(highestPriRequest != null &&
         !fitsIn(highestPriRequest.getCapability(), freeResources)) {
        // highest priority request will not fit in existing free resources
        // free up some more
        // TODO this is subject to error wrt RM resource normalization
        Map.Entry<Object, Container> preemptedEntry = null;
        for(Map.Entry<Object, Container> entry : taskAllocations.entrySet()) {
          HeldContainer heldContainer = heldContainers.get(entry.getValue().getId());
          CookieContainerRequest lastTaskInfo = heldContainer.getLastTaskInfo();
          Priority taskPriority = lastTaskInfo.getPriority();
          Object signature = lastTaskInfo.getCookie().getContainerSignature();
          if(!isHigherPriority(highestPriRequest.getPriority(), taskPriority)) {
            // higher or same priority
            continue;
          }
          if (containerSignatureMatcher.isExactMatch(
              highestPriRequest.getCookie().getContainerSignature(),
              signature)) {
            // exact match with different priorities
            continue;
          }
          if(preemptedEntry == null ||
             !isHigherPriority(taskPriority, 
                 preemptedEntry.getValue().getPriority())) {
            // keep the lower priority or the one added later
            preemptedEntry = entry;
          }
        }
        if(preemptedEntry != null) {
          // found something to preempt
          LOG.info("Preempting task: " + preemptedEntry.getKey() +
              " to free resource for request: " + highestPriRequest +
              " . Current free resources: " + freeResources);
          preemptedContainer = preemptedEntry.getValue().getId();
          // app client will be notified when after container is killed
          // and we get its completed container status
        }
      }
    }
    
    // upcall outside locks
    if (preemptedContainer != null) {
      appClientDelegate.preemptContainer(preemptedContainer);
    }
  }

  private boolean fitsIn(Resource toFit, Resource resource) {
    // YARN-893 prevents using correct library code
    //return Resources.fitsIn(toFit, resource);
    return resource.getMemory() >= toFit.getMemory();
  }

  private CookieContainerRequest getMatchingRequestWithPriority(
      Container container,
      String location) {
    Priority priority = container.getPriority();
    Resource capability = container.getResource();
    List<? extends Collection<CookieContainerRequest>> requestsList =
        amRmClient.getMatchingRequests(priority, location, capability);

    if (!requestsList.isEmpty()) {
      // pick first one
      for (Collection<CookieContainerRequest> requests : requestsList) {
        for (CookieContainerRequest cookieContainerRequest : requests) {
          if (canAssignTaskToContainer(cookieContainerRequest, container)) {
            return cookieContainerRequest;
          }
        }
      }
    }

    return null;
  }

  private CookieContainerRequest getMatchingRequestWithoutPriority(
      Container container,
      String location) {
    Resource capability = container.getResource();
    List<? extends Collection<CookieContainerRequest>> pRequestsList =
      amRmClient.getMatchingRequestsForTopPriority(location, capability);
    if (pRequestsList == null || pRequestsList.isEmpty()) {
      return null;
    }
    for (Collection<CookieContainerRequest> requests : pRequestsList) {
      for (CookieContainerRequest cookieContainerRequest : requests) {
        if (canAssignTaskToContainer(cookieContainerRequest, container)) {
          return cookieContainerRequest;
        }
      }
    }
    return null;
  }

  private boolean canAssignTaskToContainer(
      CookieContainerRequest cookieContainerRequest, Container container) {
    HeldContainer heldContainer = heldContainers.get(container.getId());
    if (heldContainer == null || heldContainer.isNew()) { // New container.
      return true;
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Trying to match task to a held container, "
            + " containerId=" + heldContainer.container.getId());
      }
      if (containerSignatureMatcher.isSuperSet(heldContainer
          .getFirstContainerSignature(), cookieContainerRequest.getCookie()
          .getContainerSignature())) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Matched delayed container to task"
            + " containerId=" + heldContainer.container.getId());
        }
        return true;
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Failed to match delayed container to task"
        + " containerId=" + heldContainer.container.getId());
    }
    return false;
  }

  private Object getTask(CookieContainerRequest request) {
    return request.getCookie().getTask();
  }

  private void releaseContainer(ContainerId containerId) {
    Object assignedTask = containerAssignments.remove(containerId);
    if (assignedTask != null) {
      // A task was assigned to this container at some point. Inform the app.
      appClientDelegate.containerBeingReleased(containerId);
    }
    HeldContainer delayedContainer = heldContainers.remove(containerId);
    if (delayedContainer != null) {
      Resources.subtractFrom(allocatedResources,
          delayedContainer.getContainer().getResource());
    }
    if (delayedContainer != null || !shouldReuseContainers) {
      amRmClient.releaseAssignedContainer(containerId);
    }
    if (assignedTask != null) {
      // A task was assigned at some point. Add to release list since we are
      // releasing the container.
      releasedContainers.put(containerId, assignedTask);
    }
  }

  private void assignContainer(Object task,
      Container container,
      CookieContainerRequest assigned) {
    CookieContainerRequest request = removeTaskRequest(task);
    assert request != null;
    //assert assigned.equals(request);

    Container result = taskAllocations.put(task, container);
    assert result == null;
    containerAssignments.put(container.getId(), task);
    HeldContainer heldContainer = heldContainers.get(container.getId()); 
    if (!shouldReuseContainers && heldContainer == null) {
      heldContainers.put(container.getId(), new HeldContainer(container,
        -1, -1, assigned));
      Resources.addTo(allocatedResources, container.getResource());
    } else {
      if (heldContainer.isNew()) {
        // check for existence before adding since the first container potentially
        // has the broadest signature as subsequent uses dont expand any dimension.
        // This will need to be enhanced to track other signatures too when we
        // think about preferring within vertex matching etc.
        heldContainers.put(container.getId(),
            new HeldContainer(container, heldContainer.getNextScheduleTime(),
                heldContainer.getContainerExpiryTime(), assigned));
      }
      heldContainer.setLastTaskInfo(assigned);
    }
  }
  
  private void pushNewContainerToDelayed(List<Container> containers){
    long expireTime = -1;
    if (sessionDelay > 0) {
      long currentTime = System.currentTimeMillis();
      expireTime = currentTime + sessionDelay;
    }

    synchronized (delayedContainerManager) {
      for (Container container : containers) {
        if (heldContainers.put(container.getId(), new HeldContainer(container,
            -1, expireTime, null)) != null) {
          throw new TezUncheckedException("New container " + container.getId()
              + " is already held.");
        }
        long nextScheduleTime = delayedContainerManager.maxScheduleTimeSeen;
        if (delayedContainerManager.maxScheduleTimeSeen == -1) {
          nextScheduleTime = System.currentTimeMillis();
        }
        Resources.addTo(allocatedResources, container.getResource());
        delayedContainerManager.addDelayedContainer(container,
          nextScheduleTime + 1);
      }
    }
    delayedContainerManager.triggerScheduling(false);      
  }

  private CookieContainerRequest removeTaskRequest(Object task) {
    CookieContainerRequest request = taskRequests.remove(task);
    if(request != null) {
      // remove all references of the request from AMRMClient
      amRmClient.removeContainerRequest(request);
    }
    return request;
  }

  private void addTaskRequest(Object task,
                                CookieContainerRequest request) {
    // TODO TEZ-37 fix duplicate handling
    taskRequests.put(task, request);
    amRmClient.addContainerRequest(request);
  }

  private Container doBookKeepingForTaskDeallocate(Object task) {
    Container container = taskAllocations.remove(task);
    if (container == null) {
      return null;
    }
    return container;
  }

  private Object unAssignContainer(ContainerId containerId,
                                    boolean releaseIfFound) {
    // Not removing. containerAssignments tracks the last task run on a
    // container.
    Object task = containerAssignments.get(containerId);
    if(task == null) {
      return null;
    }
    Container container = taskAllocations.remove(task);
    assert container != null;
    if(releaseIfFound) {
      releaseContainer(containerId);
    }
    return task;
  }

  private boolean isHigherPriority(Priority lhs, Priority rhs) {
    return lhs.getPriority() < rhs.getPriority();
  }

  private synchronized void assignNewContainersWithLocation(
      Iterable<Container> containers,
      ContainerAssigner assigner,
      Map<CookieContainerRequest, Container> assignedContainers) {

    Iterator<Container> containerIterator = containers.iterator();
    while (containerIterator.hasNext()) {
      Container container = containerIterator.next();
      CookieContainerRequest assigned =
        assigner.assignNewContainer(container);
      if (assigned != null) {
        assignedContainers.put(assigned, container);
        containerIterator.remove();
      }
    }
  }

  private synchronized void assignReUsedContainersWithLocation(
      Iterable<Container> containers,
      ContainerAssigner assigner,
      Map<CookieContainerRequest, Container> assignedContainers,
      boolean honorLocality) {

    Iterator<Container> containerIterator = containers.iterator();
    while (containerIterator.hasNext()) {
      Container container = containerIterator.next();
      if (assignReUsedContainerWithLocation(container, assigner,
          assignedContainers, honorLocality)) {
        containerIterator.remove();
      }
    }
  }

  private synchronized boolean assignReUsedContainerWithLocation(
    Container container,
    ContainerAssigner assigner,
    Map<CookieContainerRequest, Container> assignedContainers,
    boolean honorLocality) {

    Priority containerPriority = container.getPriority();
    Priority topPendingTaskPriority = amRmClient.getTopPriority();
    if (topPendingTaskPriority == null) {
      // nothing left to assign
      return false;
    }
    
    if (topPendingTaskPriority.compareTo(containerPriority) > 0) {
      // if the next task to assign is higher priority than the container then 
      // dont assign this container to that task.
      // if task and container are equal priority - then its first use or reuse
      // within the same priority - safe to use
      // if task is lower priority than container then its we use a container that
      // is no longer needed by higher priority tasks All those higher pri tasks 
      // have been assigned resources - safe to use (first use or reuse)
      // if task is higher priority than container then we may end up using a 
      // container that was assigned by the RM for a lower priority pending task 
      // that will be assigned after this higher priority task is assigned. If we
      // use that task's container now then we may not be able to match this 
      // container to that task later on. However the RM has already assigned us 
      // all containers and is not going to give us new containers. We will get 
      // stuck for resources.
      return false;
    }
    
    CookieContainerRequest assigned =
      assigner.assignReUsedContainer(container, honorLocality);
    if (assigned != null) {
      assignedContainers.put(assigned, container);
      return true;
    }
    return false;
  }

  private void releaseUnassignedContainers(Iterable<Container> containers) {
    for (Container container : containers) {
      LOG.info("Releasing unused container: "
          + container.getId());
      releaseContainer(container.getId());
    }
  }

  private void informAppAboutAssignment(CookieContainerRequest assigned,
      Container container) {
    appClientDelegate.taskAllocated(getTask(assigned),
        assigned.getCookie().getAppCookie(), container);
  }

  private void informAppAboutAssignments(
      Map<CookieContainerRequest, Container> assignedContainers) {
    if (assignedContainers == null || assignedContainers.isEmpty()) {
      return;
    }
    for (Entry<CookieContainerRequest, Container> entry : assignedContainers
        .entrySet()) {
      Container container = entry.getValue();
      // check for blacklisted nodes. There may be race conditions between
      // setting blacklist and receiving allocations
      if (blacklistedNodes.contains(container.getNodeId())) {
        CookieContainerRequest request = entry.getKey();
        Object task = getTask(request);
        Object clientCookie = request.getCookie().getAppCookie();
        LOG.info("Container: " + container.getId() + 
            " allocated on blacklisted node: " + container.getNodeId() + 
            " for task: " + task);
        Object deAllocTask = deallocateContainer(container.getId());
        assert deAllocTask.equals(task);
        // its ok to submit the same request again because the RM will not give us
        // the bad/unhealthy nodes again. The nodes may become healthy/unblacklisted
        // and so its better to give the RM the full information.
        allocateTask(task, request.getCapability(), 
            (request.getNodes() == null ? null : 
            request.getNodes().toArray(new String[request.getNodes().size()])), 
            (request.getRacks() == null ? null : 
              request.getRacks().toArray(new String[request.getRacks().size()])), 
            request.getPriority(), 
            request.getCookie().getContainerSignature(), clientCookie);
      } else {
        informAppAboutAssignment(entry.getKey(), container);
      }
    }
  }

  private abstract class ContainerAssigner {

    protected final String locality;

    protected ContainerAssigner(String locality) {
      this.locality = locality;
    }

    public abstract CookieContainerRequest assignNewContainer(
        Container container);

    public abstract CookieContainerRequest assignReUsedContainer(
      Container container, boolean honorLocality);

    public void doBookKeepingForAssignedContainer(
        CookieContainerRequest assigned, Container container,
        String matchedLocation, boolean honorLocalityFlags) {
      if (assigned == null) {
        return;
      }
      Object task = getTask(assigned);
      assert task != null;

      LOG.info("Assigning container to task"
        + ", container=" + container
        + ", task=" + task
        + ", containerHost=" + container.getNodeId().getHost()
        + ", localityMatchType=" + locality
        + ", matchedLocation=" + matchedLocation
        + ", honorLocalityFlags=" + honorLocalityFlags
        + ", reusedContainer="
        + containerAssignments.containsKey(container.getId())
        + ", delayedContainers=" + delayedContainerManager.delayedContainers.size()
        + ", containerResourceMemory=" + container.getResource().getMemory()
        + ", containerResourceVCores="
        + container.getResource().getVirtualCores());

      assignContainer(task, container, assigned);
    }
  }
  
  private class NodeLocalContainerAssigner extends ContainerAssigner {

    NodeLocalContainerAssigner() {
      super("NodeLocal");
    }

    @Override
    public CookieContainerRequest assignNewContainer(Container container) {
      String location = container.getNodeId().getHost();
      CookieContainerRequest assigned = getMatchingRequestWithPriority(
          container, location);
      doBookKeepingForAssignedContainer(assigned, container, location, false);
      return assigned;
    }

    @Override
    public CookieContainerRequest assignReUsedContainer(Container container,
        boolean honorLocality) {
      String location = container.getNodeId().getHost();
      CookieContainerRequest assigned = getMatchingRequestWithoutPriority(
        container, location);
      doBookKeepingForAssignedContainer(assigned, container, location, true);
      return assigned;

    }
  }

  private class RackLocalContainerAssigner extends ContainerAssigner {

    RackLocalContainerAssigner() {
      super("RackLocal");
    }

    @Override
    public CookieContainerRequest assignNewContainer(Container container) {
      String location = RackResolver.resolve(container.getNodeId().getHost())
          .getNetworkLocation();
      CookieContainerRequest assigned = getMatchingRequestWithPriority(container,
          location);
      doBookKeepingForAssignedContainer(assigned, container, location, false);
      return assigned;
    }

    @Override
    public CookieContainerRequest assignReUsedContainer(
      Container container, boolean honorLocality) {
      // TEZ-586 this is not match an actual rackLocal request unless honorLocality
      // is false. This method is useless if honorLocality=true
      if (!honorLocality) {
        String location = RackResolver.resolve(container.getNodeId().getHost())
          .getNetworkLocation();
        CookieContainerRequest assigned = getMatchingRequestWithoutPriority(
            container, location);
        doBookKeepingForAssignedContainer(assigned, container, location,
            honorLocality);
        return assigned;
      }
      return null;
    }
  }

  private class NonLocalContainerAssigner extends ContainerAssigner {

    NonLocalContainerAssigner() {
      super("NonLocal");
    }

    @Override
    public CookieContainerRequest assignNewContainer(Container container) {
      String location = ResourceRequest.ANY;
      CookieContainerRequest assigned = getMatchingRequestWithPriority(container,
          location);
      doBookKeepingForAssignedContainer(assigned, container, location, false);
      return assigned;
    }

    @Override
    public CookieContainerRequest assignReUsedContainer(Container container,
        boolean honorLocality) {
      if (!honorLocality) {
        String location = ResourceRequest.ANY;
        CookieContainerRequest assigned = getMatchingRequestWithoutPriority(
          container, location);
        doBookKeepingForAssignedContainer(assigned, container, location,
            honorLocality);
        return assigned;
      }
      return null;
    }

  }
  
  
  @VisibleForTesting
  class DelayedContainerManager extends Thread {

    class HeldContainerTimerComparator implements Comparator<HeldContainer> {

      @Override
      public int compare(HeldContainer c1,
          HeldContainer c2) {
        return (int) (c1.getNextScheduleTime() - c2.getNextScheduleTime());
      }
    }

    private PriorityBlockingQueue<HeldContainer> delayedContainers =
      new PriorityBlockingQueue<HeldContainer>(20,
        new HeldContainerTimerComparator());

    private volatile boolean tryAssigningAll = false;
    private volatile boolean running = true;
    private long maxScheduleTimeSeen = -1;
    
    // used for testing only
    @VisibleForTesting
    AtomicBoolean drainedDelayedContainers = null;

    DelayedContainerManager() {
      super.setName("DelayedContainerManager");
    }
    
    @Override
    public void run() {
      while(running) {
        // Try assigning all containers if there's a request to do so.
        if (tryAssigningAll) {
          doAssignAll();
          tryAssigningAll = false;
        }

        // Try allocating containers which have timed out.
        // Required since these containers may get assigned without
        // locality at this point.
        if (delayedContainers.peek() == null) {
          try {
            if (drainedDelayedContainers != null) {
              drainedDelayedContainers.set(true);
              synchronized (drainedDelayedContainers) {
                drainedDelayedContainers.notifyAll();
              }
            }
            synchronized(this) {
              this.wait();
            }
            // Re-loop to see if tryAssignAll is set.
            continue;
          } catch (InterruptedException e) {
            LOG.info("AllocatedContainerManager Thread interrupted");
          }
        } else {
          HeldContainer delayedContainer = delayedContainers.peek();
          if (delayedContainer == null) {
            continue;
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("Considering HeldContainer: "
              + delayedContainer + " for assignment");
          }
          long currentTs = System.currentTimeMillis();
          long nextScheduleTs = delayedContainer.getNextScheduleTime();
          if (currentTs >= nextScheduleTs) {
            // Remove the container and try scheduling it.
            // TEZ-587 what if container is released by RM after this
            // in onContainerCompleted()
            delayedContainer = delayedContainers.poll();
            if (delayedContainer == null) {
              continue;
            }
            Map<CookieContainerRequest, Container> assignedContainers = null;
            synchronized(TaskScheduler.this) {
              if (null !=
                  heldContainers.get(delayedContainer.getContainer().getId())) {
                assignedContainers = assignDelayedContainer(delayedContainer);
              } else {
                LOG.info("Skipping delayed container as container is no longer"
                  + " running, containerId="
                  + delayedContainer.getContainer().getId());
              }
            }
            // Inform App should be done outside of the lock
            informAppAboutAssignments(assignedContainers);
          } else {
            synchronized(this) {
              try {
                // Wait for the next container to be assignable
                delayedContainer = delayedContainers.peek();
                long diff = localitySchedulingDelay;
                if (delayedContainer != null) {
                  diff = delayedContainer.getNextScheduleTime() - currentTs;
                }
                if (diff > 0) {
                  this.wait(diff);
                }
              } catch (InterruptedException e) {
                LOG.info("AllocatedContainerManager Thread interrupted");
              }
            }
          }
        }
      }
      releasePendingContainers();
    }
    
    private void doAssignAll() {
      // The allocatedContainers queue should not be modified in the middle of an iteration over it.
      // Synchronizing here on TaskScheduler.this to prevent this from happening.
      // The call to assignAll from within this method should NOT add any
      // elements back to the allocatedContainers list. Since they're all
      // delayed elements, de-allocation should not happen either - leaving the
      // list of delayed containers intact, except for the contaienrs which end
      // up getting assigned.
      if (delayedContainers.isEmpty()) {
        return;
      }

      Map<CookieContainerRequest, Container> assignedContainers;
      synchronized(TaskScheduler.this) {
        // honor reuse-locality flags (container not timed out yet), Don't queue
        // (already in queue), don't release (release happens when containers
        // time-out)
        if (LOG.isDebugEnabled()) {
          LOG.debug("Trying to assign all delayed containers to newly received"
            + " tasks");
        }
        Iterator<HeldContainer> iter = delayedContainers.iterator();
        while(iter.hasNext()) {
          HeldContainer delayedContainer = iter.next();
          if (!heldContainers.containsKey(delayedContainer.getContainer().getId())) {
            // this container is no longer held by us
            LOG.info("AssignAll - Skipping delayed container as container is no longer"
                + " running, containerId="
                + delayedContainer.getContainer().getId());
            iter.remove();
          }
        }
        assignedContainers = tryAssignReUsedContainers(
          new ContainerIterable(delayedContainers));
      }
      // Inform app
      informAppAboutAssignments(assignedContainers);
    }
    
    /**
     * Indicate that an attempt should be made to allocate all available containers.
     * Intended to be used in cases where new Container requests come in 
     */
    public void triggerScheduling(boolean scheduleAll) {
      this.tryAssigningAll = scheduleAll;
      synchronized(this) {
        this.notify();
      }
    }

    public void shutdown() {
      this.running = false;
      this.interrupt();
    }
    
    private void releasePendingContainers() {
      List<HeldContainer> pendingContainers = Lists.newArrayListWithCapacity(
        delayedContainers.size());
      delayedContainers.drainTo(pendingContainers);
      releaseUnassignedContainers(new ContainerIterable(pendingContainers));
    }

    private void addDelayedContainer(Container container,
        long nextScheduleTime) {
      HeldContainer delayedContainer = heldContainers.get(container.getId());
      if (delayedContainer == null) {
        LOG.warn("Attempting to add a non-running container to the"
            + " delayed container list, containerId=" + container.getId());
        return;
      } else {
        delayedContainer.setNextScheduleTime(nextScheduleTime);
      }
      if (maxScheduleTimeSeen < nextScheduleTime) {
        maxScheduleTimeSeen = nextScheduleTime;
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Adding container to delayed queue"
          + ", containerId=" + delayedContainer.getContainer().getId()
          + ", nextScheduleTime=" + delayedContainer.getNextScheduleTime()
          + ", containerExpiry=" + delayedContainer.getContainerExpiryTime());
      }
      boolean added = delayedContainers.offer(delayedContainer);
      synchronized(this) {
        this.notify();
      }
      if (!added) {
        releaseUnassignedContainers(Lists.newArrayList(container));
      }
    }

  }

  private class ContainerIterable implements Iterable<Container> {

    private final Iterable<HeldContainer> delayedContainers;

    ContainerIterable(Iterable<HeldContainer> delayedContainers) {
      this.delayedContainers = delayedContainers;
    }

    @Override
    public Iterator<Container> iterator() {

      final Iterator<HeldContainer> delayedContainerIterator = delayedContainers
          .iterator();

      return new Iterator<Container>() {

        @Override
        public boolean hasNext() {
          return delayedContainerIterator.hasNext();
        }

        @Override
        public Container next() {
          return delayedContainerIterator.next().getContainer();
        }

        @Override
        public void remove() {
          delayedContainerIterator.remove();
        }
      };
    }
  }

  static class HeldContainer {

    enum LocalityMatchLevel {
      NEW,
      NODE,
      RACK,
      NON_LOCAL
    }

    Container container;
    private long nextScheduleTime;
    private Object firstContainerSignature;
    private LocalityMatchLevel localityMatchLevel;
    private long containerExpiryTime;
    private CookieContainerRequest lastTaskInfo;
    
    HeldContainer(Container container,
        long nextScheduleTime,
        long containerExpiryTime,
        CookieContainerRequest firstTaskInfo) {
      this.container = container;
      this.nextScheduleTime = nextScheduleTime;
      if (firstTaskInfo != null) {
        this.lastTaskInfo = firstTaskInfo;
        this.firstContainerSignature = firstTaskInfo.getCookie().getContainerSignature();
      }
      this.localityMatchLevel = LocalityMatchLevel.NODE;
      this.containerExpiryTime = containerExpiryTime;
    }
    
    boolean isNew() {
      return firstContainerSignature == null;
    }
    
    public Container getContainer() {
      return this.container;
    }
    
    public long getNextScheduleTime() {
      return this.nextScheduleTime;
    }
    
    public void setNextScheduleTime(long nextScheduleTime) {
      this.nextScheduleTime = nextScheduleTime;
    }

    public long getContainerExpiryTime() {
      return this.containerExpiryTime;
    }

    public void setContainerExpiryTime(long containerExpiryTime) {
      this.containerExpiryTime = containerExpiryTime;
    }

    public Object getFirstContainerSignature() {
      return this.firstContainerSignature;
    }
    
    public CookieContainerRequest getLastTaskInfo() {
      return this.lastTaskInfo;
    }
    
    public void setLastTaskInfo(CookieContainerRequest taskInfo) {
      lastTaskInfo = taskInfo;
    }

    public synchronized void resetLocalityMatchLevel() {
      localityMatchLevel = LocalityMatchLevel.NEW;
    }

    public synchronized void incrementLocalityMatchLevel() {
      if (localityMatchLevel.equals(LocalityMatchLevel.NEW)) {
        localityMatchLevel = LocalityMatchLevel.NODE;
      } else if (localityMatchLevel.equals(LocalityMatchLevel.NODE)) {
        localityMatchLevel = LocalityMatchLevel.RACK;
      } else if (localityMatchLevel.equals(LocalityMatchLevel.RACK)) {
        localityMatchLevel = LocalityMatchLevel.NON_LOCAL;
      } else if (localityMatchLevel.equals(LocalityMatchLevel.NON_LOCAL)) {
        throw new TezUncheckedException("Cannot increment locality level "
          + " from current NON_LOCAL for container: " + container.getId());
      }
    }

    public LocalityMatchLevel getLocalityMatchLevel() {
      return this.localityMatchLevel;
    }

    @Override
    public String toString() {
      return "HeldContainer: id: " + container.getId()
          + ", nextScheduleTime: " + nextScheduleTime
          + ", localityMatchLevel=" + localityMatchLevel
          + ", signature: "
          + (firstContainerSignature != null? firstContainerSignature.toString():"null");
    }
  }
}
