#!/usr/bin/env python3
"""Generates the Grafana dashboards in dashboards/ (LLD section 15).

Edit this file, not the JSON: python3 infra/observability/grafana/generate_dashboards.py
One dashboard per flow, plus an overview and a platform dashboard. Every panel reads Prometheus,
except the log panels (Loki). Traces are opened from Tempo via exemplars and the log lines' trace IDs.
"""
import json
import pathlib

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}
OUT = pathlib.Path(__file__).parent / "dashboards"


class Board:
    def __init__(self, uid, title, description, variables=()):
        self.uid, self.title, self.description = uid, title, description
        self.panels, self.variables = [], list(variables)
        self.x = self.y = self.row_h = 0
        self.next_id = 1

    def _place(self, w, h):
        if self.x + w > 24:
            self.x, self.y = 0, self.y + self.row_h
            self.row_h = 0
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def row(self, title):
        if self.x:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        self._add({"type": "row", "title": title, "collapsed": False, "panels": [],
                   "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}})
        self.y += 1

    def _add(self, panel):
        panel["id"] = self.next_id
        self.next_id += 1
        self.panels.append(panel)

    def ts(self, title, targets, unit="short", w=12, h=8, stack=False, thresholds=None, desc=""):
        defaults = {"unit": unit, "custom": {"fillOpacity": 25 if stack else 8, "lineWidth": 2,
                                             "stacking": {"mode": "normal" if stack else "none"},
                                             "showPoints": "never"}}
        if thresholds:
            defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
            defaults["custom"]["thresholdsStyle"] = {"mode": "dashed"}
        self._add({"type": "timeseries", "title": title, "description": desc, "datasource": PROM,
                   "gridPos": self._place(w, h), "targets": _targets(targets),
                   "fieldConfig": {"defaults": defaults, "overrides": []},
                   "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["lastNotNull"]},
                               "tooltip": {"mode": "multi"}}})

    def stat(self, title, expr, unit="short", w=4, h=4, red_above=None, desc=""):
        steps = [{"color": "green", "value": None}]
        if red_above is not None:
            steps.append({"color": "red", "value": red_above})
        self._add({"type": "stat", "title": title, "description": desc, "datasource": PROM,
                   "gridPos": self._place(w, h), "targets": _targets([(expr, "")]),
                   "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}},
                                   "overrides": []},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background",
                               "graphMode": "area"}})

    def table(self, title, expr, w=12, h=8, desc=""):
        self._add({"type": "table", "title": title, "description": desc, "datasource": PROM,
                   "gridPos": self._place(w, h),
                   "targets": [{"expr": expr, "refId": "A", "instant": True, "format": "table"}],
                   "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True}}}],
                   "fieldConfig": {"defaults": {}, "overrides": []}})

    def logs(self, title, expr, w=24, h=10):
        self._add({"type": "logs", "title": title, "datasource": LOKI, "gridPos": self._place(w, h),
                   "targets": [{"expr": expr, "refId": "A"}],
                   "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending"}})

    def text(self, content, w=24, h=4, title=""):
        self._add({"type": "text", "title": title, "gridPos": self._place(w, h),
                   "options": {"mode": "markdown", "content": content}})

    def json(self):
        return {"uid": self.uid, "title": self.title, "description": self.description,
                "tags": ["pbp"], "timezone": "browser", "schemaVersion": 39, "editable": False,
                "time": {"from": "now-1h", "to": "now"}, "refresh": "30s",
                "links": [{"type": "dashboards", "tags": ["pbp"], "asDropdown": True, "title": "PBP dashboards",
                           "includeVars": False, "keepTime": True}],
                "templating": {"list": self.variables}, "annotations": {"list": []},
                "panels": self.panels}


def _targets(pairs):
    return [{"expr": e, "legendFormat": l, "refId": chr(65 + i), "exemplar": True} for i, (e, l) in enumerate(pairs)]


def query_var(name, label, query, include_all=True):
    return {"name": name, "label": label, "type": "query", "datasource": PROM, "refresh": 2,
            "query": {"query": query, "refId": "v"}, "definition": query, "includeAll": include_all,
            "multi": include_all, "allValue": ".*", "current": {"text": "All", "value": "$__all"} if include_all else {}}


def textbox(name, label):
    return {"name": name, "label": label, "type": "textbox", "query": "", "current": {"text": "", "value": ""}}


def p(q, metric, by, rng="5m", where=""):
    """histogram_quantile over a Micrometer timer."""
    return f'histogram_quantile({q}, sum by (le{", " + by if by else ""}) (rate({metric}_bucket{{{where}}}[{rng}])))'


# ---------------------------------------------------------------- overview
overview = Board("pbp-overview", "PBP · Overview",
                 "Every flow at a glance, and where to start when something looks wrong.",
                 [textbox("cid", "Correlation ID")])
overview.stat("Services up", 'count(up{job="pbp"} == 1)', desc="Of 9: eight services and the vendor simulator")
overview.stat("Alerts firing", 'count(ALERTS{alertstate="firing"}) or vector(0)', red_above=1)
overview.stat("Payments completed (1h)", 'sum(increase(pbp_payment_transitions_total{status="COMPLETED"}[1h])) or vector(0)')
overview.stat("Card decisions (1h)", 'sum(increase(pbp_ehi_decision_seconds_count[1h])) or vector(0)')
overview.stat("Open payments", 'sum(max without (instance, service) (pbp_payments_open)) or vector(0)')
overview.stat("Recon exceptions", 'sum(pbp_recon_exceptions_open) or vector(0)', red_above=1)
overview.row("Flows")
overview.ts("Payments by status /min", [('sum by (status) (rate(pbp_payment_transitions_total[5m])) * 60', "{{status}}")], w=8)
overview.ts("Card authorisation p99", [(p(0.99, "pbp_ehi_decision_seconds", "", where='type!~"PRESENTMENT|REVERSAL"'), "p99")],
            unit="s", w=8, thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 2}])
overview.ts("Business events /min", [('topk(10, sum by (type) (rate(pbp_events_published_total[5m]))) * 60', "{{type}}")], w=8)
overview.row("Platform")
overview.ts("5xx by service", [('sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))', "{{application}}")], unit="reqps", w=8)
overview.ts("p95 latency by service", [(p(0.95, "http_server_requests_seconds", "application"), "{{application}}")], unit="s", w=8)
overview.ts("Event delivery p95 (outbox → listener)", [(p(0.95, "pbp_events_delivery_seconds", "topic"), "{{topic}}")], unit="s", w=8)
overview.row("Follow one journey")
overview.text(
    "Paste a correlation ID (from the `X-Correlation-ID` header, a problem+json body or a log line) into "
    "**Correlation ID** above to see its log lines below. Each line has a **Trace** link to the full trace in "
    "Tempo. Or open *Explore → Tempo* and search `{ span.pbp.correlation_id = \"COR-PAY-…\" }`.", h=3)
overview.logs("Log lines for the correlation ID", '{service=~".+"} |= "cid=$cid"')

# ---------------------------------------------------------------- payments
pay = Board("pbp-payments", "PBP · Payments",
            "Faster Payments, CHAPS, internal transfers, inbound and Bacs credits. Counted from payment_status_history.",
            [query_var("type", "Payment type", "label_values(pbp_payment_transitions_total, type)")])
pay.stat("Completed (1h)", 'sum(increase(pbp_payment_transitions_total{type=~"$type", status="COMPLETED"}[1h])) or vector(0)')
pay.stat("Failed or rejected (1h)", 'sum(increase(pbp_payment_transitions_total{type=~"$type", status=~"FAILED|REJECTED"}[1h])) or vector(0)', red_above=1)
pay.stat("Held for review", 'sum(max without (instance, service) (pbp_payments_open{type=~"$type", status="HELD"})) or vector(0)')
pay.stat("CHAPS queued", 'sum(max without (instance, service) (pbp_payments_open{type="CHAPS", status="QUEUED"})) or vector(0)')
pay.stat("Completion p95", p(0.95, "pbp_payment_completion_seconds", "", "1h", 'type=~"$type"'), unit="s")
pay.stat("Oldest open", 'max(pbp_payments_oldest_seconds{type=~"$type"}) or vector(0)', unit="s")
pay.row("Flow")
pay.ts("Status changes /min", [('sum by (status) (rate(pbp_payment_transitions_total{type=~"$type"}[5m])) * 60', "{{status}}")], stack=True)
pay.ts("Initiated to COMPLETED", [(p(0.5, "pbp_payment_completion_seconds", "type", "15m", 'type=~"$type"'), "p50 {{type}}"),
                                   (p(0.95, "pbp_payment_completion_seconds", "type", "15m", 'type=~"$type"'), "p95 {{type}}")], unit="s")
pay.ts("Open payments by status", [('max without (instance, service) (pbp_payments_open{type=~"$type"})', "{{type}} {{status}}")], stack=True)
pay.ts("Oldest open payment in its status", [('max without (instance, service) (pbp_payments_oldest_seconds{type=~"$type"})', "{{type}} {{status}}")],
       unit="s", desc="Alerts: FPS > 5 min, CHAPS > 1 h, CHAPS QUEUED while the window is open, HELD > 48 h")
pay.row("Why payments fail")
pay.table("Failures and rejections by reason (24h)",
          'sort_desc(sum by (type, status, reason) (increase(pbp_payment_transitions_total{type=~"$type", status=~"FAILED|REJECTED|RETURNED"}[24h])) > 0)')
pay.ts("Confirmation of Payee results /h", [('sum by (result) (rate(pbp_cop_results_total[1h])) * 3600', "{{result}}")],
       stack=True, desc="Checked by the saga before every payment to another bank")
pay.row("Essence calls from the payment saga")
pay.ts("Orchestrator → essence-adapter", [('sum by (uri, outcome) (rate(http_client_requests_seconds_count{application="orchestrator", client_name="essence-adapter"}[5m]))', "{{outcome}} {{uri}}")], unit="reqps")
pay.ts("essence-adapter → Essence p95", [(p(0.95, "http_client_requests_seconds", "uri", where='application="essence-adapter"'), "{{uri}}")], unit="s")

# ---------------------------------------------------------------- cards
cards = Board("pbp-cards", "PBP · Cards and EHI",
              "Thredd's real-time authorisations answered by ehi-responder, and settlement.")
cards.stat("Decisions /s", 'sum(rate(pbp_ehi_decision_seconds_count[5m])) or vector(0)', unit="reqps")
cards.stat("Approval rate", 'sum(rate(pbp_ehi_decision_seconds_count{decision="APPROVE"}[15m])) / sum(rate(pbp_ehi_decision_seconds_count{type!~"PRESENTMENT|REVERSAL"}[15m]))', unit="percentunit")
cards.stat("p99 (budget 2 s)", p(0.99, "pbp_ehi_decision_seconds", "", where='type!~"PRESENTMENT|REVERSAL"'), unit="s", red_above=2)
cards.stat("CORE_UNAVAILABLE (1h)", 'sum(increase(pbp_ehi_decision_seconds_count{reason="CORE_UNAVAILABLE"}[1h])) or vector(0)', red_above=1)
cards.stat("Cache misses (1h)", 'sum(increase(pbp_ehi_decision_seconds_count{source="mapping"}[1h])) or vector(0)', desc="Card looked up from the mapping service, not memory or Redis")
cards.stat("Settled (1h)", 'sum(increase(pbp_events_published_total{type="card.settled"}[1h])) or vector(0)')
cards.row("Authorisations")
cards.ts("Latency against the budget", [(p(q, "pbp_ehi_decision_seconds", "", where='type!~"PRESENTMENT|REVERSAL"'), f"p{int(q*100)}") for q in (0.5, 0.95, 0.99)],
         unit="s", thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 2}])
cards.ts("Decisions by reason /min", [('sum by (decision, reason) (rate(pbp_ehi_decision_seconds_count[5m])) * 60', "{{decision}} {{reason}}")], stack=True)
cards.ts("Card lookup source", [('sum by (source) (rate(pbp_ehi_decision_seconds_count[5m]))', "{{source}}")], unit="reqps", stack=True,
         desc="memory → Redis → mapping service; a rising mapping share means the Redis cache is cold or down")
cards.ts("Essence hold and capture p95", [(p(0.95, "http_client_requests_seconds", "uri", where='application="ehi-responder", client_name="essence-adapter"'), "{{uri}}")], unit="s")
cards.row("Lifecycle")
cards.ts("Card events /min", [('sum by (type) (rate(pbp_events_published_total{type=~"card\\\\..*"}[5m])) * 60', "{{type}}")])
cards.ts("Thredd adapter calls", [('sum by (uri, outcome) (rate(http_client_requests_seconds_count{application="thredd-adapter"}[5m]))', "{{outcome}} {{uri}}")], unit="reqps")

# ---------------------------------------------------------------- schedules and direct debits
sched = Board("pbp-schedules", "PBP · Standing orders and Direct Debits",
              "Scheduled payments, standing orders, Direct Debit mandates, collections, returns and claims.")
sched.stat("Active standing orders", 'sum(max without (instance, service) (pbp_schedules_active{kind="STANDING_ORDER"})) or vector(0)')
sched.stat("Scheduled one-offs", 'sum(max without (instance, service) (pbp_schedules_active{kind="ONE_OFF"})) or vector(0)')
sched.stat("Overdue schedules", 'sum(max without (instance, service) (pbp_schedules_overdue)) or vector(0)', red_above=1)
sched.stat("Active mandates", 'sum(max without (instance, service) (pbp_dd_mandates{status="ACTIVE"})) or vector(0)')
sched.stat("Collections paid (24h)", 'sum(increase(pbp_payment_transitions_total{type="DIRECT_DEBIT", status="COMPLETED"}[24h])) or vector(0)')
sched.stat("Returned (24h)", 'sum(increase(pbp_payment_transitions_total{type="DIRECT_DEBIT", status="RETURNED"}[24h])) or vector(0)', red_above=1)
sched.row("Standing orders and scheduled payments")
sched.ts("Schedule events /h", [('sum by (type) (rate(pbp_events_published_total{type=~"schedule\\\\..*"}[1h])) * 3600', "{{type}}")])
sched.ts("Payments made by schedules", [('sum by (status) (rate(pbp_payment_transitions_total{status=~"COMPLETED|FAILED|REJECTED"}[1h])) * 3600', "{{status}}")],
         desc="All payment types; schedule payments run at 06:00 UK on the due date")
sched.row("Direct Debits")
sched.ts("Collections by status /h", [('sum by (status) (rate(pbp_payment_transitions_total{type="DIRECT_DEBIT"}[1h])) * 3600', "{{status}}")], stack=True)
sched.table("Returns by ARUDD reason (7d)", 'sort_desc(sum by (reason) (increase(pbp_payment_transitions_total{type="DIRECT_DEBIT", status="RETURNED"}[7d])) > 0)')
sched.ts("Mandates by status", [('max without (instance, service) (pbp_dd_mandates)', "{{status}}")], stack=True)
sched.ts("Direct Debit events /h", [('sum by (type) (rate(pbp_events_published_total{type=~"directdebit\\\\..*"}[1h])) * 3600', "{{type}}")],
         desc="AUDDIS mandates, collections presented, ADDACS cancellations, indemnity claims")

# ---------------------------------------------------------------- onboarding
onb = Board("pbp-onboarding", "PBP · Onboarding and KYC",
            "Customer onboarding saga, Atfinity KYC and compliance freezes.")
onb.stat("Onboardings started (24h)", 'sum(increase(pbp_events_published_total{type="onboarding.start"}[24h])) or vector(0)')
onb.stat("Completed (24h)", 'sum(increase(pbp_events_published_total{type="onboarding.completed"}[24h])) or vector(0)')
onb.stat("Rejected or failed (24h)", 'sum(increase(pbp_events_published_total{type=~"onboarding.rejected|onboarding.failed"}[24h])) or vector(0)', red_above=1)
onb.stat("KYC referred (24h)", 'sum(increase(pbp_events_published_total{type="kyc.referred"}[24h])) or vector(0)')
onb.stat("In progress", 'sum(max without (instance, service) (pbp_sagas_open{saga="ONBOARDING"})) or vector(0)')
onb.stat("Compliance freezes open", 'sum(max without (instance, service) (pbp_sagas_open{saga="FREEZE"})) or vector(0)')
onb.row("Funnel")
onb.ts("Onboarding and KYC events /h", [('sum by (type) (rate(pbp_events_published_total{type=~"onboarding\\\\..*|kyc\\\\..*|customer\\\\..*"}[1h])) * 3600', "{{type}}")])
onb.ts("Open sagas by step", [('max without (instance, service) (pbp_sagas_open)', "{{saga}} {{step}}")], stack=True)
onb.ts("Oldest saga at each step", [('max without (instance, service) (pbp_sagas_oldest_seconds)', "{{saga}} {{step}}")], unit="s", desc="Alert: onboarding at one step for over an hour")
onb.ts("Atfinity adapter → Atfinity", [('sum by (uri, outcome) (rate(http_client_requests_seconds_count{application="atfinity-adapter"}[5m]))', "{{outcome}} {{uri}}")], unit="reqps")

# ---------------------------------------------------------------- recon
rec = Board("pbp-recon", "PBP · Reconciliation", "Card and payment movements proved against Essence postings.")
rec.stat("Open exceptions", 'sum(pbp_recon_exceptions_open) or vector(0)', red_above=1)
rec.stat("Matched (last runs)", 'sum(pbp_recon_last_run_matched) or vector(0)')
rec.stat("Hours since last run", 'max(pbp_recon_last_run_age_seconds) / 3600', unit="h", red_above=26)
rec.row("Runs")
rec.table("Open exceptions by type and queue", 'sum by (type, queue) (pbp_recon_exceptions_open)')
rec.table("Last run per source", 'sum by (source) (pbp_recon_last_run_matched)')
rec.ts("Exceptions in the last run", [('sum by (source) (pbp_recon_last_run_exceptions)', "{{source}}")])

# ---------------------------------------------------------------- platform
plat = Board("pbp-platform", "PBP · Platform",
             "Services, vendor calls, Kafka and the outbox, Temporal and the JVM.",
             [query_var("app", "Service", 'label_values(up{job="pbp"}, service)')])
plat.ts("Targets up", [('up{job=~"pbp|temporal|kafka"}', "{{job}} {{service}}")], w=8)
plat.ts("Requests /s", [('sum by (application) (rate(http_server_requests_seconds_count{application=~"$app"}[5m]))', "{{application}}")], unit="reqps", w=8)
plat.ts("5xx ratio", [('sum by (application) (rate(http_server_requests_seconds_count{application=~"$app", status=~"5.."}[5m])) / sum by (application) (rate(http_server_requests_seconds_count{application=~"$app"}[5m]))', "{{application}}")], unit="percentunit", w=8)
plat.ts("p95 by endpoint", [(p(0.95, "http_server_requests_seconds", "application, uri", where='application=~"$app"'), "{{application}} {{uri}}")], unit="s")
plat.row("Vendors (adapters → simulator or sandbox)")
plat.ts("Vendor call outcomes", [('sum by (application, outcome) (rate(http_client_requests_seconds_count{application=~".+-adapter"}[5m]))', "{{application}} {{outcome}}")], unit="reqps")
plat.ts("Vendor call p95", [(p(0.95, "http_client_requests_seconds", "application", where='application=~".+-adapter"'), "{{application}}")], unit="s")
plat.row("Events")
plat.ts("Outbox → listener p95", [(p(0.95, "pbp_events_delivery_seconds", "topic"), "{{topic}}")], unit="s", w=8)
plat.ts("Consumer lag", [('sum by (consumergroup, topic) (kafka_consumergroup_lag)', "{{consumergroup}} {{topic}}")], w=8)
plat.ts("Dead-letter topics", [('sum by (topic) (kafka_topic_partition_current_offset{topic=~".+\\\\.DLT"})', "{{topic}}")], w=8,
        desc="Any rise means a message failed every retry. Replay is not built yet.")
plat.ts("Published vs consumed /min", [('sum by (topic) (rate(pbp_events_published_total[5m])) * 60', "out {{topic}}"),
                                       ('sum by (topic) (rate(pbp_events_consumed_total[5m])) * 60', "in {{topic}}")])
plat.ts("Listener p95", [(p(0.95, "spring_kafka_listener_seconds", "application"), "{{application}}")], unit="s")
plat.row("Temporal")
plat.ts("Activity failures /min", [('sum by (activity_type) (rate(temporal_activity_execution_failed_total[5m])) * 60', "{{activity_type}}")])
plat.ts("Workflow task schedule-to-start (avg)", [('sum by (task_queue) (rate(temporal_workflow_task_schedule_to_start_latency_seconds_sum[5m])) / sum by (task_queue) (rate(temporal_workflow_task_schedule_to_start_latency_seconds_count[5m]))', "{{task_queue}}")],
         unit="s", desc="Rising means the orchestrator's workers cannot keep up")
plat.row("JVM")
plat.ts("Heap used", [('sum by (application) (jvm_memory_used_bytes{area="heap", application=~"$app"})', "{{application}}")], unit="bytes")
plat.ts("GC pause /s", [('sum by (application) (rate(jvm_gc_pause_seconds_sum{application=~"$app"}[5m]))', "{{application}}")], unit="s")
plat.row("Errors in the logs")
plat.logs("WARN and ERROR lines", '{service=~".+", level=~"WARN|ERROR"}')

if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    for board in (overview, pay, cards, sched, onb, rec, plat):
        (OUT / f"{board.uid}.json").write_text(json.dumps(board.json(), indent=2) + "\n")
        print("wrote", board.uid, len(board.panels), "panels")
