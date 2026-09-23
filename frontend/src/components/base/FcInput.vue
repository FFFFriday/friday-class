<script setup>
import { computed, useId } from 'vue'

const props = defineProps({
  label: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  type: { type: String, default: 'text' },
  /** 填了就当多行文本框用 */
  rows: { type: Number, default: 0 },
  maxlength: { type: [Number, String], default: undefined },
  /** 错误提示。填了就显示红框并把提示挂在下方。 */
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  /** 输入框右侧的固定后缀，如「字」 */
  suffix: { type: String, default: '' },
})

const model = defineModel({ type: [String, Number], default: '' })

// useId 是 Vue 3.5 内置的：让 label 的 for 与 input 的 id 对上。
// 手写常量 id 在有多个实例的页面上会重复，点 label 会聚焦到错误的输入框。
const inputId = useId()
const errorId = computed(() => `${inputId}-error`)

const isMultiline = computed(() => props.rows > 0)
</script>

<template>
  <div class="fc-field" :class="{ 'fc-field--disabled': disabled }">
    <label v-if="label" class="fc-field__label" :for="inputId">{{ label }}</label>

    <div class="fc-field__control" :class="{ 'fc-field__control--error': error }">
      <textarea
        v-if="isMultiline"
        :id="inputId"
        v-model="model"
        class="fc-field__input fc-field__input--area"
        :rows="rows"
        :placeholder="placeholder"
        :maxlength="maxlength"
        :disabled="disabled"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="error ? errorId : undefined"
      />
      <input
        v-else
        :id="inputId"
        v-model="model"
        class="fc-field__input"
        :type="type"
        :placeholder="placeholder"
        :maxlength="maxlength"
        :disabled="disabled"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="error ? errorId : undefined"
      />
      <span v-if="suffix" class="fc-field__suffix">{{ suffix }}</span>
    </div>

    <p v-if="error" :id="errorId" class="fc-field__error">{{ error }}</p>
    <p v-else-if="hint" class="fc-field__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.fc-field {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-1);
}

.fc-field__label {
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-medium);
  color: var(--fc-text-muted);
}

.fc-field__control {
  display: flex;
  align-items: center;
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  background: var(--fc-bg-panel);
  transition: border-color var(--fc-transition), box-shadow var(--fc-transition);
}

.fc-field__control:focus-within {
  border-color: var(--fc-primary);
  box-shadow: 0 0 0 3px var(--fc-primary-tint);
}

.fc-field__control--error {
  border-color: var(--fc-danger);
}
.fc-field__control--error:focus-within {
  box-shadow: 0 0 0 3px var(--fc-danger-tint);
}

.fc-field__input {
  flex: 1;
  min-width: 0;
  height: 34px;
  padding: 0 var(--fc-space-3);
  border: none;
  outline: none;
  background: transparent;
  font-size: var(--fc-font);
}

.fc-field__input--area {
  height: auto;
  padding: var(--fc-space-2) var(--fc-space-3);
  resize: vertical;
  line-height: 1.6;
}

.fc-field__input::placeholder {
  color: var(--fc-text-faint);
}

.fc-field__suffix {
  flex-shrink: 0;
  padding-right: var(--fc-space-3);
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.fc-field--disabled .fc-field__control {
  background: var(--fc-bg-muted);
}

.fc-field__error {
  margin-bottom: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-danger);
}

.fc-field__hint {
  margin-bottom: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}
</style>
