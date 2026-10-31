import { codeToTokens, isSupportedLanguage } from '@pierre/highlights';
import { githubLightHighContrast as theme } from '@pierre/highlights/themes';

const aliases: Record<string, string> = { fnl: 'clojure', bb: 'clojure', fish: 'bash', kbd: 'lisp', sexp: 'scheme', guile: 'scheme', scm: 'scheme' };

(globalThis as any).highlightCode = (code: string, lang: string) => {
  const name = aliases[lang] ?? lang;
  if (!isSupportedLanguage(name)) return null;
  return codeToTokens(code, { lang: name as never, theme }).tokens.map((line) =>
    line.map(({ content, color, fontStyle }) => ({ content, color, italic: Boolean((fontStyle ?? 0) & 1) })),
  );
};
