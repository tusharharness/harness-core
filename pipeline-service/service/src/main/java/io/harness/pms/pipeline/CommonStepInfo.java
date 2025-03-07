/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.pms.pipeline;

import static io.harness.annotations.dev.HarnessTeam.PIPELINE;

import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.FeatureName;
import io.harness.enforcement.constants.FeatureRestrictionName;
import io.harness.pms.contracts.steps.StepInfo;
import io.harness.pms.contracts.steps.StepMetaData;
import io.harness.pms.helpers.PmsFeatureFlagHelper;
import io.harness.steps.FolderPathConstants;
import io.harness.steps.StepCategoryConstants;
import io.harness.steps.StepSpecTypeConstants;
import io.harness.steps.policy.PolicyStepConstants;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@OwnedBy(PIPELINE)
@Slf4j
@Singleton
public class CommonStepInfo {
  @Inject PmsFeatureFlagHelper pmsFeatureFlagHelper;

  StepInfo shellScriptStepInfo =
      StepInfo.newBuilder()
          .setName("Shell Script")
          .setType(StepSpecTypeConstants.SHELL_SCRIPT)
          .setStepMetaData(StepMetaData.newBuilder().addFolderPaths("Utilities/Scripted").build())
          .build();
  StepInfo httpStepInfo =
      StepInfo.newBuilder()
          .setName("HTTP")
          .setType("Http")
          .setStepMetaData(StepMetaData.newBuilder().addFolderPaths("Utilities/Non-Scripted").build())
          .build();
  StepInfo emailStepInfo =
      StepInfo.newBuilder()
          .setName("Email")
          .setType("Email")
          .setStepMetaData(StepMetaData.newBuilder().addFolderPaths("Utilities/Non-Scripted").build())
          .setFeatureFlag(FeatureName.NG_EMAIL_STEP.name())
          .build();
  StepInfo harnessApprovalStepInfo =
      StepInfo.newBuilder()
          .setName("Harness Approval")
          .setType("HarnessApproval")
          .setStepMetaData(StepMetaData.newBuilder()
                               .addCategory(StepCategoryConstants.PROVISIONER)
                               .addCategory(StepCategoryConstants.APPROVAL)
                               .addFolderPaths(FolderPathConstants.APPROVAL)
                               .build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_HARNESS_UI.name())
          .build();
  StepInfo customApprovalStepInfo =
      StepInfo.newBuilder()
          .setName("Custom Approval")
          .setType("CustomApproval")
          .setStepMetaData(StepMetaData.newBuilder()
                               .addCategory(StepCategoryConstants.PROVISIONER)
                               .addCategory(StepCategoryConstants.APPROVAL)
                               .addFolderPaths(FolderPathConstants.APPROVAL)
                               .build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_CUSTOM_SCRIPT.name())
          .setFeatureFlag(FeatureName.NG_CUSTOM_APPROVAL.name())
          .build();
  StepInfo jiraApprovalStepInfo =
      StepInfo.newBuilder()
          .setName("Jira Approval")
          .setType("JiraApproval")
          .setStepMetaData(StepMetaData.newBuilder()
                               .addCategory(StepCategoryConstants.PROVISIONER)
                               .addCategory(StepCategoryConstants.APPROVAL)
                               .addFolderPaths(FolderPathConstants.APPROVAL)
                               .build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_JIRA.name())

          .build();
  StepInfo jiraCreateStepInfo =
      StepInfo.newBuilder()
          .setName("Jira Create")
          .setType(StepSpecTypeConstants.JIRA_CREATE)
          .setStepMetaData(StepMetaData.newBuilder().addCategory("Jira").addFolderPaths("Jira").build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_JIRA.name())

          .build();
  StepInfo jiraUpdateStepInfo =
      StepInfo.newBuilder()
          .setName("Jira Update")
          .setType(StepSpecTypeConstants.JIRA_UPDATE)
          .setStepMetaData(StepMetaData.newBuilder().addCategory("Jira").addFolderPaths("Jira").build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_JIRA.name())

          .build();
  StepInfo barrierStepInfo =
      StepInfo.newBuilder()
          .setName("Barrier")
          .setType("Barrier")
          .setStepMetaData(StepMetaData.newBuilder().addFolderPaths("FlowControl/Barrier").build())
          .build();
  StepInfo queueStepInfo = StepInfo.newBuilder()
                               .setName("Queue")
                               .setType("Queue")
                               .setStepMetaData(StepMetaData.newBuilder().addFolderPaths("FlowControl/Queue").build())
                               .setFeatureFlag(FeatureName.PIPELINE_QUEUE_STEP.name())
                               .build();
  StepInfo serviceNowApprovalStepInfo =
      StepInfo.newBuilder()
          .setName("ServiceNow Approval")
          .setType(StepSpecTypeConstants.SERVICENOW_APPROVAL)
          .setStepMetaData(StepMetaData.newBuilder()
                               .addCategory(StepCategoryConstants.PROVISIONER)
                               .addCategory(StepCategoryConstants.APPROVAL)
                               .addFolderPaths(FolderPathConstants.APPROVAL)
                               .build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_SERVICE_NOW.name())
          .build();

  StepInfo policyStepInfo = StepInfo.newBuilder()
                                .setName(PolicyStepConstants.POLICY_STEP_NAME)
                                .setType(StepSpecTypeConstants.POLICY_STEP)
                                .setFeatureFlag(FeatureName.CUSTOM_POLICY_STEP.name())
                                .setStepMetaData(StepMetaData.newBuilder()
                                                     .addCategory(PolicyStepConstants.POLICY_STEP_CATEGORY)
                                                     .addFolderPaths(PolicyStepConstants.POLICY_STEP_FOLDER_PATH)
                                                     .build())
                                .build();

  StepInfo serviceNowCreateStepInfo =
      StepInfo.newBuilder()
          .setName("ServiceNow Create")
          .setType(StepSpecTypeConstants.SERVICENOW_CREATE)
          .setStepMetaData(StepMetaData.newBuilder()
                               .addCategory(StepCategoryConstants.SERVICENOW)
                               .addFolderPaths(FolderPathConstants.SERVICENOW)
                               .build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_SERVICE_NOW.name())
          .build();

  StepInfo serviceNowUpdateStepInfo =
      StepInfo.newBuilder()
          .setName("ServiceNow Update")
          .setType(StepSpecTypeConstants.SERVICENOW_UPDATE)
          .setStepMetaData(StepMetaData.newBuilder()
                               .addCategory(StepCategoryConstants.SERVICENOW)
                               .addFolderPaths(FolderPathConstants.SERVICENOW)
                               .build())
          .setFeatureRestrictionName(FeatureRestrictionName.INTEGRATED_APPROVALS_WITH_SERVICE_NOW.name())
          .build();

  public List<StepInfo> getCommonSteps(String category) {
    List<StepInfo> stepInfos = new ArrayList<>();
    stepInfos.add(shellScriptStepInfo);
    stepInfos.add(httpStepInfo);
    stepInfos.add(harnessApprovalStepInfo);
    stepInfos.add(customApprovalStepInfo);
    stepInfos.add(jiraApprovalStepInfo);
    stepInfos.add(jiraCreateStepInfo);
    stepInfos.add(jiraUpdateStepInfo);
    stepInfos.add(barrierStepInfo);
    stepInfos.add(queueStepInfo);
    stepInfos.add(serviceNowApprovalStepInfo);
    stepInfos.add(policyStepInfo);
    stepInfos.add(serviceNowCreateStepInfo);
    stepInfos.add(serviceNowUpdateStepInfo);
    stepInfos.add(emailStepInfo);
    return stepInfos;
  }
}
