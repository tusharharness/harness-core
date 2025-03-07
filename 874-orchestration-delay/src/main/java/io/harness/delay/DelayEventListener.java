/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.delay;

import static io.harness.annotations.dev.HarnessTeam.CDC;

import io.harness.annotations.dev.OwnedBy;
import io.harness.queue.QueueConsumer;
import io.harness.queue.QueueListener;
import io.harness.waiter.WaitNotifyEngine;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@OwnedBy(CDC)
@Slf4j
public class DelayEventListener extends QueueListener<DelayEvent> {
  @Inject private WaitNotifyEngine waitNotifyEngine;

  @Inject
  public DelayEventListener(QueueConsumer<DelayEvent> queueConsumer) {
    super(queueConsumer, false);
  }

  @Override
  public void onMessage(DelayEvent message) {
    log.info("Notifying for DelayEvent with resumeId {}", message.getResumeId());

    waitNotifyEngine.doneWith(
        message.getResumeId(), DelayEventNotifyData.builder().context(message.getContext()).build());
  }
}
