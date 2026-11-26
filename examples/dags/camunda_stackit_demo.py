# Copyright 2026 envite consulting GmbH and the STACKIT Connectors for
# Camunda 8 contributors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Demo DAG for the STACKIT Workflows connector for Camunda 8.

Triggered by ``examples/bpmn/stackit-workflows-example.bpmn`` and by the connector's live tests.
It needs nothing but Airflow itself - no providers, connections or variables - and runs unchanged
on Airflow 2.11 and 3.1, the two versions STACKIT Workflows offers.

``dag_run.conf`` (all optional):

    orderId       string   shows up in the logs and in the result      default "demo-order"
    delaySeconds  number   how long ``prepare`` takes, 0 to 600        default 20
    shouldFail    boolean  makes ``export`` raise, so the run fails    default false

On success ``export`` returns ``{"orderId": ..., "exportedRecords": 42}``, which Airflow stores as
the XCom ``return_value`` of ``export``. On failure ``export`` raises without retries, so the run
turns ``failed`` at once and ``export`` is the task to read the log of.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime, timezone

# Airflow 3 moved the DAG authoring API to ``airflow.sdk``; ``airflow.decorators`` still works
# there but is deprecated. Airflow 2.11 has no ``airflow.sdk``. Import whichever this Airflow has.
try:
    from airflow.sdk import dag, task
except ImportError:  # Airflow 2
    from airflow.decorators import dag, task

DAG_ID = "camunda_stackit_demo"
DEFAULT_ORDER_ID = "demo-order"
DEFAULT_DELAY_SECONDS = 20
MAX_DELAY_SECONDS = 600
EXPORTED_RECORDS = 42

# "airflow.task" is the logger Airflow 2 routes into the task log; Airflow 3 captures it as well.
log = logging.getLogger("airflow.task")


def _settings(conf: dict | None) -> tuple[str, int, bool]:
    """Reads the three demo settings from dag_run.conf, tolerating strings from a hand-written conf."""
    conf = conf or {}
    order_id = str(conf.get("orderId") or DEFAULT_ORDER_ID)
    try:
        delay = int(float(conf.get("delaySeconds", DEFAULT_DELAY_SECONDS)))
    except (TypeError, ValueError):
        delay = DEFAULT_DELAY_SECONDS
    delay = max(0, min(delay, MAX_DELAY_SECONDS))
    should_fail = conf.get("shouldFail", False)
    if isinstance(should_fail, str):
        should_fail = should_fail.strip().lower() in ("true", "1", "yes")
    return order_id, delay, bool(should_fail)


@dag(
    dag_id=DAG_ID,
    description="Demo DAG for the STACKIT Workflows connector for Camunda 8",
    schedule=None,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    catchup=False,
    is_paused_upon_creation=False,
    max_active_runs=16,
    default_args={"retries": 0},
    tags=["camunda", "stackit", "demo"],
)
def camunda_stackit_demo():
    # multiple_outputs=False: Airflow 2 would otherwise infer True from the dict annotation and push
    # every key as an XCom of its own. The result is wanted as one return_value.
    @task(multiple_outputs=False)
    def prepare(**context) -> dict:
        order_id, delay, should_fail = _settings(context["dag_run"].conf)
        # Only the three known settings are logged, never the whole conf: a real conf may carry
        # data that has no business in a log.
        log.info(
            "Preparing export of order %s (delaySeconds=%s, shouldFail=%s)",
            order_id,
            delay,
            should_fail,
        )
        for elapsed in range(0, delay, 5):
            log.info("Order %s: preparing, %s of %s seconds", order_id, elapsed, delay)
            time.sleep(min(5, delay - elapsed))
        log.info("Order %s prepared", order_id)
        return {"orderId": order_id, "shouldFail": should_fail}

    @task(multiple_outputs=False)
    def export(prepared: dict) -> dict:
        order_id = prepared["orderId"]
        log.info("Exporting order %s", order_id)
        if prepared["shouldFail"]:
            log.error("Export of order %s failed, as requested by shouldFail=true", order_id)
            raise RuntimeError(
                f"Export of order {order_id} failed: the demo was asked to fail (shouldFail=true)"
            )
        log.info("Exported %s records of order %s", EXPORTED_RECORDS, order_id)
        return {"orderId": order_id, "exportedRecords": EXPORTED_RECORDS}

    export(prepare())


camunda_stackit_demo()
