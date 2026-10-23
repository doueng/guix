import { codeToTokens, type ThemedToken } from '@pierre/highlights';
import { catppuccinMocha as theme } from '@pierre/highlights/themes';

type Kind = 'context' | 'add' | 'del';
type Line = { kind: Kind; text: string };
type Hunk = { header: string; lines: Line[] };
type FilePatch = { from: string; to: string; status: string; notes: string[]; hunks: Hunk[] };
type Segment = { type: 'text'; line: string } | { type: 'file'; file: FilePatch };

const palette = { base: '#1e1e2e', text: '#cdd6f4', dim: '#6c7086', green: '#a6e3a1', red: '#f38ba8', blue: '#89b4fa' };

const rgb = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const mix = (hex: string, amount: number) => {
  const [a, b] = [rgb(hex), rgb(palette.base)];
  return a.map((c, i) => Math.round(c * amount + b[i] * (1 - amount)));
};
const fg = (hex: string) => `\x1b[38;2;${rgb(hex).join(';')}m`;
const bg = ([r, g, b]: number[]) => `\x1b[48;2;${r};${g};${b}m`;
const reset = '\x1b[0m';

const style: Record<Kind, { sign: string; color: string; bg: string }> = {
  context: { sign: ' ', color: palette.dim, bg: '' },
  add: { sign: '+', color: palette.green, bg: bg(mix(palette.green, 0.16)) },
  del: { sign: '-', color: palette.red, bg: bg(mix(palette.red, 0.16)) },
};

const langByName: Record<string, string> = { makefile: 'make', gnumakefile: 'make', dockerfile: 'dockerfile', containerfile: 'dockerfile' };
const langByExt: Record<string, string> = { fnl: 'clojure', bb: 'clojure', fish: 'bash', kbd: 'lisp', sexp: 'scheme', service: 'toml' };

function langFor(path: string): string {
  const name = path.split('/').pop()!.toLowerCase();
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  return langByName[name] ?? langByExt[ext] ?? ext;
}

function parse(input: string): Segment[] {
  const segments: Segment[] = [];
  let file: FilePatch | undefined;
  let hunk: Hunk | undefined;
  for (const line of input.replace(/\n$/, '').split('\n')) {
    const git = line.match(/^diff --git a\/(.*) b\/(.*)$/);
    if (git) {
      file = { from: git[1], to: git[2], status: 'modified', notes: [], hunks: [] };
      hunk = undefined;
      segments.push({ type: 'file', file });
    } else if (!file) {
      segments.push({ type: 'text', line });
    } else if (line.startsWith('@@')) {
      hunk = { header: line, lines: [] };
      file.hunks.push(hunk);
    } else if (hunk && /^[ +-]/.test(line)) {
      hunk.lines.push({ kind: line[0] === '+' ? 'add' : line[0] === '-' ? 'del' : 'context', text: line.slice(1) });
    } else if (line.startsWith('new file')) {
      file.status = 'added';
    } else if (line.startsWith('deleted file')) {
      file.status = 'deleted';
    } else if (line.startsWith('rename from')) {
      file.status = 'renamed';
    } else if (/^(Binary files|\\ No newline)/.test(line)) {
      file.notes.push(line);
    }
  }
  return segments;
}

function tokenize(lines: string[], lang: string): ThemedToken[][] {
  const code = lines.join('\n');
  try {
    return codeToTokens(code, { lang: lang as never, theme }).tokens;
  } catch {
    return lines.map((content) => [{ content, offset: 0 }]);
  }
}

function paint(tokens: ThemedToken[], line: Line, width: number): string {
  const { sign, color, bg } = style[line.kind];
  let out = `${bg}${fg(color)}${sign} `;
  let used = 2;
  for (const token of tokens) {
    const content = token.content.replaceAll('\t', '    ');
    const italic = (token.fontStyle ?? 0) & 1 ? '\x1b[3m' : '\x1b[23m';
    out += `${fg(token.color ?? palette.text)}${italic}${content}`;
    used += Bun.stringWidth(content);
  }
  return `${out}${'\u00a0'.repeat(Math.max(0, width - used))}${reset}`;
}

function render(file: FilePatch, width: number): string[] {
  const rule = `${fg(palette.dim)}${'─'.repeat(width)}${reset}`;
  const path = file.from === file.to ? file.to : `${file.from} → ${file.to}`;
  const out = [rule, `${fg(palette.blue)}\x1b[1m${file.status}: ${path}${reset}`, rule];
  const lang = langFor(file.to);
  for (const hunk of file.hunks) {
    const before = tokenize(hunk.lines.filter((l) => l.kind !== 'add').map((l) => l.text), lang);
    const after = tokenize(hunk.lines.filter((l) => l.kind !== 'del').map((l) => l.text), lang);
    let [b, a] = [0, 0];
    out.push(`${fg(palette.dim)}${hunk.header}${reset}`);
    for (const line of hunk.lines) {
      const tokens = line.kind === 'del' ? before[b] : after[a];
      if (line.kind !== 'add') b++;
      if (line.kind !== 'del') a++;
      out.push(paint(tokens ?? [], line, width));
    }
  }
  for (const note of file.notes) out.push(`${fg(palette.dim)}${note}${reset}`);
  return out;
}

const widthFlag = process.argv.indexOf('--width');
const width = widthFlag > 0 ? Number(process.argv[widthFlag + 1]) : process.stdout.columns ?? 120;
const output = parse(await Bun.stdin.text()).flatMap((s) => (s.type === 'text' ? [s.line] : render(s.file, width)));
process.stdout.write(output.join('\n') + '\n');
