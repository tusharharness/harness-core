/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.cdng.creator;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.executions.steps.StepSpecTypeConstants;

import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Set;
import lombok.experimental.UtilityClass;

@UtilityClass
@OwnedBy(HarnessTeam.PIPELINE)
public class CDCreatorUtils {
  public Set<String> getSupportedSteps() {
    return Collections.emptySet();
  }
  public Set<String> getSupportedStepsV2() {
    return Sets.newHashSet(StepSpecTypeConstants.GITOPS_CREATE_PR, StepSpecTypeConstants.GITOPS_MERGE_PR,
        StepSpecTypeConstants.K8S_CANARY_DEPLOY, StepSpecTypeConstants.K8S_APPLY,
        StepSpecTypeConstants.K8S_BLUE_GREEN_DEPLOY, StepSpecTypeConstants.K8S_ROLLING_DEPLOY,
        StepSpecTypeConstants.K8S_ROLLING_ROLLBACK, StepSpecTypeConstants.K8S_SCALE, StepSpecTypeConstants.K8S_DELETE,
        StepSpecTypeConstants.K8S_BG_SWAP_SERVICES, StepSpecTypeConstants.K8S_CANARY_DELETE,
        StepSpecTypeConstants.TERRAFORM_APPLY, StepSpecTypeConstants.TERRAFORM_PLAN,
        StepSpecTypeConstants.TERRAFORM_DESTROY, StepSpecTypeConstants.TERRAFORM_ROLLBACK,
        StepSpecTypeConstants.HELM_DEPLOY, StepSpecTypeConstants.HELM_ROLLBACK,
        StepSpecTypeConstants.CLOUDFORMATION_CREATE_STACK, StepSpecTypeConstants.CLOUDFORMATION_DELETE_STACK,
        StepSpecTypeConstants.SERVERLESS_AWS_LAMBDA_DEPLOY, StepSpecTypeConstants.SERVERLESS_AWS_LAMBDA_ROLLBACK,
        StepSpecTypeConstants.CLOUDFORMATION_ROLLBACK_STACK, StepSpecTypeConstants.COMMAND,
        StepSpecTypeConstants.AZURE_CREATE_ARM_RESOURCE, StepSpecTypeConstants.AZURE_CREATE_BP_RESOURCE,
        StepSpecTypeConstants.AZURE_ROLLBACK_ARM_RESOURCE, StepSpecTypeConstants.AZURE_SLOT_DEPLOYMENT,
        StepSpecTypeConstants.AZURE_TRAFFIC_SHIFT, StepSpecTypeConstants.AZURE_SWAP_SLOT,
        StepSpecTypeConstants.AZURE_WEBAPP_ROLLBACK, StepSpecTypeConstants.JENKINS_BUILD);
  }
}
