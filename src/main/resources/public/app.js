"use strict";
(function () {
  var catalog = null;
  var state = null;

  var COLORS = { na: "#4a5566", zero: "#d4574a", half: "#e0a526", full: "#2fb37a" };
  var STATUS_LABELS = { not_started: "Not started", partial: "Partial", implemented: "Implemented", not_applicable: "Not applicable" };

  function h(tag, attrs) {
    var el = document.createElement(tag);
    attrs = attrs || {};
    Object.keys(attrs).forEach(function (k) {
      if (k === "class") el.className = attrs[k];
      else if (k === "text") el.textContent = attrs[k];
      else if (k.indexOf("on") === 0) el.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] !== null && attrs[k] !== undefined) el.setAttribute(k, attrs[k]);
    });
    for (var i = 2; i < arguments.length; i++) {
      var c = arguments[i];
      if (c === null || c === undefined) continue;
      el.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return el;
  }

  function api(method, path, body) {
    var opts = { method: method, headers: { "X-Atlas-Request": "1" } };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetch(path, opts).then(function (r) {
      return r.json().then(function (j) {
        if (!r.ok) throw new Error(j.error || "request failed");
        return j;
      });
    });
  }

  function toast(msg, err) {
    var t = document.getElementById("toast");
    t.textContent = msg;
    t.className = "toast" + (err ? " err" : "");
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { t.hidden = true; }, 3200);
  }

  function pct(r) { return Math.round(r * 100); }
  function level(r) { return r >= 0.75 ? "ok" : r >= 0.4 ? "mid" : "low"; }
  function scoreColor(s) { return s < 0 ? COLORS.na : s >= 1 ? COLORS.full : s > 0 ? COLORS.half : COLORS.zero; }

  function entry(id) { return state.assessment.controls[id] || { status: "not_started", owner: "", due: null, notes: "", evidence: [] }; }
  function scope() { return state.assessment.scope; }

  function statusText(id) {
    var e = entry(id);
    var s = state.summary.scores[id];
    if (e.status === "implemented" && s < 1) return "Evidence needed";
    return STATUS_LABELS[e.status];
  }

  function renderHeader() {
    var org = document.getElementById("org");
    var asof = document.getElementById("asof");
    org.value = state.assessment.organisation;
    asof.value = state.assessment.asOf;
    var box = document.getElementById("scope");
    box.textContent = "";
    catalog.frameworks.forEach(function (f) {
      var on = scope().indexOf(f.id) >= 0;
      box.appendChild(h("button", {
        class: "chip", type: "button", "aria-pressed": on ? "true" : "false", title: f.name,
        onclick: function () {
          var next = scope().filter(function (x) { return x !== f.id; });
          if (!on) next.push(f.id);
          if (next.length === 0) { toast("Keep at least one framework in scope", true); return; }
          saveMeta({ scope: next });
        }
      }, f.label));
    });
  }

  function renderCards() {
    var box = document.getElementById("cards");
    box.textContent = "";
    state.summary.frameworks.forEach(function (f) {
      var track = h("div", { class: "track" });
      var ghost = h("div", { class: "fill ghost" });
      ghost.style.width = pct(f.readinessIfTop5Closed) + "%";
      var fill = h("div", { class: "fill " + level(f.readiness) });
      fill.style.width = pct(f.readiness) + "%";
      track.appendChild(ghost);
      track.appendChild(fill);
      box.appendChild(h("div", { class: "card" },
        h("div", { class: "name", text: f.label }),
        h("div", { class: "pct", text: pct(f.readiness) + "%" }),
        track,
        h("div", { class: "foot" },
          h("span", { text: f.implemented + " done, " + f.partial + " partial, " + f.notStarted + " open" }),
          h("span", { class: "up", text: "top 5 gaps: " + pct(f.readinessIfTop5Closed) + "%" }))));
    });
  }

  function renderGaps() {
    var ol = document.getElementById("gaps");
    ol.textContent = "";
    var gaps = state.summary.gaps.slice(0, 8);
    if (gaps.length === 0) { ol.appendChild(h("li", { class: "hint", text: "No open gaps in the selected frameworks." })); return; }
    gaps.forEach(function (g, i) {
      var sub = h("div", { class: "s" }, statusText(g.id), g.overdue ? h("span", { class: "tag bad", text: "overdue" }) : null,
        g.owner ? h("span", { class: "tag", text: g.owner }) : h("span", { class: "tag", text: "unassigned" }));
      ol.appendChild(h("li", null, h("button", { class: "gap", type: "button", onclick: function () { openDrawer(g.id); } },
        h("div", { class: "rank", text: String(i + 1) }),
        h("div", null, h("div", { class: "t", text: g.id + "  " + g.title }), sub),
        h("div", { class: "dot", text: g.frameworksHit + " frameworks" }))));
    });
  }

  function renderLegend() {
    var l = document.getElementById("legend");
    l.textContent = "";
    [["Implemented with evidence", COLORS.full], ["Partial or evidence needed", COLORS.half], ["Not started", COLORS.zero], ["Not applicable", COLORS.na]].forEach(function (x) {
      var s = h("span", { text: x[0] });
      s.style.setProperty("--c", x[1]);
      l.appendChild(s);
    });
  }

  function renderMatrix() {
    var t = document.getElementById("matrix");
    t.textContent = "";
    var fws = catalog.frameworks.filter(function (f) { return scope().indexOf(f.id) >= 0; });
    var head = h("tr", null, h("th", { text: "Control" }), h("th", { text: "Status" }));
    fws.forEach(function (f) { head.appendChild(h("th", { class: "c", text: f.label })); });
    t.appendChild(h("thead", null, head));
    var body = h("tbody");
    var domain = null;
    catalog.controls.forEach(function (c) {
      var hit = fws.some(function (f) { return c.refs[f.id]; });
      if (!hit) return;
      if (c.domain !== domain) {
        domain = c.domain;
        body.appendChild(h("tr", { class: "domain" }, h("td", { colspan: String(2 + fws.length), text: domain })));
      }
      var s = state.summary.scores[c.id];
      var tr = h("tr", { class: "ctl", tabindex: "0", onclick: function () { openDrawer(c.id); },
        onkeydown: function (ev) { if (ev.key === "Enter") openDrawer(c.id); } },
        h("td", null, h("strong", { text: c.id }), " " + c.title),
        h("td", null, h("span", { class: "st", text: statusText(c.id) })));
      fws.forEach(function (f) {
        var cell = h("span", { class: "cell" + (c.refs[f.id] ? "" : " none"), title: c.refs[f.id] ? c.refs[f.id].join(", ") : "No direct mapping" });
        if (c.refs[f.id]) cell.style.setProperty("--c", scoreColor(s));
        tr.appendChild(h("td", { class: "c" }, cell));
      });
      body.appendChild(tr);
    });
    t.appendChild(body);
  }

  function render() {
    renderHeader();
    renderCards();
    renderGaps();
    renderLegend();
    renderMatrix();
  }

  // ---- drawer ----

  function openDrawer(id) {
    var c = catalog.controls.filter(function (x) { return x.id === id; })[0];
    var e = JSON.parse(JSON.stringify(entry(id)));
    var d = document.getElementById("drawer");
    d.textContent = "";
    d.appendChild(h("h3", { text: c.id + "  " + c.title }));
    d.appendChild(h("div", { class: "sub", text: c.domain + ", weight " + c.weight + ". " + c.summary }));
    var refs = h("div", { class: "refs" });
    catalog.frameworks.forEach(function (f) {
      if (c.refs[f.id]) refs.appendChild(h("div", null, h("b", { text: f.label }), c.refs[f.id].join(", ")));
    });
    d.appendChild(refs);

    var status = h("select", { id: "f-status" });
    Object.keys(STATUS_LABELS).forEach(function (k) {
      var o = h("option", { value: k, text: STATUS_LABELS[k] });
      if (k === e.status) o.selected = true;
      status.appendChild(o);
    });
    var owner = h("input", { id: "f-owner", maxlength: "80", value: e.owner });
    var due = h("input", { id: "f-due", type: "date", value: e.due || "" });
    var notes = h("textarea", { id: "f-notes", maxlength: "2000" });
    notes.value = e.notes;
    d.appendChild(h("label", { class: "field" }, h("span", { text: "Status" }), status));
    d.appendChild(h("label", { class: "field" }, h("span", { text: "Owner" }), owner));
    d.appendChild(h("label", { class: "field" }, h("span", { text: "Due date" }), due));
    d.appendChild(h("label", { class: "field" }, h("span", { text: "Notes" }), notes));

    d.appendChild(h("div", { class: "field" }, h("span", { text: "Evidence (" + c.evidenceHint + ")" })));
    var list = h("div", { id: "f-evidence" });
    d.appendChild(list);
    function addRow(ev) {
      var row = h("div", { class: "evi" },
        h("input", { class: "e-title", maxlength: "200", placeholder: "Document or record", value: ev.title || "" }),
        h("input", { class: "e-rev", type: "date", title: "Reviewed on", value: ev.reviewed || "" }),
        h("input", { class: "e-until", type: "date", title: "Valid until", value: ev.validUntil || "" }),
        h("button", { class: "btn sec x", type: "button", title: "Remove", "aria-label": "Remove evidence", onclick: function () { row.remove(); } }, "x"));
      list.appendChild(row);
    }
    list.appendChild(h("div", { class: "evi head" }, h("span", { text: "Item" }), h("span", { text: "Reviewed" }), h("span", { text: "Valid until" }), h("span")));
    e.evidence.forEach(addRow);
    d.appendChild(h("button", { class: "btn sec", type: "button", onclick: function () { addRow({}); } }, "Add evidence"));

    d.appendChild(h("div", { class: "actions" },
      h("button", { class: "btn", type: "button", onclick: function () {
        var evidence = [];
        list.querySelectorAll(".evi:not(.head)").forEach(function (row) {
          var title = row.querySelector(".e-title").value.trim();
          if (!title) return;
          evidence.push({ title: title, reviewed: row.querySelector(".e-rev").value || null, validUntil: row.querySelector(".e-until").value || null });
        });
        api("PUT", "/api/control/" + encodeURIComponent(id), {
          status: status.value, owner: owner.value, due: due.value || null, notes: notes.value, evidence: evidence
        }).then(function (s) { state = s; render(); d.hidden = true; toast(id + " saved"); })
          .catch(function (err) { toast(err.message, true); });
      } }, "Save"),
      h("button", { class: "btn sec", type: "button", onclick: function () { d.hidden = true; } }, "Close")));
    d.hidden = false;
    status.focus();
  }

  function saveMeta(patch) {
    api("PUT", "/api/meta", patch).then(function (s) { state = s; render(); }).catch(function (err) { toast(err.message, true); });
  }

  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape") document.getElementById("drawer").hidden = true;
  });
  document.getElementById("org").addEventListener("change", function (ev) { saveMeta({ organisation: ev.target.value }); });
  document.getElementById("asof").addEventListener("change", function (ev) { if (ev.target.value) saveMeta({ asOf: ev.target.value }); });

  Promise.all([api("GET", "/api/catalog"), api("GET", "/api/state")]).then(function (r) {
    catalog = r[0];
    state = r[1];
    render();
  }).catch(function (err) { toast(err.message, true); });
})();
