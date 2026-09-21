import DOMPurify from 'dompurify'
import { marked } from 'marked'

// GFM gives us tables / strikethrough / fenced code; breaks turns single newlines into <br> so
// streamed chat text keeps its line structure the way users expect.
marked.setOptions({ gfm: true, breaks: true })

/**
 * Renders assistant Markdown to sanitized HTML for `v-html`.
 *
 * <p>Parsing happens on the frontend (not the backend) so the SSE token stream stays raw and
 * incremental; the browser re-renders the accumulated content on each token. DOMPurify runs last so
 * anything the model emits that looks like raw HTML/scripts is stripped before touching the DOM.</p>
 */
export function renderMarkdown(source: string): string {
  if (!source) {
    return ''
  }
  const raw = marked.parse(source, { async: false }) as string
  return DOMPurify.sanitize(raw)
}
