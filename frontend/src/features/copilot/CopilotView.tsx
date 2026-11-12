'use client'

import type { Entry } from './entry'
import { CopilotWorkspace } from './CopilotWorkspace'

/** D-Copilot / M-Copilot (`/copilot`): conversa efêmera até a primeira resposta guardada (ADR-0041 §1). */
export function CopilotView({ entry }: { entry: Entry | null }) {
  return <CopilotWorkspace entry={entry} />
}
