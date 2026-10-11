import { execFileSync } from "node:child_process";
import { rmSync, writeFileSync } from "node:fs";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { answer, lines } from "./tasks.mjs";

// Questions and edits modeled on prompts from real Pi sessions in this
// repository, run against a fixed snapshot of it. Guix itself computes the
// answer keys, so a grader never trusts its own parse of the Scheme.

const REPO = fileURLToPath(new URL("../../../../", import.meta.url));
const COMMIT = "b7fd83a5a210e6ba89fe80f2069f72818296d5d4";
const PATHS = [
	"modules",
	"Makefile",
	"channels.scm",
	"README.md",
	"AGENTS.md",
	"desktop/home.scm",
	"desktop/system.scm",
];
const GUIX = join(process.env.HOME, ".config/guix/current/bin/guix");

const GUIX_STUB = `#!/bin/sh
# Stands in for Guix so make targets cannot touch the host.
case "$1" in
describe) grep -o '(commit "[0-9a-f]*")' "$(dirname "$0")/../channels.scm" ;;
*) echo "guix-stub $*" ;;
esac
`;
const SUDO_STUB = '#!/bin/sh\necho "sudo-stub $*"\n';

async function snapshot(ws) {
	const archive = execFileSync("git", ["-C", REPO, "archive", COMMIT, ...PATHS], {
		maxBuffer: 1 << 26,
	});
	execFileSync("tar", ["-x", "-C", ws], { input: archive });
	const makefile = (await readFile(join(ws, "Makefile"), "utf8"))
		.replace(
			"GUIX := $(HOME)/.config/guix/current/bin/guix",
			"GUIX := $(CURDIR)/bin/guix\nSUDO := $(CURDIR)/bin/sudo",
		)
		.replaceAll(/^(\t\s*)sudo /gm, "$1$(SUDO) ");
	await writeFile(join(ws, "Makefile"), makefile);
	await mkdir(join(ws, "bin"), { recursive: true });
	await writeFile(join(ws, "bin/guix"), GUIX_STUB);
	await writeFile(join(ws, "bin/sudo"), SUDO_STUB);
	await chmod(join(ws, "bin/guix"), 0o755);
	await chmod(join(ws, "bin/sudo"), 0o755);
}

function guixRepl(ws, program) {
	const script = join(
		tmpdir(),
		`pi-sci-eval-${process.pid}-${Math.random().toString(36).slice(2)}.scm`,
	);
	writeFileSync(script, program);
	try {
		return execFileSync(GUIX, ["repl", "-L", "modules", "--", script], {
			cwd: ws,
			stdio: ["ignore", "pipe", "pipe"],
			timeout: 120_000,
		})
			.toString()
			.trim()
			.split("\n")
			.filter(Boolean)
			.sort();
	} finally {
		rmSync(script, { force: true });
	}
}

const VERSIONS = `(use-modules (guix packages) (ice-9 ftw))
(for-each
 (lambda (file)
   (module-for-each
    (lambda (sym var)
      (let ((value (variable-ref var)))
        (when (package? value)
          (format #t "~a ~a~%" (package-name value) (package-version value)))))
    (resolve-interface (list 'engstrand 'packages (string->symbol (basename file ".scm"))))))
 (scandir "modules/engstrand/packages" (lambda (f) (string-suffix? ".scm" f))))
`;

const USES = `(use-modules (ice-9 match) (ice-9 ftw))
(for-each
 (lambda (file)
   (let ((feature (string->symbol (basename file ".scm"))))
     (for-each
      (lambda (used)
        (match (module-name used)
          (('engstrand 'services service) (format #t "~a ~a~%" feature service))
          (_ #f)))
      (module-uses (resolve-module (list 'engstrand 'features feature))))))
 (scandir "modules/engstrand/features" (lambda (f) (string-suffix? ".scm" f))))
`;

const RESOLVES = `(resolve-interface '(engstrand features tailscale)) (display "ok")`;

const UNUSED = "  #:use-module (engstrand packages jjui)\n";
const TAILSCALE = "modules/engstrand/features/tailscale.scm";

// Expected answers come from an untouched snapshot, computed once per run.
let truth;
async function expected() {
	if (truth) return truth;
	const ws = execFileSync("mktemp", ["-d"]).toString().trim();
	await snapshot(ws);
	const channels = (await readFile(join(ws, "channels.scm"), "utf8")).matchAll(
		/\(name '([\w-]+)\)[\s\S]*?\(commit "([0-9a-f]+)"\)/g,
	);
	truth = {
		versions: guixRepl(ws, VERSIONS),
		uses: guixRepl(ws, USES),
		channels: [...channels].map(([, name, commit]) => `${name} ${commit.slice(0, 12)}`),
		tailscale: await readFile(join(ws, TAILSCALE), "utf8"),
	};
	execFileSync("rm", ["-rf", ws]);
	return truth;
}

function make(ws, ...args) {
	try {
		return execFileSync("make", ["-s", ...args], {
			cwd: ws,
			stdio: ["ignore", "pipe", "pipe"],
			timeout: 60_000,
		}).toString();
	} catch (error) {
		return `FAILED ${error.stdout ?? ""}${error.stderr ?? ""}`;
	}
}

const question = (id, prompt, key) => ({
	id: `repo-${id}`,
	suite: "repo",
	category: "repo-question",
	prompt,
	files: {},
	setup: snapshot,
	async validate(ws) {
		const want = (await expected())[key];
		const got = lines(await answer(ws));
		return { passed: JSON.stringify(got) === JSON.stringify(want), detail: { got, want } };
	},
	async solve(ws) {
		await writeFile(join(ws, "answer.txt"), `${(await expected())[key].join("\n")}\n`);
	},
});

export const REPO_TASKS = [
	question(
		"package-versions",
		"This is my Guix configuration. List every package defined under modules/engstrand/packages/ together with the version Guix would build. Write `<package name> <version>` lines, sorted, to answer.txt.",
		"versions",
	),
	question(
		"feature-services",
		"Review how the feature modules in modules/engstrand/features/ depend on the service modules. For each feature module, list every (engstrand services ...) module it imports. Write `<feature> <service>` lines, using the last name component of each module, sorted, to answer.txt.",
		"uses",
	),
	question(
		"channels",
		"Which channels does channels.scm pin? Write `<channel name> <first 12 characters of the pinned commit>` lines, in the order they appear in the file, to answer.txt.",
		"channels",
	),
	{
		id: "repo-make-update",
		suite: "repo",
		category: "repo-edit",
		prompt:
			"add a make update target: it should run the pull target and then the switch target, in that order. List it in the help output and in .PHONY like the other targets.",
		files: {},
		setup: snapshot,
		async validate(ws) {
			const dry = make(ws, "-n", "update");
			const pull = dry.indexOf("pull -C");
			const switched = dry.indexOf("system reconfigure");
			const help = /^update\s/m.test(make(ws, "help"));
			const phony = /\.PHONY:[^\n]*(?:\\\n[^\n]*)*\bupdate\b/.test(
				await readFile(join(ws, "Makefile"), "utf8"),
			);
			const ordered = pull !== -1 && switched !== -1 && pull < switched;
			return {
				passed: ordered && help && phony,
				detail: { ordered, help, phony, dry: dry.slice(0, 300) },
			};
		},
		async solve(ws) {
			const path = join(ws, "Makefile");
			const text = (await readFile(path, "utf8"))
				.replace(".PHONY: help pull", ".PHONY: help update pull")
				.replace("\t@echo 'pull ", "\t@echo 'update        pull, then switch'\n\t@echo 'pull ")
				.replace("\npull:\n", "\nupdate:\n\t$(MAKE) pull\n\t$(MAKE) switch\n\npull:\n");
			await writeFile(path, text);
		},
	},
	{
		id: "repo-unused-import",
		suite: "repo",
		category: "repo-edit",
		prompt:
			"One feature module under modules/engstrand/features/ imports a module it never uses. Find it and remove that import. Do not change anything else.",
		files: {},
		async setup(ws) {
			await snapshot(ws);
			const path = join(ws, TAILSCALE);
			const text = await readFile(path, "utf8");
			await writeFile(
				path,
				text.replace(
					"  #:use-module (engstrand packages tailscale)\n",
					`  #:use-module (engstrand packages tailscale)\n${UNUSED}`,
				),
			);
		},
		async validate(ws) {
			const text = await readFile(join(ws, TAILSCALE), "utf8");
			const restored = text === (await expected()).tailscale;
			let resolves = false;
			try {
				resolves = guixRepl(ws, RESOLVES).join("") === "ok";
			} catch {}
			return { passed: restored && resolves, detail: { restored, resolves } };
		},
		async solve(ws) {
			const path = join(ws, TAILSCALE);
			await writeFile(path, (await readFile(path, "utf8")).replace(UNUSED, ""));
		},
	},
];
