import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { CODEMODE_RULES, repairDelimiters, rulesForPath } from "../src/delimiters.ts";
import { harness, output } from "./harness.ts";

const clojure = (content: string) => repairDelimiters(content, CODEMODE_RULES);
const forPath = (path: string, content: string) => {
	const rules = rulesForPath(path);
	assert.ok(rules, path);
	return repairDelimiters(content, rules);
};

test("a form truncated at the end gets its closers appended", () => {
	const outcome = clojure('(defn greet [name]\n  (println (str "Hello " name))');
	assert.equal(outcome.kind, "repaired");
	assert.equal(
		outcome.kind === "repaired" && outcome.content,
		'(defn greet [name]\n  (println (str "Hello " name)))',
	);
	assert.match(outcome.kind === "repaired" ? outcome.note : "", /^\[auto-repair\] .*appended `\)`/);
});

test("closing at the end never swallows a later top-level form", () => {
	const source =
		'(defn first-fn []\n  (println "first")\n(defn second-fn []\n  (println "second"))';
	assert.deepEqual(clojure(source), {
		kind: "unbalanced",
		summary:
			"Delimiter imbalance: 1 unclosed. The innermost is the `(` opened at line 1, column 1; close it with `)`.",
	});
});

test("a long open form is reported instead of closed", () => {
	const body = Array.from({ length: 11 }, (_, i) => `  (step ${i})`).join("\n");
	assert.equal(clojure(`(do\n${body}`).kind, "unbalanced");
});

test("only trailing extra closers are removed", () => {
	assert.deepEqual(clojure("(+ 1 2)))\n"), {
		kind: "repaired",
		content: "(+ 1 2)\n",
		note: "[auto-repair] Unbalanced delimiters: removed 2 extra trailing closing delimiter(s). If that placement is wrong, resend the corrected text.",
	});
	assert.equal(clojure("(a))\n(b)").kind, "unbalanced");
	assert.equal(clojure("(let [x (+ 1 2]\n  x)").kind, "unbalanced");
});

test("delimiters inside strings, comments, and character literals do not count", () => {
	assert.deepEqual(clojure('(str "a)" \\( #"\\)" \\;) ; ) comment'), { kind: "clean" });
	assert.deepEqual(forPath("x.scm", "(list #\\( #;(ignored) #;skip #| ) |# 1)"), { kind: "clean" });
	assert.deepEqual(forPath("x.el", "(list ?\\( ?) ?\\C-] ?\\M-\\C-] foo?)"), { kind: "clean" });
	assert.equal(rulesForPath("notes.txt"), undefined);
});

test("codemode runs a truncated program once and reports the repair", async () => {
	const runtime = await harness();
	try {
		const result = await runtime.call("(text (+ 20 22)");
		assert.equal(result.isError, false, output(result));
		assert.match(output(result), /\[auto-repair\] Unbalanced delimiters: appended `\)`/);
		assert.match(output(result), /\n42/);
		const swallowed = await runtime.call("(def x (+ 1 2)\n(text x)");
		assert.equal(swallowed.isError, true);
		assert.match(output(swallowed), /EOF while reading/);
		assert.doesNotMatch(output(swallowed), /auto-repair/);
	} finally {
		runtime.close();
	}
});

test("the write tool repairs Lisp files and leaves other files alone", async () => {
	const runtime = await harness();
	try {
		const result = await runtime.call(`
			(text (await (tools/write {:path "a.clj" :content "(ns a)\\n(defn f [] (inc 1)"})))
			(text (await (tools/write {:path "b.scm" :content "(define a 1)\\n(define b\\n(define c 3)"})))
			(text (await (tools/write {:path "c.txt" :content "(unbalanced"})))
		`);
		assert.equal(result.isError, false, output(result));
		const read = (name: string) => readFileSync(join(runtime.cwd, name), "utf8");
		assert.equal(read("a.clj"), "(ns a)\n(defn f [] (inc 1))");
		assert.equal(read("b.scm"), "(define a 1)\n(define b\n(define c 3)");
		assert.equal(read("c.txt"), "(unbalanced");
		assert.match(
			output(result),
			/a\.clj[\s\S]*\[auto-repair\][\s\S]*b\.scm\n\[delimiter warning\] .*line 2, column 1/,
		);
		assert.match(output(result), /c\.txt$/m);
	} finally {
		runtime.close();
	}
});
