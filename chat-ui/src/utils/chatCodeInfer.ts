/** 常见 fence 别名 → highlight.js 语言 id */
const LANG_ALIASES: Record<string, string> = {
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  js: 'javascript',
  ts: 'typescript',
  py: 'python',
  rs: 'rust',
  kt: 'kotlin',
  cs: 'csharp',
  cpp: 'cpp',
  'c++': 'cpp',
  cxx: 'cpp',
  h: 'c',
  hpp: 'cpp',
  ps1: 'powershell',
  ps: 'powershell',
  md: 'markdown',
  docker: 'dockerfile',
  tf: 'plaintext',
  vue: 'html',
  jsx: 'javascript',
  tsx: 'typescript',
}

export function normalizeFenceLang(raw: string | undefined | null): string {
  const s = (raw || '').trim().toLowerCase()
  if (!s) return ''
  const base = s.split(/[/\s]+/)[0] ?? s
  return LANG_ALIASES[base] ?? base
}

/** 无 fence 或 plaintext 时按内容粗判（用于左上角展示） */
export function inferLangFromCode(text: string, hlLang: string): string {
  const t = text.trimStart()
  const fromHl = normalizeFenceLang(hlLang)
  if (fromHl && fromHl !== 'plaintext') return fromHl
  if (/^\s*[\[{]/.test(t) && /"[\w-]+"\s*:/.test(t)) return 'json'
  if (/^\s*[\w-]+\s*:\s*.+$/m.test(t) && /^\s*[\w-]+\s*:/m.test(t) && t.includes(':')) {
    if (/apiVersion|kind:\s*Deployment|metadata:/m.test(t)) return 'yaml'
    if (/^---\s*$/m.test(t)) return 'yaml'
  }
  if (/^\s*(def |class |import |from \w+ import|print\()/m.test(t)) return 'python'
  if (/^\s*(package |import java\.|public class |void main\()/m.test(t)) return 'java'
  if (/^\s*(#include|int main\s*\(|namespace \w+)/m.test(t)) return 'cpp'
  if (/^\s*(fn |let mut |impl <|use std::)/m.test(t)) return 'rust'
  if (/^\s*(func |package main|:= range )/m.test(t)) return 'go'
  if (/^\s*(SELECT |INSERT INTO|CREATE TABLE)/i.test(t)) return 'sql'
  if (/^\s*<\?xml/i.test(t)) return 'xml'
  if (/^\s*<!DOCTYPE html|<html[\s>]/i.test(t)) return 'html'
  if (/^\s*#\!/.test(t) || /^\s*(echo |export |if \[)/m.test(t)) return 'bash'
  return 'plaintext'
}
