/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.delegate.task.azure.appservice.webapp.ng.request;

import static io.harness.annotations.dev.HarnessTeam.CDP;

import io.harness.annotations.dev.OwnedBy;
import io.harness.delegate.beans.connector.artifactoryconnector.ArtifactoryCapabilityHelper;
import io.harness.delegate.beans.connector.azureconnector.AzureCapabilityHelper;
import io.harness.delegate.beans.connector.docker.DockerCapabilityHelper;
import io.harness.delegate.beans.executioncapability.ExecutionCapability;
import io.harness.delegate.beans.logstreaming.CommandUnitsProgress;
import io.harness.delegate.task.azure.appservice.AzureAppServicePreDeploymentData;
import io.harness.delegate.task.azure.appservice.settings.AppSettingsFile;
import io.harness.delegate.task.azure.appservice.webapp.ng.AzureWebAppInfraDelegateConfig;
import io.harness.delegate.task.azure.appservice.webapp.ng.AzureWebAppRequestType;
import io.harness.delegate.task.azure.artifact.AzureArtifactConfig;
import io.harness.delegate.task.azure.artifact.AzureArtifactType;
import io.harness.delegate.task.azure.artifact.AzureContainerArtifactConfig;
import io.harness.expression.ExpressionEvaluator;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@OwnedBy(CDP)
@EqualsAndHashCode(callSuper = true)
public class AzureWebAppSlotDeploymentRequest extends AbstractSlotDataRequest {
  private AzureAppServicePreDeploymentData preDeploymentData;

  @Builder
  public AzureWebAppSlotDeploymentRequest(String accountId, AzureAppServicePreDeploymentData preDeploymentData,
      CommandUnitsProgress commandUnitsProgress, AzureWebAppInfraDelegateConfig infrastructure,
      AppSettingsFile startupCommand, AppSettingsFile applicationSettings, AppSettingsFile connectionStrings,
      AzureArtifactConfig artifact, Integer timeoutIntervalInMin) {
    super(accountId, commandUnitsProgress, infrastructure, startupCommand, applicationSettings, connectionStrings,
        artifact, timeoutIntervalInMin);
    this.preDeploymentData = preDeploymentData;
  }

  @Override
  public AzureWebAppRequestType getRequestType() {
    return AzureWebAppRequestType.SLOT_DEPLOYMENT;
  }

  @Override
  protected void populateRequestCapabilities(
      List<ExecutionCapability> capabilities, ExpressionEvaluator maskingEvaluator) {
    super.populateRequestCapabilities(capabilities, maskingEvaluator);
    AzureArtifactConfig artifactConfig = getArtifact();
    if (artifactConfig != null) {
      if (artifactConfig.getArtifactType() == AzureArtifactType.CONTAINER) {
        AzureContainerArtifactConfig azureContainerArtifactConfig = (AzureContainerArtifactConfig) artifactConfig;
        switch (azureContainerArtifactConfig.getRegistryType()) {
          case DOCKER_HUB_PUBLIC:
          case DOCKER_HUB_PRIVATE:
            capabilities.addAll(DockerCapabilityHelper.fetchRequiredExecutionCapabilities(
                azureContainerArtifactConfig.getConnectorConfig(), maskingEvaluator));
            break;
          case ARTIFACTORY_PRIVATE_REGISTRY:
            capabilities.addAll(ArtifactoryCapabilityHelper.fetchRequiredExecutionCapabilities(
                azureContainerArtifactConfig.getConnectorConfig(), maskingEvaluator));
            break;
          case ACR:
            capabilities.addAll(AzureCapabilityHelper.fetchRequiredExecutionCapabilities(
                azureContainerArtifactConfig.getConnectorConfig(), maskingEvaluator));
            break;
          default:
            // no capabilities to add
        }
      }
    }
  }
}
