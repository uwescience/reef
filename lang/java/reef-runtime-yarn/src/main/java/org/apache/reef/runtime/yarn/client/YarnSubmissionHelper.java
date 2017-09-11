/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.reef.runtime.yarn.client;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.reef.runtime.common.REEFLauncher;
import org.apache.reef.runtime.common.files.ClasspathProvider;
import org.apache.reef.runtime.common.files.REEFFileNames;
import org.apache.reef.runtime.common.launch.JavaLaunchCommandBuilder;
import org.apache.reef.runtime.yarn.client.unmanaged.YarnProxyUser;
import org.apache.reef.runtime.yarn.util.YarnTypes;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper code that wraps the YARN Client API for our purposes.
 */
public final class YarnSubmissionHelper implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(YarnSubmissionHelper.class.getName());

  private final YarnClient yarnClient;
  private final GetNewApplicationResponse applicationResponse;
  private final ApplicationSubmissionContext applicationSubmissionContext;
  private final ApplicationId applicationId;
  private final Map<String, LocalResource> resources = new HashMap<>();
  private final ClasspathProvider classpath;
  private final YarnProxyUser yarnProxyUser;
  private final SecurityTokenProvider tokenProvider;
  private final boolean isUnmanaged;
  private final List<String> commandPrefixList;

  private String driverStdoutFilePath;
  private String driverStderrFilePath;
  private Class launcherClazz = REEFLauncher.class;
  private List<String> configurationFilePaths;
  private int driverMemoryMB;
  private String driverHostName;

  public YarnSubmissionHelper(final YarnConfiguration yarnConfiguration,
                              final REEFFileNames fileNames,
                              final ClasspathProvider classpath,
                              final YarnProxyUser yarnProxyUser,
                              final SecurityTokenProvider tokenProvider,
                              final boolean isUnmanaged,
                              final List<String> commandPrefixList) throws IOException, YarnException {

    this.classpath = classpath;
    this.yarnProxyUser = yarnProxyUser;
    this.isUnmanaged = isUnmanaged;

    this.driverStdoutFilePath =
        ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + fileNames.getDriverStdoutFileName();

    this.driverStderrFilePath =
        ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + fileNames.getDriverStderrFileName();

    LOG.log(Level.FINE, "Initializing YARN Client");
    this.yarnClient = YarnClient.createYarnClient();
    this.yarnClient.init(yarnConfiguration);
    this.yarnClient.start();
    LOG.log(Level.FINE, "Initialized YARN Client");

    LOG.log(Level.FINE, "Requesting Application ID from YARN.");
    final YarnClientApplication yarnClientApplication = this.yarnClient.createApplication();
    this.applicationResponse = yarnClientApplication.getNewApplicationResponse();
    this.applicationSubmissionContext = yarnClientApplication.getApplicationSubmissionContext();
    this.applicationSubmissionContext.setUnmanagedAM(isUnmanaged);
    this.applicationId = this.applicationSubmissionContext.getApplicationId();
    this.tokenProvider = tokenProvider;
    this.commandPrefixList = commandPrefixList;
    this.configurationFilePaths = Collections.singletonList(fileNames.getDriverConfigurationPath());
    LOG.log(Level.INFO, "YARN Application ID: {0}", this.applicationId);
  }

  public YarnSubmissionHelper(final YarnConfiguration yarnConfiguration,
                              final REEFFileNames fileNames,
                              final ClasspathProvider classpath,
                              final YarnProxyUser yarnProxyUser,
                              final SecurityTokenProvider tokenProvider,
                              final boolean isUnmanaged) throws IOException, YarnException {
    this(yarnConfiguration, fileNames, classpath, yarnProxyUser, tokenProvider, isUnmanaged, null);
  }

  /**
   *
   * @return the application ID assigned by YARN.
   */
  public int getApplicationId() {
    return this.applicationId.getId();
  }

  /**
   *
   * @return the application ID string representation assigned by YARN.
   */
  public String getStringApplicationId() {
    return this.applicationId.toString();
  }

  /**
   * Set the name of the application to be submitted.
   * @param applicationName
   * @return
   */
  public YarnSubmissionHelper setApplicationName(final String applicationName) {
    applicationSubmissionContext.setApplicationName(applicationName);
    return this;
  }

  /**
   * Set the amount of memory to be allocated to the Driver.
   * @param megabytes
   * @return
   */
  public YarnSubmissionHelper setDriverMemory(final int megabytes) {
    this.driverMemoryMB = getMemory(megabytes);
    return this;
  }

  /**
   * Set the amount of memory to be allocated to the Driver.
   * @param megabytes
   * @return
   */
  public YarnSubmissionHelper setDriverNode(final String hostName) {
    this.driverHostName = hostName;
    return this;
  }

  /**
   * Add a file to be localized on the driver.
   * @param resourceName
   * @param resource
   * @return
   */
  public YarnSubmissionHelper addLocalResource(final String resourceName, final LocalResource resource) {
    resources.put(resourceName, resource);
    return this;
  }

  /**
   * Set the priority of the job.
   * @param priority
   * @return
   */
  public YarnSubmissionHelper setPriority(final int priority) {
    this.applicationSubmissionContext.setPriority(Priority.newInstance(priority));
    return this;
  }

  /**
   * Set whether or not the resource manager should preserve evaluators across driver restarts.
   * @param preserveEvaluators
   * @return
   */
  public YarnSubmissionHelper setPreserveEvaluators(final boolean preserveEvaluators) {
    if (preserveEvaluators) {
      // when supported, set KeepContainersAcrossApplicationAttempts to be true
      // so that when driver (AM) crashes, evaluators will still be running and we can recover later.
      if (YarnTypes.isAtOrAfterVersion(YarnTypes.MIN_VERSION_KEEP_CONTAINERS_AVAILABLE)) {
        LOG.log(
            Level.FINE,
            "Hadoop version is {0} or after with KeepContainersAcrossApplicationAttempts supported," +
                " will set it to true.",
            YarnTypes.MIN_VERSION_KEEP_CONTAINERS_AVAILABLE);

        applicationSubmissionContext.setKeepContainersAcrossApplicationAttempts(true);
      } else {
        LOG.log(Level.WARNING,
            "Hadoop version does not yet support KeepContainersAcrossApplicationAttempts. Driver restarts " +
                "will not support recovering evaluators.");

        applicationSubmissionContext.setKeepContainersAcrossApplicationAttempts(false);
      }
    } else {
      applicationSubmissionContext.setKeepContainersAcrossApplicationAttempts(false);
    }

    return this;
  }

  /**
   * Sets the maximum application attempts for the application.
   * @param maxApplicationAttempts
   * @return
   */
  public YarnSubmissionHelper setMaxApplicationAttempts(final int maxApplicationAttempts) {
    applicationSubmissionContext.setMaxAppAttempts(maxApplicationAttempts);
    return this;
  }

  /**
   * Assign this job submission to a queue.
   * @param queueName
   * @return
   */
  public YarnSubmissionHelper setQueue(final String queueName) {
    this.applicationSubmissionContext.setQueue(queueName);
    return this;
  }

  /**
   * Sets the launcher class for the job.
   * @param launcherClass
   * @return
   */
  public YarnSubmissionHelper setLauncherClass(final Class launcherClass) {
    this.launcherClazz = launcherClass;
    return this;
  }

  /**
   * Sets the configuration file for the job.
   * Note that this does not have to be Driver TANG configuration. In the bootstrap
   * launch case, this can be the set of  the Avro files that supports the generation of a driver
   * configuration file natively at the Launcher.
   * @param configurationFilePaths
   * @return
   */
  public YarnSubmissionHelper setConfigurationFilePaths(final List<String> configurationFilePaths) {
    this.configurationFilePaths = configurationFilePaths;
    return this;
  }

  /**
   * Sets the Driver stdout file path.
   * @param driverStdoutPath
   * @return
   */
  public YarnSubmissionHelper setDriverStdoutPath(final String driverStdoutPath) {
    this.driverStdoutFilePath = driverStdoutPath;
    return this;
  }

  /**
   * Sets the Driver stderr file path.
   * @param driverStderrPath
   * @return
   */
  public YarnSubmissionHelper setDriverStderrPath(final String driverStderrPath) {
    this.driverStderrFilePath = driverStderrPath;
    return this;
  }

  public void submit() throws IOException, YarnException {
    // We always set `relaxLocality` to `false` if `setDriverNode()` is called with a non-wildcard argument.
    final boolean relaxLocality = this.driverHostName.equals("*");
    final int vcores = 1;
    final int numContainers = 1;
    // According to docs for `ApplicationSubmissionContext.getAMContainerResourceRequest()`,
    // the arguments `priority` and `numContainers` to `ResourceRequest.newInstance()` are ignored.
    this.applicationSubmissionContext.setAMContainerResourceRequest(
        ResourceRequest.newInstance(
            Priority.UNDEFINED, this.driverHostName, Resource.newInstance(
                this.driverMemoryMB, vcores), numContainers, relaxLocality));

    // SET EXEC COMMAND
    final List<String> launchCommand = new JavaLaunchCommandBuilder(launcherClazz, commandPrefixList)
        .setConfigurationFilePaths(configurationFilePaths)
        .setClassPath(this.classpath.getDriverClasspath())
        .setMemory(this.applicationSubmissionContext.getAMContainerResourceRequest().getCapability().getMemory())
        .setStandardOut(driverStdoutFilePath)
        .setStandardErr(driverStderrFilePath)
        .build();

    if (this.applicationSubmissionContext.getKeepContainersAcrossApplicationAttempts() &&
        this.applicationSubmissionContext.getMaxAppAttempts() == 1) {
      LOG.log(Level.WARNING, "Application will not be restarted even though preserve evaluators is set to true" +
          " since the max application submissions is 1. Proceeding to submit application...");
    }

    final ContainerLaunchContext containerLaunchContext = YarnTypes.getContainerLaunchContext(
        launchCommand, this.resources, tokenProvider.getTokens());
    this.applicationSubmissionContext.setAMContainerSpec(containerLaunchContext);

    LOG.log(Level.INFO, "Submitting REEF Application to YARN. ID: {0}", this.applicationId);

    if (LOG.isLoggable(Level.INFO)) {
      LOG.log(Level.INFO, "REEF app command: {0}", StringUtils.join(launchCommand, ' '));
    }

    this.yarnClient.submitApplication(applicationSubmissionContext);

    if (this.isUnmanaged) {
      // For Unmanaged AM mode, add a new app token to the
      // current process so it can talk to the RM as an AM.
      final Token<AMRMTokenIdentifier> token = this.yarnClient.getAMRMToken(this.applicationId);
      this.yarnProxyUser.set("reef-proxy", UserGroupInformation.getCurrentUser(), token);
      this.tokenProvider.addTokens(UserCredentialSecurityTokenProvider.serializeToken(token));
    }
  }

  /**
   * Extract the desired driver memory from jobSubmissionProto.
   * <p>
   * returns maxMemory if that desired amount is more than maxMemory
   */
  private int getMemory(final int requestedMemory) {
    final int maxMemory = applicationResponse.getMaximumResourceCapability().getMemory();
    final int amMemory;

    if (requestedMemory <= maxMemory) {
      amMemory = requestedMemory;
    } else {
      LOG.log(Level.WARNING,
          "Requested {0}MB of memory for the driver. " +
              "The max on this YARN installation is {1}. " +
              "Using {1} as the memory for the driver.",
          new Object[]{requestedMemory, maxMemory});
      amMemory = maxMemory;
    }
    return amMemory;
  }

  @Override
  public void close() {
    LOG.log(Level.FINE, "Closing YARN application: {0}", this.applicationId);
    this.yarnClient.stop(); // same as yarnClient.close()
  }
}
