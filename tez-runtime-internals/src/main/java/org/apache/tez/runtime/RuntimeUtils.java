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

package org.apache.tez.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.TezUncheckedException;

public class RuntimeUtils {
  
  private static final Map<String, Class<?>> CLAZZ_CACHE = new ConcurrentHashMap<String, Class<?>>();

  @Private
  public static Class<?> getClazz(String className) {
    Class<?> clazz = CLAZZ_CACHE.get(className);
    if (clazz == null) {
      try {
        clazz = Class.forName(className);
      } catch (ClassNotFoundException e) {
        throw new TezUncheckedException("Unable to load class: " + className, e);
      }
    }
    return clazz;
  }

  private static <T> T getNewInstance(Class<T> clazz) {
    T instance;
    try {
      instance = clazz.newInstance();
    } catch (InstantiationException e) {
      throw new TezUncheckedException(
          "Unable to instantiate class with 0 arguments: " + clazz.getName(), e);
    } catch (IllegalAccessException e) {
      throw new TezUncheckedException(
          "Unable to instantiate class with 0 arguments: " + clazz.getName(), e);
    }
    return instance;
  }

  @Private
  public static <T> T createClazzInstance(String className) {
    Class<?> clazz = getClazz(className);
    @SuppressWarnings("unchecked")
    T instance = (T) getNewInstance(clazz);
    return instance;
  }
  
  
  @Private
  public static synchronized void addResourcesToClasspath(List<URL> urls) {
    ClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), Thread
        .currentThread().getContextClassLoader());
    Thread.currentThread().setContextClassLoader(classLoader);
  }
  
  
  // Parameters for addResourcesToSystemClassLoader
  private static final Class<?>[] parameters = new Class[]{URL.class};
  private static Method sysClassLoaderMethod = null;
  
  
  @Private  
  public static synchronized void addResourcesToSystemClassLoader(List<URL> urls) throws TezException {
    URLClassLoader sysLoader = (URLClassLoader)ClassLoader.getSystemClassLoader();
    
    if (sysClassLoaderMethod == null) {
      Class<?> sysClass = URLClassLoader.class;
      Method method;
      try {
        method = sysClass.getDeclaredMethod("addURL", parameters);
      } catch (SecurityException e) {
        throw new TezException("Failed to get handle on method addURL", e);
      } catch (NoSuchMethodException e) {
        throw new TezException("Failed to get handle on method addURL", e);
      }
      method.setAccessible(true);
      sysClassLoaderMethod = method;
    }
    for (URL url : urls) {
      try {
        sysClassLoaderMethod.invoke(sysLoader, new Object[] { url });
      } catch (IllegalArgumentException e) {
        throw new TezException("Failed to invoke addURL for rsrc: " + url, e);
      } catch (IllegalAccessException e) {
        throw new TezException("Failed to invoke addURL for rsrcs: " + urls, e);
      } catch (InvocationTargetException e) {
        throw new TezException("Failed to invoke addURL for rsrcs: " + urls, e);
      }
    }
  }
  
}
