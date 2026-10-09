import { useEffect, useRef, useState } from "react";

/**
 * Local text for an editor bound to a model value. Typing keeps the user's text as typed; the text is replaced only
 * when the model changes from elsewhere (undo, another step), never because the model re-formats what was typed.
 */
export function useSyncedText(modelText: string): [string, (local: string, modelAfter: string) => void] {
  const [text, setText] = useState(modelText);
  const committed = useRef(modelText);
  useEffect(() => {
    if (modelText !== committed.current) {
      committed.current = modelText;
      setText(modelText);
    }
  }, [modelText]);
  return [
    text,
    (local, modelAfter) => {
      committed.current = modelAfter;
      setText(local);
    },
  ];
}

/** A number kept in localStorage, for panel sizes. */
export function usePrefNumber(key: string, fallback: number): [number, (v: number) => void] {
  const [v, setV] = useState(() => {
    try {
      const raw = localStorage.getItem("ct." + key);
      const n = raw === null ? NaN : Number(raw);
      return Number.isFinite(n) ? n : fallback;
    } catch {
      return fallback;
    }
  });
  return [
    v,
    (n) => {
      setV(n);
      try {
        localStorage.setItem("ct." + key, String(Math.round(n)));
      } catch {
        /* ignore */
      }
    },
  ];
}
