(ns bbh.explainer
  "Self-contained HTML pages for explanations written for the person: answers,
  plans, audits, reviews. One page is one file with inline CSS and SVG and no
  network resources, readable at phone width. A page tagged with a thread
  carries the bbh-doc meta, so bbh.docs lists it in the phone's Docs. The look
  follows the T136 and T138 explainers in ~/guix/docs."
  (:require [clojure.string :as str]))

(def css
  "The shared stylesheet. Pages may add their own rules after it."
  ":root{--fg:#1c2230;--mut:#5d6678;--bg:#f7f6f2;--card:#fff;--line:#dedad0;--lap:#2f6f8f;--lapbg:#e6f0f5;--bld:#7a4fa0;--bldbg:#f0e9f6;--net:#2e7d4f;--netbg:#e4f3ea;--ext:#8a6a1f;--extbg:#f7efdc;--bad:#b3382c;--badbg:#f8e3e0;--hi:#f2b705}
*{box-sizing:border-box}html{scroll-behavior:smooth;-webkit-text-size-adjust:100%}
body{margin:0;font:15px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif;color:var(--fg);background:var(--bg)}
main{max-width:920px;margin:0 auto;padding:22px 16px 64px}
h1{font-size:25px;line-height:1.25;margin:0 0 4px}h2{font-size:20px;margin:36px 0 8px;padding-bottom:4px;border-bottom:2px solid var(--line)}h3{font-size:16.5px;margin:22px 0 6px}
.sub{color:var(--mut);margin:0 0 12px;font-size:14px}
nav{padding:8px 0;border-bottom:1px solid var(--line);font-size:14px}
nav a{color:var(--lap);text-decoration:none;margin-right:14px;font-weight:600}
nav summary{cursor:pointer;color:var(--lap);font-weight:600}nav ol{margin:6px 0}nav li a{margin:0;font-weight:500}
ul,ol{padding-left:22px}li{margin:3px 0}
a{color:var(--lap);overflow-wrap:break-word}
code{font:12.5px ui-monospace,SFMono-Regular,Menlo,monospace;background:#ece9e1;padding:1px 4px;border-radius:3px;overflow-wrap:break-word}
pre{background:#ece9e1;border-radius:6px;padding:10px 12px;overflow-x:auto}pre code{background:none;padding:0}
.cite{font:11.5px ui-monospace,Menlo,monospace;color:var(--mut);overflow-wrap:anywhere}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 16px;margin:10px 0}
.card.good{border-left:6px solid var(--net)}.card.warn{border-left:6px solid var(--ext)}.card.bad{border-left:6px solid var(--bad)}
.tag{display:inline-block;font-size:11px;font-weight:700;padding:1px 7px;border-radius:9px;border:1px solid var(--line);background:#faf9f5;vertical-align:1px}
.tag.measured{background:var(--netbg);color:var(--net)}.tag.inferred{background:var(--lapbg);color:var(--lap)}.tag.guess{background:var(--extbg);color:var(--ext)}
.table{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:14px;background:var(--card)}
th,td{border:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}th{background:#efece4}
svg{width:100%;height:auto;display:block}svg text{font-family:system-ui,-apple-system,'Segoe UI',sans-serif}
.split{display:grid;grid-template-columns:1fr 1fr;gap:16px}@media(max-width:700px){.split{grid-template-columns:1fr}}
@media(max-width:480px){body{font-size:14.5px}main{padding:14px 12px 48px}h1{font-size:22px}}")

(defn escape
  "S with the characters HTML gives meaning to escaped, for text and attribute values."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn page
  "A whole HTML page. TITLE and DESCRIPTION are text; BODY is the HTML inside
  <main>, after an <h1> of the title. THREAD, such as \"T12\", tags the page for the
  phone's Docs. STYLE is extra CSS after the shared sheet."
  [{:keys [title description thread body style]}]
  (str "<!doctype html>\n<html lang=en><head><meta charset=utf-8>"
       "<meta name=viewport content='width=device-width,initial-scale=1'>\n"
       (when description (str "<meta name=\"description\" content=\"" (escape description) "\">\n"))
       (when thread (str "<meta name=\"bbh-doc\" content=\"" (escape thread) "\">\n"))
       "<title>" (escape title) "</title>\n<style>\n" css (when style (str "\n" style)) "\n</style></head>\n"
       "<body><main>\n<h1>" (escape title) "</h1>\n" body "\n</main></body></html>\n"))
