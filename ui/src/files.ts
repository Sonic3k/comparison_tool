// File access: File System Access API where the browser has it (Chrome, Edge), plain download otherwise.
// Handles and drafts are kept in IndexedDB so a reload can offer them back.

/* eslint-disable @typescript-eslint/no-explicit-any */
const w = window as any;

export const canPickFiles = typeof w.showOpenFilePicker === "function";
export const canSaveFiles = typeof w.showSaveFilePicker === "function";
export const canPickFolder = typeof w.showDirectoryPicker === "function";

export async function pickFile(accept: Record<string, string[]>, description: string): Promise<{ file: File; handle: any } | null> {
  if (!canPickFiles) return pickWithInput(Object.values(accept).flat().join(","));
  try {
    const [handle] = await w.showOpenFilePicker({ types: [{ description, accept }], multiple: false });
    return { file: await handle.getFile(), handle };
  } catch (e: any) {
    if (e?.name === "AbortError") return null;
    throw e;
  }
}

function pickWithInput(accept: string): Promise<{ file: File; handle: any } | null> {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = accept;
    input.onchange = () => resolve(input.files && input.files[0] ? { file: input.files[0], handle: null } : null);
    input.click();
  });
}

export async function writeHandle(handle: any, data: Blob | string): Promise<void> {
  if (handle.queryPermission && (await handle.queryPermission({ mode: "readwrite" })) !== "granted") {
    const p = await handle.requestPermission({ mode: "readwrite" });
    if (p !== "granted") throw new Error("Permission to write the file was not given");
  }
  const writable = await handle.createWritable();
  await writable.write(data);
  await writable.close();
}

export async function saveAs(data: Blob, suggestedName: string, description: string, accept: Record<string, string[]>): Promise<any> {
  if (!canSaveFiles) {
    download(data, suggestedName);
    return null;
  }
  try {
    const handle = await w.showSaveFilePicker({ suggestedName, types: [{ description, accept }] });
    await writeHandle(handle, data);
    return handle;
  } catch (e: any) {
    if (e?.name === "AbortError") return undefined;
    throw e;
  }
}

export function download(data: Blob, name: string) {
  const url = URL.createObjectURL(data);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export async function pickFolder(): Promise<any> {
  if (!canPickFolder) return null;
  try {
    return await w.showDirectoryPicker({ mode: "readwrite" });
  } catch (e: any) {
    if (e?.name === "AbortError") return null;
    throw e;
  }
}

export async function writeInFolder(dir: any, name: string, data: Blob | string): Promise<void> {
  if (dir.queryPermission && (await dir.queryPermission({ mode: "readwrite" })) !== "granted") {
    const p = await dir.requestPermission({ mode: "readwrite" });
    if (p !== "granted") throw new Error("Permission to write in the results folder was not given");
  }
  const fh = await dir.getFileHandle(name, { create: true });
  await writeHandle(fh, data);
}

// ---------------------------------------------------------------- IndexedDB (drafts, handles, preferences)

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open("comparison-tool", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("kv");
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function idbGet<T>(key: string): Promise<T | undefined> {
  try {
    const d = await db();
    return await new Promise((resolve, reject) => {
      const r = d.transaction("kv").objectStore("kv").get(key);
      r.onsuccess = () => resolve(r.result as T);
      r.onerror = () => reject(r.error);
    });
  } catch {
    return undefined;
  }
}

export async function idbSet(key: string, value: unknown): Promise<void> {
  try {
    const d = await db();
    await new Promise<void>((resolve, reject) => {
      const t = d.transaction("kv", "readwrite");
      t.objectStore("kv").put(value, key);
      t.oncomplete = () => resolve();
      t.onerror = () => reject(t.error);
    });
  } catch {
    /* storage unavailable: drafts are a convenience only */
  }
}

export async function idbDel(key: string): Promise<void> {
  try {
    const d = await db();
    await new Promise<void>((resolve) => {
      const t = d.transaction("kv", "readwrite");
      t.objectStore("kv").delete(key);
      t.oncomplete = () => resolve();
      t.onerror = () => resolve();
    });
  } catch {
    /* ignore */
  }
}

export function pref<T>(key: string, fallback: T): T {
  try {
    const v = localStorage.getItem("ct." + key);
    return v === null ? fallback : (JSON.parse(v) as T);
  } catch {
    return fallback;
  }
}

export function setPref(key: string, value: unknown) {
  try {
    localStorage.setItem("ct." + key, JSON.stringify(value));
  } catch {
    /* ignore */
  }
}
