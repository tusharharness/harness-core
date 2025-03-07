-- Copyright 2020 Harness Inc. All rights reserved.
-- Use of this source code is governed by the PolyForm Shield 1.0.0 license
-- that can be found in the licenses directory at the root of this repository, also available at
-- https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.

BEGIN;

ALTER TABLE VERIFICATION_WORKFLOW_STATS ADD COLUMN IF NOT EXISTS ENVIRONMENT_TYPE VARCHAR(20) NOT NULL DEFAULT 'PROD';
ALTER TABLE VERIFICATION_WORKFLOW_STATS ADD COLUMN IF NOT EXISTS WORKFLOW_STATUS VARCHAR(20) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE VERIFICATION_WORKFLOW_STATS ADD COLUMN IF NOT EXISTS ROLLBACK_TYPE VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE VERIFICATION_WORKFLOW_STATS ADD COLUMN IF NOT EXISTS VERIFICATION_PROVIDER_TYPE VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN';

COMMIT;

BEGIN;

CREATE INDEX IF NOT EXISTS VWS_ENVIRONMENT_INDEX ON VERIFICATION_WORKFLOW_STATS(ENVIRONMENT_TYPE, END_TIME DESC);
CREATE INDEX IF NOT EXISTS VWS_WORKFLOW_STATUS_INDEX ON VERIFICATION_WORKFLOW_STATS(WORKFLOW_STATUS, END_TIME DESC);
CREATE INDEX IF NOT EXISTS VWS_ROLLBACK_TYPE_INDEX ON VERIFICATION_WORKFLOW_STATS(ROLLBACK_TYPE, END_TIME DESC);
CREATE INDEX IF NOT EXISTS VWS_VERIFICATION_PROVIDER_TYPE_INDEX ON VERIFICATION_WORKFLOW_STATS(VERIFICATION_PROVIDER_TYPE, END_TIME DESC);

COMMIT;
