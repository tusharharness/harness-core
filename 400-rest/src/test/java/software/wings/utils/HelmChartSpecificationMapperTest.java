/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.utils;

import static io.harness.rule.OwnerRule.JOHANNES;

import static org.assertj.core.api.Assertions.assertThat;

import io.harness.CategoryTest;
import io.harness.category.element.UnitTests;
import io.harness.rule.Owner;

import software.wings.beans.container.HelmChartSpecification;

import org.junit.Test;
import org.junit.experimental.categories.Category;

public class HelmChartSpecificationMapperTest extends CategoryTest {
  @Test
  @Owner(developers = JOHANNES)
  @Category(UnitTests.class)
  public void helmChartSpecificationDTOSmokeTest() {
    HelmChartSpecification helmChartSpecification =
        HelmChartSpecification.builder().chartUrl("someUrl").chartName("someName").chartVersion("someVersion").build();

    software.wings.beans.dto.HelmChartSpecification helmChartSpecificationDto =
        HelmChartSpecificationMapper.helmChartSpecificationDTO(helmChartSpecification);

    assertThat(helmChartSpecificationDto).isNotNull();
    assertThat(helmChartSpecificationDto.getChartUrl()).isEqualTo(helmChartSpecification.getChartUrl());
    assertThat(helmChartSpecificationDto.getChartName()).isEqualTo(helmChartSpecification.getChartName());
    assertThat(helmChartSpecificationDto.getChartVersion()).isEqualTo(helmChartSpecification.getChartVersion());
  }

  @Test
  @Owner(developers = JOHANNES)
  @Category(UnitTests.class)
  public void helmChartSpecificationDTOForNull() {
    assertThat(HelmChartSpecificationMapper.helmChartSpecificationDTO(null)).isNull();
  }
}
