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

package org.apache.tez.runtime.api;

import java.io.IOException;
import java.util.List;

/**
 * {@link Processor} represents the <em>Tez</em> entity responsible for
 * consuming {@link Input} and producing {@link Output}.
 */
public interface Processor {

  /**
   * Initializes the <code>Processor</code>
   *
   * @param processorContext
   * @throws IOException
   *           if an error occurs
   */
  public void initialize(TezProcessorContext processorContext)
      throws Exception;

  /**
   * Handles user and system generated {@link Event}s.
   *
   * @param processorEvents
   *          the list of {@link Event}s
   */
  public void handleEvents(List<Event> processorEvents);

  /**
   * Closes the <code>Processor</code>
   *
   * @throws IOException
   *           if an error occurs
   */
  public void close() throws Exception;
}
