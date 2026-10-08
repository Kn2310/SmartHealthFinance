'use client'

import { FileText, Upload, X } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { ACCEPT, formatFileSize } from './file-check'
import styles from './Import.module.css'

interface Props {
  file: File | null
  onFile: (file: File | null) => void
  disabled?: boolean
}

/**
 * Área de envio (design D-FirstData / D-AddAccount). O controle real é o `<input type="file">` nativo,
 * acessível por teclado e leitor de tela; arrastar e soltar é só um atalho para quem usa mouse.
 */
export function Dropzone({ file, onFile, disabled = false }: Props) {
  const inputId = useId()
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)

  function onDrop(event: React.DragEvent) {
    event.preventDefault()
    setDragging(false)
    if (!disabled) onFile(event.dataTransfer.files[0] ?? null)
  }

  if (file) {
    return (
      <div className={styles.fileCard}>
        <span className={styles.fileIcon} aria-hidden="true">
          <FileText size={20} strokeWidth={1.75} />
        </span>
        <span className={styles.fileText}>
          <span className={styles.fileName}>{file.name}</span>
          <span className={styles.fileMeta}>{formatFileSize(file.size)}</span>
        </span>
        <button
          type="button"
          className={styles.iconButton}
          onClick={() => {
            onFile(null)
            if (input.current) input.current.value = ''
          }}
          disabled={disabled}
          aria-label={`Remover ${file.name}`}
        >
          <X size={18} strokeWidth={1.75} aria-hidden="true" />
        </button>
      </div>
    )
  }

  return (
    <div
      className={`${styles.dropzone} ${dragging ? styles.dragging : ''}`}
      onDragOver={(event) => {
        event.preventDefault()
        if (!disabled) setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
    >
      <span className={styles.dropIcon} aria-hidden="true">
        <Upload size={22} strokeWidth={1.75} />
      </span>
      <p className={styles.dropTitle}>Arraste o arquivo OFX ou CSV aqui</p>
      <p className={styles.dropOr}>ou</p>
      <input
        ref={input}
        id={inputId}
        type="file"
        accept={ACCEPT}
        className={`sr-only ${styles.fileInput}`}
        disabled={disabled}
        onChange={(event) => onFile(event.target.files?.[0] ?? null)}
        aria-describedby={`${inputId}-formats`}
      />
      <label htmlFor={inputId} className={styles.pickButton}>
        Selecionar arquivo
      </label>
      <p id={`${inputId}-formats`} className={styles.dropHint}>
        Formatos aceitos: .ofx e .csv exportados do seu banco, até 10 MB. O CSV precisa das colunas data, descrição e
        valor —{' '}
        <a href="/modelo-extrato.csv" download className={styles.link}>
          baixar modelo CSV
        </a>
        .
      </p>
    </div>
  )
}
