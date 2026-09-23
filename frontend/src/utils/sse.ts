/**
 * Minimal SSE wire-format helpers shared by fetch-based stream consumers.
 *
 * The chat stream keeps its own parser for now; new consumers (notification stream) use these
 * utilities instead of duplicating the block-splitting logic again.
 */

export interface SseBlock {
  event: string
  data: string
}

/** Splits a raw SSE byte-buffer chunk into complete blocks; returns the unfinished tail. */
export function parseSseBlocks(buffer: string): { blocks: SseBlock[]; rest: string } {
  const blocks: SseBlock[] = []
  const parts = buffer.split(/\r?\n\r?\n/)
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of part.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
      // Comment lines (": ping" heartbeats) fall through and are ignored.
    }
    if (dataLines.length > 0 || event !== 'message') {
      blocks.push({ event, data: dataLines.join('\n') })
    }
  }
  return { blocks, rest }
}

export function safeJsonParse(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/**
 * Reads one SSE response body to completion, dispatching each block to onEvent. Throws on network
 * errors so the caller can reconnect.
 */
export async function readSseStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (block: SseBlock) => void
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { blocks, rest } = parseSseBlocks(buffer)
      buffer = rest
      for (const block of blocks) onEvent(block)
    }
    if (buffer.trim()) {
      const { blocks } = parseSseBlocks(`${buffer}\n\n`)
      for (const block of blocks) onEvent(block)
    }
  } finally {
    reader.releaseLock()
  }
}
