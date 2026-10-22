<script setup lang="ts">
/**
 * 代码编辑器（CodeMirror 6）。
 *
 * 面向 OJ 做题场景：语法高亮、行号、括号自动补全与配对高亮、自动缩进、
 * Tab 缩进、撤销重做、搜索替换，外观贴近力扣的浅色编辑区。
 *
 * 组件只负责编辑体验，不执行代码；输入内容通过 v-model 交给调用方提交。
 */
import { basicSetup } from 'codemirror'
import { Compartment, EditorState, Prec } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { indentWithTab } from '@codemirror/commands'
import { HighlightStyle, indentUnit, syntaxHighlighting, type LanguageSupport } from '@codemirror/language'
import { cpp } from '@codemirror/lang-cpp'
import { go } from '@codemirror/lang-go'
import { java } from '@codemirror/lang-java'
import { javascript } from '@codemirror/lang-javascript'
import { python } from '@codemirror/lang-python'
import { rust } from '@codemirror/lang-rust'
import { tags } from '@lezer/highlight'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { AssessmentLanguage } from '@/api/types'

const props = withDefaults(
  defineProps<{
    modelValue: string
    language: AssessmentLanguage
    readonly?: boolean
    /** 编辑区高度，默认 420px。 */
    height?: string
  }>(),
  { readonly: false, height: '420px' },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const host = ref<HTMLDivElement | null>(null)
const languageConf = new Compartment()
const tabConf = new Compartment()
const editableConf = new Compartment()

const LANGUAGE_EXTENSIONS: Record<AssessmentLanguage, () => LanguageSupport> = {
  cpp: () => cpp(),
  java: () => java(),
  python: () => python(),
  javascript: () => javascript(),
  go: () => go(),
  rust: () => rust(),
}

const INDENT_SIZE: Record<AssessmentLanguage, number> = {
  cpp: 4,
  java: 4,
  python: 4,
  javascript: 2,
  go: 4,
  rust: 4,
}

const highlightStyle = HighlightStyle.define([
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: '#8a94a6', fontStyle: 'italic' },
  { tag: [tags.keyword, tags.modifier, tags.controlKeyword, tags.moduleKeyword], color: '#a626a4' },
  { tag: [tags.string, tags.special(tags.string), tags.regexp], color: '#3f8f4f' },
  { tag: [tags.number, tags.bool, tags.null, tags.atom], color: '#b76b01' },
  { tag: [tags.typeName, tags.className, tags.namespace, tags.tagName], color: '#b8860b' },
  { tag: [tags.function(tags.variableName), tags.function(tags.propertyName)], color: '#2563eb' },
  { tag: [tags.definition(tags.variableName), tags.propertyName], color: '#1f2937' },
  { tag: [tags.operator, tags.punctuation, tags.bracket], color: '#4b5563' },
  { tag: tags.invalid, color: '#dc4c4c' },
])

const lightTheme = EditorView.theme(
  {
    '&': {
      height: '100%',
      color: '#1f2937',
      backgroundColor: '#ffffff',
      fontSize: '13.5px',
    },
    '&.cm-focused': { outline: 'none' },
    '.cm-scroller': {
      fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, SFMono-Regular, Consolas, monospace",
      lineHeight: '1.7',
    },
    '.cm-content': { padding: '10px 0', caretColor: '#2563eb' },
    '.cm-line': { padding: '0 12px' },
    '.cm-gutters': {
      backgroundColor: '#f8fafc',
      color: '#9aa4b2',
      border: 'none',
      borderRight: '1px solid #eff3f8',
      paddingLeft: '4px',
    },
    '.cm-activeLineGutter': { backgroundColor: '#eef4ff', color: '#2563eb' },
    '.cm-activeLine': { backgroundColor: 'rgba(37, 99, 235, 0.05)' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#2563eb', borderLeftWidth: '2px' },
    '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection': {
      backgroundColor: 'rgba(37, 99, 235, 0.16)',
    },
    '.cm-matchingBracket, &.cm-focused .cm-matchingBracket': {
      backgroundColor: 'rgba(37, 99, 235, 0.14)',
      outline: '1px solid rgba(37, 99, 235, 0.45)',
      color: 'inherit',
    },
    '.cm-nonmatchingBracket': { color: '#dc4c4c' },
    '.cm-foldPlaceholder': {
      backgroundColor: '#eef4ff',
      border: '1px solid #d7e4fe',
      color: '#2563eb',
      padding: '0 4px',
    },
    '.cm-tooltip': { border: '1px solid #dce4ee', backgroundColor: '#ffffff' },
    '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
      backgroundColor: '#eef4ff',
      color: '#1d4ed8',
    },
    '.cm-panels': { backgroundColor: '#f8fafc', color: '#1f2937' },
    '.cm-searchMatch': { backgroundColor: 'rgba(230, 106, 76, 0.24)' },
    '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: 'rgba(230, 106, 76, 0.44)' },
  },
  { dark: false },
)

let view: EditorView | null = null

function languageExtension(language: AssessmentLanguage): LanguageSupport {
  return (LANGUAGE_EXTENSIONS[language] ?? LANGUAGE_EXTENSIONS.python)()
}

function readonlyExtension(readonly: boolean) {
  return [EditorView.editable.of(!readonly), EditorState.readOnly.of(readonly)]
}

function createExtensions() {
  return [
    basicSetup,
    Prec.highest(keymap.of([indentWithTab])),
    languageConf.of(languageExtension(props.language)),
    tabConf.of(indentUnit.of(' '.repeat(INDENT_SIZE[props.language] ?? 4))),
    syntaxHighlighting(highlightStyle),
    lightTheme,
    EditorView.contentAttributes.of({ 'aria-label': '代码编辑器', spellcheck: 'false' }),
    editableConf.of(readonlyExtension(props.readonly)),
    EditorView.updateListener.of((update) => {
      if (!update.docChanged) return
      const value = update.state.doc.toString()
      emit('update:modelValue', value)
      emit('change', value)
    }),
  ]
}

onMounted(() => {
  if (!host.value) return
  view = new EditorView({
    state: EditorState.create({ doc: props.modelValue, extensions: createExtensions() }),
    parent: host.value,
  })
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

watch(
  () => props.modelValue,
  (value) => {
    if (!view) return
    const current = view.state.doc.toString()
    if (current === value) return
    view.dispatch({ changes: { from: 0, to: current.length, insert: value } })
  },
)

watch(
  () => props.language,
  (language) => {
    view?.dispatch({
      effects: [
        languageConf.reconfigure(languageExtension(language)),
        tabConf.reconfigure(indentUnit.of(' '.repeat(INDENT_SIZE[language] ?? 4))),
      ],
    })
  },
)

watch(
  () => props.readonly,
  (readonly) => {
    view?.dispatch({ effects: editableConf.reconfigure(readonlyExtension(readonly)) })
  },
)

defineExpose({
  focus: () => view?.focus(),
})
</script>

<template>
  <div ref="host" class="code-editor" :style="{ height }" data-test="code-editor" />
</template>

<style scoped>
.code-editor {
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: 0 0 var(--app-control-radius) var(--app-control-radius);
  background: #ffffff;
  transition: border-color var(--app-transition);
}

.code-editor:focus-within {
  border-color: var(--app-brand);
}
</style>
