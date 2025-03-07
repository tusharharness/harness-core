/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.delegate.beans;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

@OwnedBy(HarnessTeam.DEL)
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class DelegateSelectionLogParams {
  private String delegateId;
  private String delegateType;
  private String delegateName;
  private String delegateHostName;
  private String delegateProfileName;
  private String conclusion;
  private String message;
  private long eventTimestamp;
  private ProfileScopingRulesDetails profileScopingRulesDetails;
}
