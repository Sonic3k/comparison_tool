import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ButtonHTMLAttributes, KeyboardEvent as ReactKeyboardEvent, ReactNode } from "react";
import { create } from "zustand";
import { Ban, Circle, CircleCheck, CircleDashed, CircleMinus, CircleX, LoaderCircle, TriangleAlert, X } from "lucide-react";

// ------------------------------------------------------------------ buttons

type BtnProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "default" | "primary" | "ghost" | "danger" | "stop" | "run";
  size?: "sm";
  icon?: ReactNode;
};

export function Button({ variant = "default", size, icon, children, className, type = "button", ...rest }: BtnProps) {
  const cls = ["btn", variant === "default" ? "" : variant, size ?? "", children === undefined || children === null ? "icon" : "", className ?? ""]
    .filter(Boolean).join(" ");
  return (
    <button type={type} className={cls} {...rest}>
      {icon}
      {children}
    </button>
  );
}

// ------------------------------------------------------------------ status

export function StatusIcon({ status, size = 15 }: { status?: string; size?: number }) {
  switch (status) {
    case "pass": return <CircleCheck size={size} className="st-pass" aria-label="pass" />;
    case "fail": return <CircleX size={size} className="st-fail" aria-label="fail" />;
    case "error": return <TriangleAlert size={size} className="st-error" aria-label="error" />;
    case "blocked": return <Ban size={size} className="st-blocked" aria-label="blocked" />;
    case "skipped": return <CircleDashed size={size} className="st-skipped" aria-label="skipped" />;
    case "running": return <LoaderCircle size={size} className="st-running spin" aria-label="running" />;
    case "disabled": return <CircleMinus size={size} className="st-disabled" aria-label="disabled" />;
    default: return <Circle size={size} className="st-queued" aria-label="queued" />;
  }
}

export function StatusPill({ status, children }: { status: string; children?: ReactNode }) {
  return (
    <span className={`pill st-${status}`}>
      <StatusIcon status={status} size={12} />
      {children ?? status}
    </span>
  );
}

export function HttpCode({ code }: { code?: number }) {
  if (code === undefined || code === null) return null;
  const cls = code < 300 ? "ok" : code < 400 ? "redirect" : code < 500 ? "client" : "server";
  return <span className={`http ${cls}`}>{code}</span>;
}

export function Method({ m }: { m?: string }) {
  const u = (m ?? "GET").toUpperCase();
  const known = ["GET", "POST", "PUT", "PATCH", "DELETE"].includes(u);
  return <span className={`method ${known ? u : "other"}`}>{u === "DELETE" ? "DEL" : u === "OPTIONS" ? "OPT" : u}</span>;
}

// ------------------------------------------------------------------ tabs

export interface TabItem {
  id: string;
  label: ReactNode;
  count?: number | string;
  dot?: "on" | "bad";
}

export function Tabs({ items, value, onChange, right, tight }: { items: TabItem[]; value: string; onChange: (id: string) => void; right?: ReactNode; tight?: boolean }) {
  return (
    <div className={`tabs${tight ? " tight" : ""}`} role="tablist">
      {items.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === value} className={`tab${t.id === value ? " active" : ""}`} onClick={() => onChange(t.id)}>
          {t.label}
          {t.count !== undefined && t.count !== 0 && t.count !== "" ? <span className="count">{t.count}</span> : null}
          {t.dot ? <span className={`dot${t.dot === "bad" ? " bad" : ""}`} /> : null}
        </button>
      ))}
      {right ? <div className="tabs-right">{right}</div> : null}
    </div>
  );
}

// ------------------------------------------------------------------ form pieces

export function Field({ label, hint, children, className }: { label: ReactNode; hint?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <div className={`field${className ? " " + className : ""}`}>
      <label>{label}</label>
      {children}
      {hint ? <div className="hint">{hint}</div> : null}
    </div>
  );
}

export function Check({ checked, onChange, children, title }: { checked: boolean; onChange: (v: boolean) => void; children: ReactNode; title?: string }) {
  return (
    <label className="check" title={title}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>{children}</span>
    </label>
  );
}

export function NumberInput({ value, onChange, placeholder, min = 0, className, title }: {
  value?: number; onChange: (v: number | undefined) => void; placeholder?: string; min?: number; className?: string; title?: string;
}) {
  return (
    <input
      className={`input${className ? " " + className : ""}`}
      type="number"
      min={min}
      value={value ?? ""}
      placeholder={placeholder}
      title={title}
      onChange={(e) => {
        const t = e.target.value.trim();
        if (!t) return onChange(undefined);
        const n = Number(t);
        onChange(Number.isFinite(n) ? n : undefined);
      }}
    />
  );
}

/** Editable list of short strings shown as chips (tags, ignored fields, dependencies). */
export function TagInput({ values, onChange, placeholder, mono, suggestions }: {
  values?: string[]; onChange: (v: string[]) => void; placeholder?: string; mono?: boolean; suggestions?: string[];
}) {
  const [text, setText] = useState("");
  const list = values ?? [];
  const listId = useRef("tl-" + Math.random().toString(36).slice(2)).current;
  const add = (raw: string) => {
    const parts = raw.split(/[,\n]/).map((s) => s.trim()).filter(Boolean).filter((s) => !list.includes(s));
    if (parts.length) onChange([...list, ...parts]);
    setText("");
  };
  const onKey = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      add(text);
    } else if (e.key === "Backspace" && !text && list.length) {
      onChange(list.slice(0, -1));
    }
  };
  return (
    <div className="input row wrap" style={{ height: "auto", minHeight: 28, padding: "3px 4px", gap: 4 }}>
      {list.map((v) => (
        <span key={v} className={`tag${mono ? " mono" : ""}`}>
          {v}
          <button title="Remove" onClick={() => onChange(list.filter((x) => x !== v))}><X size={12} /></button>
        </span>
      ))}
      <input
        className={mono ? "mono" : undefined}
        style={{ border: "none", outline: "none", background: "transparent", flex: 1, minWidth: 80, height: 20 }}
        value={text}
        placeholder={list.length ? "" : placeholder}
        list={suggestions?.length ? listId : undefined}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKey}
        onBlur={() => text.trim() && add(text)}
      />
      {suggestions?.length ? (
        <datalist id={listId}>
          {suggestions.filter((s) => !list.includes(s)).slice(0, 200).map((s) => <option key={s} value={s} />)}
        </datalist>
      ) : null}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty"><div className="col">{children}</div></div>;
}

// ------------------------------------------------------------------ overlays (menus, dialogs)

export type MenuItem =
  | { label: string; icon?: ReactNode; hint?: string; onClick: () => void; disabled?: boolean; danger?: boolean }
  | { sep: true }
  | { header: string };

interface CtxMenu {
  x: number;
  y: number;
  items: MenuItem[];
}

interface OverlayState {
  dialogs: { id: number; node: ReactNode }[];
  menu: CtxMenu | null;
  showDialog(node: ReactNode): number;
  closeDialog(id?: number): void;
  showMenu(m: CtxMenu): void;
  closeMenu(): void;
}

let dialogSeq = 1;
export const useOverlay = create<OverlayState>((set) => ({
  dialogs: [],
  menu: null,
  showDialog(node) {
    const id = dialogSeq++;
    set((s) => ({ dialogs: [...s.dialogs, { id, node }] }));
    return id;
  },
  closeDialog(id) {
    set((s) => ({ dialogs: id === undefined ? s.dialogs.slice(0, -1) : s.dialogs.filter((d) => d.id !== id) }));
  },
  showMenu(m) {
    set({ menu: m });
  },
  closeMenu() {
    set({ menu: null });
  },
}));

export function openContextMenu(e: { clientX: number; clientY: number; preventDefault(): void }, items: MenuItem[]) {
  e.preventDefault();
  useOverlay.getState().showMenu({ x: e.clientX, y: e.clientY, items });
}

function MenuItems({ items, onDone }: { items: MenuItem[]; onDone: () => void }) {
  return (
    <>
      {items.map((it, i) => {
        if ("sep" in it) return <div key={i} className="menu-sep" />;
        if ("header" in it) return <div key={i} className="menu-head">{it.header}</div>;
        return (
          <button
            key={i}
            className={`menu-item${it.danger ? " danger" : ""}`}
            disabled={it.disabled}
            onClick={() => {
              onDone();
              it.onClick();
            }}
          >
            {it.icon ?? <span style={{ width: 14 }} />}
            <span>{it.label}</span>
            {it.hint ? <span className="hint">{it.hint}</span> : null}
          </button>
        );
      })}
    </>
  );
}

export function ContextMenuHost() {
  const menu = useOverlay((s) => s.menu);
  const close = useOverlay((s) => s.closeMenu);
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  useLayoutEffect(() => {
    if (!menu || !ref.current) return setPos(null);
    const r = ref.current.getBoundingClientRect();
    setPos({ x: Math.max(4, Math.min(menu.x, window.innerWidth - r.width - 8)), y: Math.max(4, Math.min(menu.y, window.innerHeight - r.height - 8)) });
  }, [menu]);
  useEffect(() => {
    if (!menu) return;
    const down = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    const key = (e: KeyboardEvent) => e.key === "Escape" && close();
    const off = () => close();
    document.addEventListener("mousedown", down, true);
    document.addEventListener("keydown", key, true);
    window.addEventListener("blur", off);
    window.addEventListener("resize", off);
    return () => {
      document.removeEventListener("mousedown", down, true);
      document.removeEventListener("keydown", key, true);
      window.removeEventListener("blur", off);
      window.removeEventListener("resize", off);
    };
  }, [menu, close]);
  if (!menu) return null;
  return (
    <div ref={ref} className="menu-fixed" style={{ left: pos?.x ?? menu.x, top: pos?.y ?? menu.y, visibility: pos ? "visible" : "hidden" }} onContextMenu={(e) => e.preventDefault()}>
      <MenuItems items={menu.items} onDone={close} />
    </div>
  );
}

export function DropMenu({ trigger, items, align = "left" }: {
  trigger: (p: { open: boolean; toggle: () => void }) => ReactNode;
  items: MenuItem[] | (() => MenuItem[]);
  align?: "left" | "right";
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const down = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const key = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", down, true);
    document.addEventListener("keydown", key, true);
    return () => {
      document.removeEventListener("mousedown", down, true);
      document.removeEventListener("keydown", key, true);
    };
  }, [open]);
  return (
    <div className="menu-wrap" ref={ref}>
      {trigger({ open, toggle: () => setOpen((o) => !o) })}
      {open ? (
        <div className={`menu ${align}`}>
          <MenuItems items={typeof items === "function" ? items() : items} onDone={() => setOpen(false)} />
        </div>
      ) : null}
    </div>
  );
}

export function Modal({ title, onClose, children, footer, wide, icon }: {
  title: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean; icon?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
      }
    };
    document.addEventListener("keydown", key);
    const first = ref.current?.querySelector<HTMLElement>("[data-autofocus], input, textarea, select, button.primary");
    first?.focus();
    return () => document.removeEventListener("keydown", key);
  }, [onClose]);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal${wide ? " wide" : ""}`} ref={ref} role="dialog" aria-modal="true">
        <div className="modal-head">
          {icon}
          <h2>{title}</h2>
          <Button variant="ghost" size="sm" className="right" icon={<X size={15} />} onClick={onClose} title="Close (Esc)" />
        </div>
        <div className="modal-body">{children}</div>
        {footer ? <div className="modal-foot">{footer}</div> : null}
      </div>
    </div>
  );
}

export function DialogHost() {
  const dialogs = useOverlay((s) => s.dialogs);
  return <>{dialogs.map((d) => <div key={d.id}>{d.node}</div>)}</>;
}

/** Asks a question with a few answers; resolves with the chosen id, or null when dismissed. */
export function choose(title: string, text: ReactNode, choices: { id: string; label: string; variant?: "primary" | "danger" | "default" }[]): Promise<string | null> {
  return new Promise((resolve) => {
    let id = 0;
    const done = (v: string | null) => {
      useOverlay.getState().closeDialog(id);
      resolve(v);
    };
    id = useOverlay.getState().showDialog(
      <Modal
        title={title}
        onClose={() => done(null)}
        footer={choices.map((c) => (
          <Button key={c.id} variant={c.variant ?? "default"} onClick={() => done(c.id)}>{c.label}</Button>
        ))}
      >
        <div>{text}</div>
      </Modal>,
    );
  });
}

export async function confirmDialog(title: string, text: ReactNode, ok = "OK", danger = false): Promise<boolean> {
  return (await choose(title, text, [{ id: "cancel", label: "Cancel" }, { id: "ok", label: ok, variant: danger ? "danger" : "primary" }])) === "ok";
}

// ------------------------------------------------------------------ resizing

/** A drag handle that changes a size; axis x for widths, y for heights. invert makes moving left/up grow the size. */
export function Splitter({ value, onChange, min, max, axis = "x", invert, className }: {
  value: number; onChange: (v: number) => void; min: number; max: number; axis?: "x" | "y"; invert?: boolean; className?: string;
}) {
  const [drag, setDrag] = useState(false);
  const start = useRef({ pos: 0, value: 0 });
  return (
    <div
      className={`${className ?? (axis === "x" ? "splitter" : "vsplit")}${drag ? " drag" : ""}`}
      onPointerDown={(e) => {
        (e.target as HTMLElement).setPointerCapture(e.pointerId);
        start.current = { pos: axis === "x" ? e.clientX : e.clientY, value };
        setDrag(true);
        e.preventDefault();
      }}
      onPointerMove={(e) => {
        if (!drag) return;
        const d = (axis === "x" ? e.clientX : e.clientY) - start.current.pos;
        onChange(Math.max(min, Math.min(max, start.current.value + (invert ? -d : d))));
      }}
      onPointerUp={(e) => {
        (e.target as HTMLElement).releasePointerCapture(e.pointerId);
        setDrag(false);
      }}
      onDoubleClick={() => onChange(Math.max(min, Math.min(max, (min + max) / 2)))}
      role="separator"
      aria-orientation={axis === "x" ? "vertical" : "horizontal"}
    />
  );
}

// ------------------------------------------------------------------ misc

export function isTyping(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable || !!el.closest?.(".cm-editor");
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand("copy");
    ta.remove();
    return ok;
  }
}
