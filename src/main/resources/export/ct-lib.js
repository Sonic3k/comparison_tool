/* Comparison Tool assertion DSL for Postman and Bruno exports (same rules as the tool). */
var ct = (function () {
  function strip(s) {
    if (s == null) return "";
    var t = String(s).trim();
    if (t.length >= 2 && ((t[0] === '"' && t[t.length - 1] === '"') || (t[0] === "'" && t[t.length - 1] === "'"))) return t.slice(1, -1);
    return t;
  }
  function txt(n) { return typeof n === "string" ? n : JSON.stringify(n); }
  function resolve(root, path) {
    if (!path) return root;
    var segs = path.split("."), cur = root;
    for (var i = 0; i < segs.length; i++) {
      var seg = segs[i];
      if (cur === undefined || cur === null) return undefined;
      var m = seg.match(/^(.*)\[(-?\d+)\]$/);
      if (m) {
        if (m[1]) cur = cur[m[1]];
        if (!Array.isArray(cur)) return undefined;
        var idx = parseInt(m[2], 10);
        cur = cur[idx < 0 ? cur.length + idx : idx];
      } else if (seg === "length" && (Array.isArray(cur) || typeof cur === "string")) {
        return cur.length;
      } else {
        cur = cur[seg];
      }
    }
    return cur;
  }
  function parseOp(parts, from) {
    if (from >= parts.length) return { op: "exists", rhs: null };
    var a = (parts[from] || "").toLowerCase(), b = (parts[from + 1] || "").toLowerCase();
    if (a === "not" && ["exists", "null", "contains", "matches"].indexOf(b) >= 0) return { op: "not " + b, rhs: parts.slice(from + 2).join(" ") };
    if (a === "is" && b === "null") return { op: "is null", rhs: null };
    if (a === "type" && b === "==") return { op: "type ==", rhs: parts.slice(from + 2).join(" ") };
    return { op: a, rhs: parts.slice(from + 1).join(" ") };
  }
  function typeOf(v) {
    if (v === null) return "null";
    if (Array.isArray(v)) return "array";
    return typeof v === "object" ? "object" : typeof v;
  }
  function check(node, op, rhs) {
    var missing = node === undefined;
    var isNull = node === undefined || node === null;
    var exp = strip(rhs);
    switch (op) {
      case "exists": return isNull ? "field does not exist" : null;
      case "not exists": return isNull ? null : "field exists but should not";
      case "is null": return isNull ? null : "not null, got: " + txt(node);
      case "not null": return isNull ? "field is null or missing" : null;
      case "==": {
        if (missing) return "field missing";
        var act = txt(node), a = Number(act), e = Number(exp);
        if (act !== "" && exp !== "" && !isNaN(a) && !isNaN(e)) return Math.abs(a - e) < 0.0001 ? null : "expected " + exp + ", got " + act;
        if (/^(true|false)$/i.test(exp)) return act.toLowerCase() === exp.toLowerCase() ? null : "expected " + exp + ", got " + act;
        return act === exp ? null : 'expected "' + exp + '", got "' + act + '"';
      }
      case "!=": return missing ? null : (txt(node) !== exp ? null : 'expected value to differ from "' + exp + '"');
      case "contains": return missing ? "field missing" : (String(node).indexOf(exp) >= 0 ? null : 'expected to contain "' + exp + '", got "' + String(node) + '"');
      case "not contains": return missing ? null : (String(node).indexOf(exp) < 0 ? null : 'expected not to contain "' + exp + '"');
      case "matches": case "not matches": {
        if (missing) return op === "matches" ? "field missing" : null;
        var re = exp.length >= 2 && exp[0] === "/" && exp[exp.length - 1] === "/" ? exp.slice(1, -1) : exp;
        var found = new RegExp(re).test(txt(node));
        return (op === "matches") === found ? null : (op === "matches" ? "expected to match /" : "expected not to match /") + re + "/";
      }
      case ">": case ">=": case "<": case "<=": {
        if (missing) return "field missing";
        var x = Number(node), y = Number(exp);
        var ok = op === ">" ? x > y : op === ">=" ? x >= y : op === "<" ? x < y : x <= y;
        return ok ? null : x + " " + op + " " + y + " is false";
      }
      case "type ==": return missing ? "field missing" : (typeOf(node) === exp.toLowerCase() ? null : "expected type " + exp + ", got " + typeOf(node));
      default: return "unknown operator: " + op;
    }
  }
  function quantified(arr, field, q, p) {
    var pass = 0, first = null;
    for (var i = 0; i < arr.length; i++) {
      var r = check(field == null ? arr[i] : resolve(arr[i], field), p.op, p.rhs);
      if (r === null) pass++; else if (first === null) first = r;
    }
    var ok = q === "all" ? pass === arr.length : pass > 0;
    return ok ? null : (q === "all" ? pass + "/" + arr.length + " elements passed - " : "0/" + arr.length + " elements matched - ") + first;
  }
  /** Returns null when the line holds, otherwise the reason. */
  function evaluate(line, body, headers) {
    var t = line.trim();
    if (/^header\s/i.test(t)) {
      var hp = t.slice(7).trim().split(/\s+/), hv;
      var name = hp[0].toLowerCase();
      for (var k in headers) if (k.toLowerCase() === name) hv = headers[k];
      var hop = parseOp(hp, 1);
      return check(hv, hop.op, hop.rhs);
    }
    var expr = t.indexOf("$.") === 0 ? t.slice(2) : (t[0] === "$" ? t.slice(1) : t);
    var fm = expr.match(/^(.+?)\[([\w_]+)=["']?([^"'\]]+)["']?\](.*)$/);
    if (fm) {
      var fa = resolve(body, fm[1]);
      if (!Array.isArray(fa)) return "'" + fm[1] + "' is not an array or does not exist";
      var hits = fa.filter(function (el) { return el && el[fm[2]] !== undefined && String(el[fm[2]]) === fm[3]; });
      if (!hits.length) return "no element found where " + fm[2] + ' == "' + fm[3] + '"';
      var rem = fm[4];
      if (!rem || !rem.trim()) return null;
      var parts = (rem[0] === "." ? rem.slice(1) : rem).split(/\s+/), fp = parseOp(parts, 1);
      for (var h = 0; h < hits.length; h++) {
        var r = check(parts[0] ? resolve(hits[h], parts[0]) : hits[h], fp.op, fp.rhs);
        if (r !== null) return "filter [" + fm[2] + '="' + fm[3] + '"]: ' + r;
      }
      return null;
    }
    var wi = expr.indexOf("[*].");
    if (wi >= 0) {
      var wa = resolve(body, expr.slice(0, wi));
      if (!Array.isArray(wa)) return "'" + expr.slice(0, wi) + "' is not an array or does not exist";
      if (!wa.length) return "'" + expr.slice(0, wi) + "' is empty";
      var wp = expr.slice(wi + 4).split(/\s+/), q, op;
      if (wp.length >= 2 && /^(all|any)$/i.test(wp[1])) { q = wp[1].toLowerCase(); op = parseOp(wp, 2); }
      else { op = parseOp(wp, 1); q = op.op === "contains" ? "any" : "all"; }
      return quantified(wa, wp[0], q, op);
    }
    var tm = expr.match(/^(.+)\[\*\]\s*(.*)$/);
    if (tm) {
      var tp = tm[2].trim() ? tm[2].trim().split(/\s+/) : [];
      if (!tp.length || !/^(any|all)$/i.test(tp[0])) return "terminal [*] requires explicit quantifier: use 'any' or 'all'";
      var ta = resolve(body, tm[1]);
      if (!Array.isArray(ta)) return "'" + tm[1] + "' is not an array or does not exist";
      if (!ta.length) return "'" + tm[1] + "' is empty";
      return quantified(ta, null, tp[0].toLowerCase(), parseOp(tp, 1));
    }
    var sp = expr.split(/\s+/), so = parseOp(sp, 1);
    return check(body === undefined ? undefined : resolve(body, sp[0]), so.op, so.rhs);
  }
  function statusOk(expected, actual) {
    var exp = String(expected || "").replace(/\s/g, "");
    if (!exp) return actual < 400;
    return exp.split(",").some(function (p) {
      if (/^\dxx$/i.test(p)) return Math.floor(actual / 100) === Number(p[0]);
      return Number(p) === actual;
    });
  }
  function read(path, status, headers, raw, body) {
    var p = path.trim();
    if (p.toLowerCase() === "$status") return String(status);
    if (/^\$header\./i.test(p)) {
      var n = p.slice(8).toLowerCase();
      for (var k in headers) if (k.toLowerCase() === n) return headers[k];
      return undefined;
    }
    if (p === "$") return raw;
    var v = resolve(body, p.replace(/^\$\.?/, ""));
    if (v === undefined || v === null) return undefined;
    return typeof v === "string" ? v : JSON.stringify(v);
  }
  function parse(raw) { try { return JSON.parse(raw); } catch (e) { return undefined; } }
  return {
    /** Postman: status + assertions as pm.test entries, then extracts into collection variables. */
    postman: function (pm, spec) {
      var raw = pm.response.text(), body = parse(raw), headers = {};
      pm.response.headers.each(function (h) { headers[h.key] = h.value; });
      var status = pm.response.code;
      if (spec.status || !(spec.assertions || []).length) {
        pm.test("status " + (spec.status || "< 400"), function () { pm.expect(statusOk(spec.status, status), "got " + status).to.be.true; });
      }
      (spec.assertions || []).forEach(function (a) {
        var line = pm.variables.replaceIn(a);
        pm.test(line, function () { var r = evaluate(line, body, headers); pm.expect(r, r || "").to.equal(null); });
      });
      (spec.extract || []).forEach(function (e) {
        var v = read(e[1], status, headers, raw, body);
        if (v !== undefined) pm.collectionVariables.set(e[0], v);
      });
    },
    /** Bruno: extracts after the response. */
    bruExtract: function (res, bru, extract) {
      var raw = typeof res.body === "string" ? res.body : JSON.stringify(res.body);
      var body = typeof res.body === "string" ? parse(res.body) : res.body;
      (extract || []).forEach(function (e) {
        var v = read(e[1], res.status, res.headers || {}, raw, body);
        if (v !== undefined) bru.setVar(e[0], v);
      });
    },
    /** Bruno: status + assertions as tests. */
    bruTests: function (res, test, expect, spec, interpolate) {
      var body = typeof res.body === "string" ? parse(res.body) : res.body;
      var headers = res.headers || {};
      if (spec.status || !(spec.assertions || []).length) {
        test("status " + (spec.status || "< 400"), function () { expect(statusOk(spec.status, res.status), "got " + res.status).to.equal(true); });
      }
      (spec.assertions || []).forEach(function (a) {
        var line = interpolate ? interpolate(a) : a;
        test(line, function () { var r = evaluate(line, body, headers); expect(r, r || "").to.equal(null); });
      });
    },
    evaluate: evaluate,
    statusOk: statusOk
  };
})();
if (typeof module !== "undefined") module.exports = ct;
