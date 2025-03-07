/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.service;

import static io.harness.rule.OwnerRule.MOHIT_GARG;

import static org.assertj.core.api.Assertions.assertThat;

import io.harness.annotations.dev.HarnessModule;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.annotations.dev.TargetModule;
import io.harness.category.element.UnitTests;
import io.harness.rule.Owner;

import software.wings.WingsBaseTest;
import software.wings.beans.Application;
import software.wings.beans.infrastructure.instance.Instance;
import software.wings.dl.WingsPersistence;
import software.wings.service.intfc.instance.DashboardStatisticsService;

import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@TargetModule(HarnessModule._870_CG_ORCHESTRATION)
@OwnedBy(HarnessTeam.PL)
public class DashboardStatisticsServiceTest extends WingsBaseTest {
  @Inject private WingsPersistence wingsPersistence;
  @Inject private DashboardStatisticsService dashboardStatisticsService;

  private static final String ACCOUNT_ID = "accountId";

  @Test
  @Owner(developers = MOHIT_GARG)
  @Category(UnitTests.class)
  public void testGetDeletedAppIds() {
    List<String> applicationIds = createApplications();
    createInstances(applicationIds);
    createInstances(applicationIds);

    List<String> deletedApplicationIds = new ArrayList<>();
    deletedApplicationIds.add(applicationIds.get(0));
    deletedApplicationIds.add(applicationIds.get(1));

    deleteApplications(deletedApplicationIds);
    Set<String> deletedAppIds = dashboardStatisticsService.getDeletedAppIds(ACCOUNT_ID, 0, System.currentTimeMillis());

    assertThat(deletedAppIds.size()).isEqualTo(2);
    deletedApplicationIds.forEach(applicationId -> assertThat(deletedAppIds.contains(applicationId)).isTrue());
  }

  private void deleteApplications(List<String> applicationIds) {
    applicationIds.forEach(applicationId -> wingsPersistence.delete(Application.class, applicationId));
  }

  private void createInstances(List<String> applicationIds) {
    for (int i = 0; i < 5; i++) {
      Instance instance = Instance.builder().accountId(ACCOUNT_ID).appId(applicationIds.get(i)).build();
      wingsPersistence.save(instance);
    }
  }

  private List<String> createApplications() {
    List<String> applicationIds = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Application application = new Application();
      application.setAccountId(ACCOUNT_ID);
      applicationIds.add(wingsPersistence.save(application));
    }
    return applicationIds;
  }
}
