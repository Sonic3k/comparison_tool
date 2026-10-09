import { useEffect, useRef } from "react";
import { EditorState } from "@codemirror/state";
import type { Extension } from "@codemirror/state";
import {
  Decoration, EditorView, MatchDecorator, ViewPlugin, drawSelection, highlightActiveLineGutter, keymap, lineNumbers,
  placeholder as cmPlaceholder,
} from "@codemirror/view";
import type { DecorationSet, ViewUpdate } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { json } from "@codemirror/lang-json";
import { HighlightStyle, bracketMatching, foldGutter, foldKeymap, indentOnInput, syntaxHighlighting } from "@codemirror/language";
import { highlightSelectionMatches, search, searchKeymap } from "@codemirror/search";
import { tags as t } from "@lezer/highlight";

const style = HighlightStyle.define([
  { tag: t.propertyName, color: "var(--text)" },
  { tag: t.string, color: "var(--pass)" },
  { tag: t.number, color: "var(--running)" },
  { tag: [t.bool, t.null, t.atom], color: "var(--m-patch)" },
  { tag: [t.punctuation, t.separator, t.brace, t.squareBracket], color: "var(--faint)" },
  { tag: t.invalid, color: "var(--fail)" },
]);

const varMatcher = new MatchDecorator({ regexp: /\{\{[^{}\n]+\}\}|\$\{[A-Za-z_][\w.]*(?::[^}]*)?\}/g, decoration: Decoration.mark({ class: "cm-var" }) });
const varHighlight = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;
    constructor(view: EditorView) {
      this.decorations = varMatcher.createDeco(view);
    }
    update(u: ViewUpdate) {
      this.decorations = varMatcher.updateDeco(u, this.decorations);
    }
  },
  { decorations: (v) => v.decorations },
);

interface Props {
  value: string;
  onChange?: (v: string) => void;
  lang?: "json" | "text";
  readOnly?: boolean;
  minHeight?: number;
  maxHeight?: number;
  placeholder?: string;
  onModEnter?: () => void;
  invalid?: boolean;
  lineNumbers?: boolean;
}

export function CodeEditor({ value, onChange, lang = "json", readOnly, minHeight = 120, maxHeight = 520, placeholder, onModEnter, invalid, lineNumbers: showLines = true }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const external = useRef(false);
  const change = useRef(onChange);
  change.current = onChange;
  const enter = useRef(onModEnter);
  enter.current = onModEnter;

  useEffect(() => {
    const exts: Extension[] = [
      drawSelection(),
      history(),
      indentOnInput(),
      bracketMatching(),
      highlightSelectionMatches(),
      search({ top: true }),
      keymap.of([
        { key: "Mod-Enter", run: () => { enter.current?.(); return !!enter.current; } },
        ...defaultKeymap, ...historyKeymap, ...searchKeymap, ...foldKeymap, indentWithTab,
      ]),
      syntaxHighlighting(style),
      varHighlight,
      EditorView.lineWrapping,
      EditorState.tabSize.of(2),
      EditorState.readOnly.of(!!readOnly),
      EditorView.editable.of(!readOnly),
      EditorView.theme({
        "&": { minHeight: `${minHeight}px`, maxHeight: `${maxHeight}px`, backgroundColor: "var(--surface)", color: "var(--text)" },
        ".cm-scroller": { overflow: "auto" },
        ".cm-content": { padding: "6px 0" },
      }),
      EditorView.updateListener.of((u) => {
        if (u.docChanged && !external.current) change.current?.(u.state.doc.toString());
      }),
    ];
    if (showLines) exts.push(lineNumbers(), highlightActiveLineGutter());
    if (lang === "json") {
      exts.push(json());
      if (showLines) exts.push(foldGutter());
    }
    if (placeholder) exts.push(cmPlaceholder(placeholder));
    const v = new EditorView({ parent: host.current!, state: EditorState.create({ doc: value, extensions: exts }) });
    view.current = v;
    return () => {
      v.destroy();
      view.current = null;
    };
    // the document is synced by the effect below; the editor is rebuilt only when its kind changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lang, readOnly, minHeight, maxHeight, placeholder, showLines]);

  useEffect(() => {
    const v = view.current;
    if (!v) return;
    const cur = v.state.doc.toString();
    if (cur === value) return;
    external.current = true;
    try {
      v.dispatch({ changes: { from: 0, to: cur.length, insert: value } });
    } finally {
      external.current = false;
    }
  }, [value]);

  return <div className={`code${readOnly ? " ro" : ""}${invalid ? " invalid" : ""}`} ref={host} />;
}
