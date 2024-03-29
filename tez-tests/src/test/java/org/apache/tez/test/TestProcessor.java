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

package org.apache.tez.test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.tez.common.TezUtils;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.LogicalIOProcessor;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.api.LogicalOutput;
import org.apache.tez.runtime.api.TezProcessorContext;
import org.apache.tez.runtime.api.TezTaskContext;

import com.google.common.collect.Sets;

/**
 * LogicalIOProcessor used to write tests. Supports fault injection through
 * configuration. The configuration is post-fixed by the name of the vertex for
 * this processor. The fault injection executes in the run() method. The
 * processor first sleeps for a specified interval. Then checks if it needs to
 * fail. It fails and exits if configured to do so. If not, then it calls
 * doRead() on all inputs to let them fail.
 */
public class TestProcessor implements LogicalIOProcessor {
  private static final Log LOG = LogFactory
      .getLog(TestProcessor.class);
  
  Configuration conf;
  TezTaskContext processorContext;
  
  boolean doFail = false;
  long sleepMs;
  Set<Integer> failingTaskIndices = Sets.newHashSet();
  int failingTaskAttemptUpto = 0;
  Integer failAll = new Integer(-1);
  
  int verifyValue = -1;
  Set<Integer> verifyTaskIndices = Sets.newHashSet();
  
  /**
   * Enable failure for this processor
   */
  public static String TEZ_FAILING_PROCESSOR_DO_FAIL =
      "tez.failing-processor.do-fail";
  /**
   * Time to sleep in the processor in milliseconds.
   */
  public static String TEZ_FAILING_PROCESSOR_SLEEP_MS =
      "tez.failing-processor.sleep-ms";
  /**
   * The indices of tasks in the vertex for which the processor will fail. This 
   * is a comma-separated list of +ve integeres. -1 means all fail.
   */
  public static String TEZ_FAILING_PROCESSOR_FAILING_TASK_INDEX =
      "tez.failing-processor.failing-task-index";
  /**
   * Up to which attempt of the tasks will fail. Specifying 0 means the first
   * attempt will fail. 1 means first and second attempt will fail. And so on.
   */
  public static String TEZ_FAILING_PROCESSOR_FAILING_UPTO_TASK_ATTEMPT =
      "tez.failing-processor.failing-upto-task-attempt";
  
  public static String TEZ_FAILING_PROCESSOR_VERIFY_VALUE = 
      "tez.failing-processor.verify-value";
  
  public static String TEZ_FAILING_PROCESSOR_VERIFY_TASK_INDEX =
      "tez.failing-processor.verify-task-index";
  
  public static ProcessorDescriptor getProcDesc(byte[] payload) {
    return new ProcessorDescriptor(TestProcessor.class.getName()).
        setUserPayload(payload);
  }

  void throwException(String msg) {
    RuntimeException e = new RuntimeException(msg);
    processorContext.fatalError(e , msg);
    throw e;
  }

  public static String getVertexConfName(String confName, String vertexName) {
    return confName + "." + vertexName;
  }
  
  public static String getVertexConfName(String confName, String vertexName,
      int taskIndex) {
    return confName + "." + vertexName + "." + String.valueOf(taskIndex);
  }
  
  @Override
  public void initialize(TezProcessorContext processorContext) throws Exception {
    this.processorContext = processorContext;
    if (processorContext.getUserPayload() != null) {
      String vName = processorContext.getTaskVertexName();
      conf = TezUtils.createConfFromUserPayload(processorContext
          .getUserPayload());
      verifyValue = conf.getInt(
          getVertexConfName(TEZ_FAILING_PROCESSOR_VERIFY_VALUE, vName,
              processorContext.getTaskIndex()), -1);
      if (verifyValue != -1) {
        LOG.info("Verify value: " + verifyValue);
        for (String verifyIndex : conf
            .getTrimmedStringCollection(
                getVertexConfName(TEZ_FAILING_PROCESSOR_VERIFY_TASK_INDEX, vName))) {
          LOG.info("Adding verify task index: " + verifyIndex);
          verifyTaskIndices.add(Integer.valueOf(verifyIndex));
        }
      }
      doFail = conf.getBoolean(
          getVertexConfName(TEZ_FAILING_PROCESSOR_DO_FAIL, vName), false);
      sleepMs = conf.getLong(
          getVertexConfName(TEZ_FAILING_PROCESSOR_SLEEP_MS, vName), 0);
      LOG.info("doFail: " + doFail);
      if (doFail) {
        for (String failingIndex : conf
            .getTrimmedStringCollection(
                getVertexConfName(TEZ_FAILING_PROCESSOR_FAILING_TASK_INDEX, vName))) {
          LOG.info("Adding failing task index: " + failingIndex);
          failingTaskIndices.add(Integer.valueOf(failingIndex));
        }
        failingTaskAttemptUpto = conf.getInt(
            getVertexConfName(TEZ_FAILING_PROCESSOR_FAILING_UPTO_TASK_ATTEMPT, vName), 0);
        LOG.info("Adding failing attempt : " + failingTaskAttemptUpto + 
            " dag: " + processorContext.getDAGName());
      }
    }
  }

  @Override
  public void handleEvents(List<Event> processorEvents) {
  }

  @Override
  public void close() throws Exception {
  }

  @Override
  public void run(Map<String, LogicalInput> inputs,
      Map<String, LogicalOutput> outputs) throws Exception {
    LOG.info("Sleeping ms: " + sleepMs);

    for (LogicalInput input : inputs.values()) {
      input.start();
    }
    for (LogicalOutput output : outputs.values()) {
      output.start();
    }

    Thread.sleep(sleepMs);
    
    if (doFail) {
      if (
          (failingTaskIndices.contains(failAll) ||
          failingTaskIndices.contains(processorContext.getTaskIndex())) &&
          (failingTaskAttemptUpto == failAll.intValue() || 
           failingTaskAttemptUpto >= processorContext.getTaskAttemptNumber())) {
        String msg = "FailingProcessor: " + processorContext.getUniqueIdentifier() + 
            " dag: " + processorContext.getDAGName() +
            " taskIndex: " + processorContext.getTaskIndex() +
            " taskAttempt: " + processorContext.getTaskAttemptNumber();
        LOG.info(msg);
        throwException(msg);
      }
    }
    
    if (inputs.entrySet().size() > 0) {
        String msg = "Reading input of current FailingProcessor: " + processorContext.getUniqueIdentifier() + 
            " dag: " + processorContext.getDAGName() +
            " vertex: " + processorContext.getTaskVertexName() +
            " taskIndex: " + processorContext.getTaskIndex() +
            " taskAttempt: " + processorContext.getTaskAttemptNumber();
        LOG.info(msg);
    }
    //initialize sum to attempt number + 1
    int sum = processorContext.getTaskAttemptNumber() + 1;
    LOG.info("initializing vertex= " + processorContext.getTaskVertexName() +
             " taskIndex: " + processorContext.getTaskIndex() +
             " taskAttempt: " + processorContext.getTaskAttemptNumber() +
             " sum= " + sum);
    //sum = summation of input values
    for (Map.Entry<String, LogicalInput> entry : inputs.entrySet()) {
      TestInput input = (TestInput) entry.getValue();
      int inputValue = input.doRead();
      LOG.info("Reading input: " + entry.getKey() + " inputValue= " + inputValue);
      sum += inputValue;
    }
    
    if (outputs.entrySet().size() > 0) {
        String msg = "Writing output of current FailingProcessor: " + processorContext.getUniqueIdentifier() + 
            " dag: " + processorContext.getDAGName() +
            " vertex: " + processorContext.getTaskVertexName() +
            " taskIndex: " + processorContext.getTaskIndex() +
            " taskAttempt: " + processorContext.getTaskAttemptNumber();
        LOG.info(msg);
    }
    for (Map.Entry<String, LogicalOutput> entry : outputs.entrySet()) {
      LOG.info("Writing output: " + entry.getKey() + " sum= " + sum);
      TestOutput output = (TestOutput) entry.getValue();
      output.write(sum);
    }
    
    LOG.info("Output for DAG: " + processorContext.getDAGName() 
        + " vertex: " + processorContext.getTaskVertexName()
        + " task: " + processorContext.getTaskIndex()
        + " attempt: " + processorContext.getTaskAttemptNumber()
        + " is: " + sum);
    if (verifyTaskIndices
        .contains(new Integer(processorContext.getTaskIndex()))) {
      if (verifyValue != -1 && verifyValue != sum) {
        // expected output value set and not equal to observed value
        String msg = "Expected output mismatch of current FailingProcessor: " 
                     + processorContext.getUniqueIdentifier() + 
                     " dag: " + processorContext.getDAGName() +
                     " vertex: " + processorContext.getTaskVertexName() +
                     " taskIndex: " + processorContext.getTaskIndex() +
                     " taskAttempt: " + processorContext.getTaskAttemptNumber();
        msg += "\n" + "Expected output: " + verifyValue + " got: " + sum;
        throwException(msg);
      } else {
        LOG.info("Verified output for DAG: " + processorContext.getDAGName()
            + " vertex: " + processorContext.getTaskVertexName() + " task: "
            + processorContext.getTaskIndex() + " attempt: "
            + processorContext.getTaskAttemptNumber() + " is: " + sum);
      }
    }
  }

}
