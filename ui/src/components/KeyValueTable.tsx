import { useLayoutEffect, useRef, useState } from "react";
import { Trash2 } from "lucide-react";
import type { KeyValue } from "../types";
import { Button } from "./ui";

interface Props {
  rows?: KeyValue[];
  onChange: (rows: KeyValue[]) => void;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  monoKeys?: boolean;
  /** Keys whose values are masked until focused (passwords, secrets). */
  secret?: (key: string) => boolean;
  suggestions?: string[];
}

/** Editable key/value rows with an always-present empty row for adding, and a text mode for pasting many. */
export function KeyValueTable({ rows, onChange, keyPlaceholder = "Name", valuePlaceholder = "Value", monoKeys = true, secret, suggestions }: Props) {
  const list = rows ?? [];
  const [bulk, setBulk] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const pending = useRef<{ row: number; field: string } | null>(null);
  useLayoutEffect(() => {
    const p = pending.current;
    if (!p || !box.current) return;
    pending.current = null;
    const el = box.current.querySelector<HTMLInputElement>(`[data-kv-row="${p.row}"][data-kv-field="${p.field}"]`);
    if (el) {
      el.focus();
      const n = el.value.length;
      el.setSelectionRange?.(n, n);
    }
  });
  const listId = suggestions?.length ? "kv-sugg-" + (keyPlaceholder || "k").replace(/\W/g, "") : undefined;

  const set = (i: number, patch: Partial<KeyValue>) => {
    const next = list.map((r, j) => (j === i ? clean({ ...r, ...patch }) : r));
    onChange(next);
  };
  const add = (patch: Partial<KeyValue>, field: string) => {
    pending.current = { row: list.length, field };
    onChange([...list, clean({ key: "", value: "", ...patch })]);
  };
  const remove = (i: number) => onChange(list.filter((_, j) => j !== i));

  if (bulk !== null) {
    return (
      <div>
        <div className="kv-tools">
          <span className="muted" style={{ fontSize: 12 }}>One per line as <code>name: value</code>. Start a line with <code>#</code> to keep it disabled.</span>
          <Button size="sm" className="right" onClick={() => { onChange(parseBulk(bulk)); setBulk(null); }}>Done</Button>
        </div>
        <textarea className="textarea mono" rows={Math.max(6, bulk.split("\n").length + 1)} value={bulk} onChange={(e) => setBulk(e.target.value)} autoFocus />
      </div>
    );
  }

  return (
    <div ref={box}>
      <table className="kv">
        <tbody>
          {list.map((r, i) => {
            const masked = secret?.(r.key) ?? false;
            return (
              <tr key={i} className={r.disabled ? "off" : undefined}>
                <td className="c">
                  <input type="checkbox" checked={!r.disabled} title={r.disabled ? "Enable" : "Disable"} onChange={(e) => set(i, { disabled: e.target.checked ? undefined : true })} />
                </td>
                <td className="k">
                  <input className={`input sm${monoKeys ? " mono" : ""}`} value={r.key} placeholder={keyPlaceholder} list={listId} data-kv-row={i} data-kv-field="key" onChange={(e) => set(i, { key: e.target.value })} />
                </td>
                <td>
                  <input className="input sm mono" type={masked ? "password" : "text"} value={r.value} placeholder={valuePlaceholder} data-kv-row={i} data-kv-field="value"
                    onFocus={(e) => masked && (e.target.type = "text")} onBlur={(e) => masked && (e.target.type = "password")}
                    onChange={(e) => set(i, { value: e.target.value })} />
                </td>
                <td className="x">
                  <Button variant="ghost" size="sm" icon={<Trash2 size={13} />} title="Remove" onClick={() => remove(i)} />
                </td>
              </tr>
            );
          })}
          <tr>
            <td className="c" />
            <td className="k">
              <input className={`input sm${monoKeys ? " mono" : ""}`} value="" placeholder={list.length ? "Add…" : keyPlaceholder} list={listId}
                onChange={(e) => add({ key: e.target.value }, "key")} />
            </td>
            <td>
              <input className="input sm mono" value="" placeholder={valuePlaceholder} onChange={(e) => add({ value: e.target.value }, "value")} />
            </td>
            <td className="x" />
          </tr>
        </tbody>
      </table>
      <div className="kv-tools" style={{ marginTop: 4, marginBottom: 0 }}>
        <button className="btn ghost sm" onClick={() => setBulk(toBulk(list))}>Edit as text</button>
      </div>
      {listId ? <datalist id={listId}>{suggestions!.map((s) => <option key={s} value={s} />)}</datalist> : null}
    </div>
  );
}

function clean(r: KeyValue): KeyValue {
  const out: KeyValue = { key: r.key, value: r.value };
  if (r.disabled) out.disabled = true;
  return out;
}

function toBulk(rows: KeyValue[]): string {
  return rows.map((r) => `${r.disabled ? "# " : ""}${r.key}: ${r.value}`).join("\n");
}

function parseBulk(text: string): KeyValue[] {
  const out: KeyValue[] = [];
  for (const raw of text.split(/\r?\n/)) {
    let line = raw.trim();
    if (!line) continue;
    let disabled = false;
    if (line.startsWith("#")) {
      disabled = true;
      line = line.slice(1).trim();
    }
    const i = line.indexOf(":");
    const eq = line.indexOf("=");
    const at = i < 0 ? eq : eq < 0 ? i : Math.min(i, eq);
    const key = (at < 0 ? line : line.slice(0, at)).trim();
    const value = at < 0 ? "" : line.slice(at + 1).trim();
    if (!key) continue;
    out.push(disabled ? { key, value, disabled: true } : { key, value });
  }
  return out;
}

export function isSecretKey(k: string): boolean {
  return /pass(word)?|secret|token|api[-_]?key|private/i.test(k);
}
