<script setup>
defineProps({
  /**
   * 列定义：{ key, title, width?, align? }
   * 不填 key 的列只能靠插槽渲染（如「操作」列）。
   */
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  /** 行主键字段，用于 :key。默认 id。 */
  rowKey: { type: String, default: 'id' },
  loading: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
})
</script>

<template>
  <div class="fc-table-wrap">
    <table class="fc-table">
      <thead>
        <tr>
          <th
            v-for="col in columns"
            :key="col.key || col.title"
            :style="{ width: col.width, textAlign: col.align || 'left' }"
          >
            {{ col.title }}
          </th>
        </tr>
      </thead>

      <tbody>
        <tr v-if="loading">
          <td :colspan="columns.length" class="fc-table__placeholder">加载中…</td>
        </tr>

        <tr v-else-if="!rows.length">
          <td :colspan="columns.length" class="fc-table__placeholder">
            <slot name="empty">{{ emptyText }}</slot>
          </td>
        </tr>

        <tr v-for="(row, index) in rows" v-else :key="row[rowKey] ?? index">
          <td
            v-for="col in columns"
            :key="col.key || col.title"
            :style="{ textAlign: col.align || 'left' }"
          >
            <!-- 每列都能用 #cell-<key> 覆写成任意内容；不覆写就直出字段值 -->
            <slot :name="`cell-${col.key}`" :row="row" :index="index">
              {{ col.key ? row?.[col.key] : '' }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.fc-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.fc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fc-font-sm);
  /* 表格里的数字（人数、页数、时间、ID）用等宽数字：
     默认的比例数字，「1」比「8」窄，同列上下对不齐，看着毛毛躁躁。
     只影响数字字形，对中文与字母没有任何副作用。 */
  font-variant-numeric: tabular-nums;
}

.fc-table th {
  padding: var(--fc-space-3) var(--fc-space-4);
  background: var(--fc-bg-muted);
  border-bottom: 1px solid var(--fc-border);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text-muted);
  white-space: nowrap;
  text-align: left;
}

.fc-table td {
  padding: var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
  color: var(--fc-text);
  vertical-align: middle;
}

.fc-table tbody tr:last-child td {
  border-bottom: none;
}

.fc-table tbody tr:hover td {
  background: var(--fc-primary-bg-weak);
}

.fc-table__placeholder {
  padding: var(--fc-space-8) var(--fc-space-4);
  text-align: center;
  color: var(--fc-text-faint);
}
</style>
