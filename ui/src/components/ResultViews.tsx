import { useMemo, useState } from "react";
import { Braces, Copy, Rows3 } from "lucide-react";
import type { AssertionResult, Exchange, StepResult } from "../types";
import { fmtMs, fmtSize } from "../runs";
import { CodeEditor } from "./CodeEditor";
import { JsonView, pretty, tryParse } from "./JsonView";
import type { JsonActions } from "./JsonView";
import { Button, HttpCode, Method, StatusIcon, StatusPill, Tabs, copyText } from "./ui";
import { useStore } from "../store";

export function AssertionList({ items }: { items?: AssertionResult[] }) {
  if (!items?.length) return null;
  const failed = items.filter((a) => !a.pass);
  const ordered = [...failed, ...items.filter((a) => a.pass)];
  return (
    <div>
      {ordered.map((a, i) => (
        <div className="assert" key={i}>
          <StatusIcon status={a.pass ? "pass" : "fail"} size={14} />
          <div className="grow">
            <div className="expr">{a.expr}</div>
            {a.message ? <div className="msg">{a.message}</div> : null}
          </div>
        </div>
      ))}
    </div>
  );
}

function lanesOf(r: StepResult): { name: string; ex: Exchange }[] {
  const out: { name: string; ex: Exchange }[] = [];
  if (r.baseline) out.push({ name: "Baseline", ex: r.baseline });
  if (r.target) out.push({ name: r.baseline ? "Target" : "Response", ex: r.target });
  return out;
}

function diffPaths(diffs?: string[]): Set<string> {
  const out = new Set<string>();
  for (const d of diffs ?? []) {
    const m = / at \[(.*?)\]( \(|:)/.exec(d) ?? /^Field missing in \w+: (.+)$/.exec(d);
    if (m) out.add(m[1] ? `$.${m[1]}` : "$");
  }
  return out;
}

export function StepResultView({ r, actions, tab, onTab }: { r: StepResult; actions?: JsonActions; tab?: string; onTab?: (t: string) => void }) {
  const [own, setOwn] = useState("summary");
  const current = tab ?? own;
  const setTab = onTab ?? setOwn;
  const lanes = lanesOf(r);
  const failedAsserts = (r.assertions ?? []).filter((a) => !a.pass).length;
  const items = [
    { id: "summary", label: "Summary", dot: r.status !== "pass" && r.status !== "skipped" ? ("bad" as const) : undefined },
    { id: "body", label: "Response" },
    { id: "headers", label: "Headers" },
    { id: "request", label: "Request" },
  ];
  return (
    <div className="col" style={{ gap: 0, minHeight: 0 }}>
      <Tabs items={items} value={current} onChange={setTab} tight right={
        <span className="row" style={{ gap: 10 }}>
          {failedAsserts ? <span className="badge bad">{failedAsserts} failed check{failedAsserts > 1 ? "s" : ""}</span> : null}
          {r.diffs?.length ? <span className="badge bad">{r.diffs.length} diff{r.diffs.length > 1 ? "s" : ""}</span> : null}
          {r.attempts && r.attempts > 1 ? <span className="badge warn">{r.attempts} attempts</span> : null}
        </span>
      } />
      <div style={{ padding: "10px 12px" }}>
        {current === "summary" ? <Summary r={r} /> : null}
        {current === "body" ? (
          lanes.length ? (
            <div className={lanes.length > 1 ? "lanes" : undefined}>
              {lanes.map((l) => (
                <div key={l.name} style={{ minWidth: 0 }}>
                  {lanes.length > 1 ? <LaneTitle name={l.name} ex={l.ex} /> : null}
                  <Body ex={l.ex} actions={l.name === "Baseline" ? undefined : actions} highlight={diffPaths(r.diffs)} />
                </div>
              ))}
            </div>
          ) : <div className="muted">No response was recorded for this step.</div>
        ) : null}
        {current === "headers" ? (
          <div className={lanes.length > 1 ? "lanes" : undefined}>
            {lanes.map((l) => (
              <div key={l.name} style={{ minWidth: 0 }}>
                {lanes.length > 1 ? <LaneTitle name={l.name} ex={l.ex} /> : null}
                {l.ex.responseHeaders?.length ? (
                  <div className="kvlist">
                    {l.ex.responseHeaders.map((h, i) => (
                      <FragmentKV key={i} k={h.key} v={h.value} />
                    ))}
                  </div>
                ) : <div className="muted">No headers.</div>}
              </div>
            ))}
          </div>
        ) : null}
        {current === "request" ? (
          <div className={lanes.length > 1 ? "lanes" : undefined}>
            {lanes.map((l) => (
              <div key={l.name} style={{ minWidth: 0 }}>
                {lanes.length > 1 ? <div className="lane-title">{l.name} <span className="faint">{l.ex.env}</span></div> : null}
                <Request ex={l.ex} />
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function FragmentKV({ k, v }: { k: string; v: string }) {
  return (
    <>
      <div className="k">{k}</div>
      <div className="v">{v}</div>
    </>
  );
}

function LaneTitle({ name, ex }: { name: string; ex: Exchange }) {
  return (
    <div className="lane-title">
      <span>{name}</span>
      <span className="faint">{ex.env}</span>
      <HttpCode code={ex.status} />
      <span className="faint">{fmtMs(ex.timeMs)}</span>
    </div>
  );
}

function Summary({ r }: { r: StepResult }) {
  const lanes = lanesOf(r);
  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="row wrap" style={{ gap: 10 }}>
        <StatusPill status={r.status} />
        {r.durationMs !== undefined ? <span className="muted">{fmtMs(r.durationMs)}</span> : null}
      </div>
      {r.reason ? <div className={`note ${r.status === "error" ? "warn" : r.status === "pass" ? "" : "bad"}`}>{r.reason}</div> : null}
      {lanes.map((l) => (
        <div key={l.name} className="row wrap mono" style={{ gap: 8, fontSize: 12 }}>
          {lanes.length > 1 ? <span className="badge lane-badge">{l.name}</span> : null}
          <Method m={l.ex.method} />
          <span className="ellipsis grow" title={l.ex.url}>{l.ex.url}</span>
          <HttpCode code={l.ex.status} />
          <span className="faint">{fmtMs(l.ex.timeMs)}</span>
          {l.ex.responseSize !== undefined ? <span className="faint">{fmtSize(l.ex.responseSize)}</span> : null}
          {l.ex.error ? <span className="st-error">{l.ex.error}</span> : null}
        </div>
      ))}
      {r.assertions?.length ? (
        <div>
          <div className="section-title" style={{ marginBottom: 4 }}>Checks</div>
          <AssertionList items={r.assertions} />
        </div>
      ) : null}
      {r.diffs?.length ? (
        <div>
          <div className="section-title" style={{ marginBottom: 4 }}>Differences baseline → target</div>
          {r.diffs.map((d, i) => <div key={i} className="diff-line">{d}</div>)}
        </div>
      ) : null}
      {r.extracted && Object.keys(r.extracted).length ? (
        <div>
          <div className="section-title" style={{ marginBottom: 4 }}>Saved variables</div>
          <div className="kvlist">
            {Object.entries(r.extracted).map(([k, v]) => <FragmentKV key={k} k={k} v={v} />)}
          </div>
        </div>
      ) : null}
      {r.missed?.length ? (
        <div className="note warn">Not found in the response: {r.missed.join(", ")}</div>
      ) : null}
    </div>
  );
}

function Body({ ex, actions, highlight }: { ex: Exchange; actions?: JsonActions; highlight?: Set<string> }) {
  const parsed = useMemo(() => tryParse(ex.responseBody), [ex.responseBody]);
  const big = (ex.responseBody?.length ?? 0) > 300_000;
  const [mode, setMode] = useState<"tree" | "raw">(parsed !== undefined && !big ? "tree" : "raw");
  const text = useMemo(() => (mode === "raw" ? pretty(ex.responseBody) : ""), [mode, ex.responseBody]);
  if (ex.error && !ex.responseBody) return <div className="note warn">{ex.error}</div>;
  if (ex.responseBody === undefined || ex.responseBody === null) {
    return <div className="muted">The body was not kept for this run (capture setting). Run the test case again to see it.</div>;
  }
  if (!ex.responseBody) return <div className="muted">Empty body.</div>;
  return (
    <div className="col" style={{ gap: 6 }}>
      <div className="row">
        {parsed !== undefined ? (
          <div className="segmented sm">
            <button className={mode === "tree" ? "on" : ""} onClick={() => setMode("tree")}><Rows3 size={12} />Tree</button>
            <button className={mode === "raw" ? "on" : ""} onClick={() => setMode("raw")}><Braces size={12} />Raw</button>
          </div>
        ) : null}
        {ex.contentType ? <span className="faint mono">{ex.contentType}</span> : null}
        {ex.truncated ? <span className="badge warn" title="Bodies are cut at the run's body limit">truncated</span> : null}
        <Button size="sm" variant="ghost" className="right" icon={<Copy size={13} />} onClick={async () => {
          await copyText(ex.responseBody ?? "");
          useStore.getState().toast("ok", "Response body copied");
        }}>Copy</Button>
      </div>
      {mode === "tree" && parsed !== undefined ? (
        <JsonView key={ex.responseBody?.length + ":" + (ex.url ?? "")} value={parsed} actions={actions} highlight={highlight} />
      ) : (
        <CodeEditor value={text} readOnly lang={parsed !== undefined ? "json" : "text"} minHeight={80} maxHeight={560} />
      )}
    </div>
  );
}

function Request({ ex }: { ex: Exchange }) {
  return (
    <div className="col" style={{ gap: 10 }}>
      <div className="row mono" style={{ fontSize: 12 }}>
        <Method m={ex.method} />
        <span style={{ wordBreak: "break-all" }}>{ex.url}</span>
      </div>
      {ex.requestHeaders?.length ? (
        <div className="kvlist">
          {ex.requestHeaders.map((h, i) => <FragmentKV key={i} k={h.key} v={h.value} />)}
        </div>
      ) : <div className="faint">No headers.</div>}
      {ex.requestBody ? <pre className="pre">{pretty(ex.requestBody)}</pre> : null}
    </div>
  );
}
