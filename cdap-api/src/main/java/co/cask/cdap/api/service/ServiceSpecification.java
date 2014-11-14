/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.api.service;

import co.cask.cdap.api.ProgramSpecification;
import co.cask.cdap.api.Resources;
import co.cask.cdap.api.service.http.HttpServiceHandlerSpecification;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Specification for a {@link Service}.
 */
public final class ServiceSpecification implements ProgramSpecification {
  private final String className;
  private final String name;
  private final String description;
  private final Map<String, HttpServiceHandlerSpecification> handlers;
  private final Map<String, ServiceWorkerSpecification> workers;
  private final Resources resources;
  private final int instances;
  private final boolean local;

  public ServiceSpecification(String className, String name, String description,
                              Map<String, HttpServiceHandlerSpecification> handlers,
                              Map<String, ServiceWorkerSpecification> workers,
                              Resources resources, int instances, boolean local) {
    this.className = className;
    this.name = name;
    this.description = description;
    this.handlers = Collections.unmodifiableMap(new HashMap<String, HttpServiceHandlerSpecification>(handlers));
    this.workers = Collections.unmodifiableMap(new HashMap<String, ServiceWorkerSpecification>(workers));
    this.resources = resources;
    this.instances = instances;
    this.local = local;
  }

  @Override
  public String getClassName() {
    return className;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  /**
   * Returns an immutable map from handler name to handler specification.
   */
  public Map<String, HttpServiceHandlerSpecification> getHandlers() {
    return handlers;
  }

  /**
   * Returns the number of instances for the service handler.
   */
  public int getInstances() {
    return instances;
  }

  /**
   * Returns an immutable map from worker name to worker specification.
   */
  public Map<String, ServiceWorkerSpecification> getWorkers() {
    return workers;
  }

  /**
   * Returns the resources requirements for the service handler.
   */
  public Resources getResources() {
    return resources;
  }

  /**
   * Returns the visibility scope of the Service.
   * @return true if visible only within the same application, false otherwise.
   */
  public boolean isLocal() {
    return local;
  }
}
