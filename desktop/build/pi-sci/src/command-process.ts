import { spawn } from "node:child_process";
import { closeSync, mkdtempSync, openSync, rmSync, writeSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const OUTPUT_CHARS = 64 * 1024;
const LOG_BYTES = 32 * 1024 * 1024;
export interface CommandOptions {
	cwd: string;
	env?: Record<string, string>;
	timeout_ms?: number;
	signal?: AbortSignal;
}
export interface CommandResult {
	stdout: string;
	stderr: string;
	exit_code: number | null;
	signal: string | null;
	timed_out: boolean;
	truncated: boolean;
	full_output_path: string | null;
}

export async function runCommand(
	program: string,
	args: readonly string[],
	options: CommandOptions,
): Promise<CommandResult> {
	options.signal?.throwIfAborted();
	const directory = mkdtempSync(join(tmpdir(), "pi-sci-command-"));
	const outputPath = join(directory, "output.log");
	const fd = openSync(outputPath, "w", 0o600);
	let keepOutput = false;
	try {
		return await new Promise<CommandResult>((resolve, reject) => {
			const child = spawn(program, [...args], {
				cwd: options.cwd,
				env: { ...process.env, ...options.env },
				shell: false,
				detached: process.platform !== "win32",
				stdio: ["ignore", "pipe", "pipe"],
			});
			let stdout = "",
				stderr = "";
			let stdoutChars = 0,
				stderrChars = 0;
			let logBytes = 0;
			let timedOut = false,
				aborted = false;
			let failure: Error | undefined;
			let deadline: ReturnType<typeof setTimeout> | undefined;
			let escalation: ReturnType<typeof setTimeout> | undefined;
			const terminate = (signal: NodeJS.Signals) => {
				try {
					if (process.platform !== "win32" && child.pid) process.kill(-child.pid, signal);
					else child.kill(signal);
				} catch (error) {
					if ((error as NodeJS.ErrnoException).code !== "ESRCH") failure ??= error as Error;
				}
			};
			const stop = () => {
				terminate("SIGTERM");
				escalation ??= setTimeout(() => terminate("SIGKILL"), 100);
			};
			const abort = () => {
				aborted = true;
				stop();
			};
			options.signal?.addEventListener("abort", abort, { once: true });
			if (options.signal?.aborted) abort();
			if (options.timeout_ms !== undefined)
				deadline = setTimeout(() => {
					timedOut = true;
					stop();
				}, options.timeout_ms);
			const capture = (stream: "stdout" | "stderr", chunk: string) => {
				if (failure || aborted) return;
				try {
					const record = `[${stream}]\n${chunk}`;
					logBytes += Buffer.byteLength(record);
					if (logBytes > LOG_BYTES)
						throw new Error("Domain command output exceeded the 32 MiB log limit");
					const bytes = Buffer.from(record);
					let offset = 0;
					while (offset < bytes.length) {
						const written = writeSync(fd, bytes, offset, bytes.length - offset);
						if (!written) throw new Error("Domain output log write made no progress");
						offset += written;
					}
					const retain = (previous: string) => {
						const combined = previous + chunk;
						return combined.length <= OUTPUT_CHARS
							? combined
							: combined.slice(0, OUTPUT_CHARS / 2) + combined.slice(-OUTPUT_CHARS / 2);
					};
					if (stream === "stdout") {
						stdoutChars += chunk.length;
						stdout = retain(stdout);
					} else {
						stderrChars += chunk.length;
						stderr = retain(stderr);
					}
				} catch (error) {
					failure = error as Error;
					stop();
				}
			};
			child.stdout.setEncoding("utf8").on("data", (chunk: string) => capture("stdout", chunk));
			child.stderr.setEncoding("utf8").on("data", (chunk: string) => capture("stderr", chunk));
			child.on("error", (error) => {
				failure = error;
				stop();
			});
			child.on("exit", () => {
				if (process.platform !== "win32") stop();
			});
			child.on("close", (code, signal) => {
				if (deadline) clearTimeout(deadline);
				if (escalation) clearTimeout(escalation);
				options.signal?.removeEventListener("abort", abort);
				if (process.platform !== "win32") terminate("SIGKILL");
				const truncated = stdoutChars > OUTPUT_CHARS || stderrChars > OUTPUT_CHARS;
				if (failure) {
					reject(failure);
					return;
				}
				if (aborted) {
					reject(new Error("Domain command aborted"));
					return;
				}
				keepOutput = truncated;
				const marked = (text: string, chars: number) =>
					chars > OUTPUT_CHARS
						? text.slice(0, OUTPUT_CHARS / 2) +
							"\n[output truncated]\n" +
							text.slice(-OUTPUT_CHARS / 2)
						: text;
				resolve({
					stdout: marked(stdout, stdoutChars),
					stderr: marked(stderr, stderrChars),
					exit_code: code,
					signal,
					timed_out: timedOut,
					truncated,
					full_output_path: truncated ? outputPath : null,
				});
			});
		});
	} finally {
		closeSync(fd);
		if (!keepOutput) rmSync(directory, { recursive: true, force: true });
	}
}
