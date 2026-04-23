<template>
  <!-- 双层圆角方框「复制」描边图标，与点赞等工具栏风格一致 -->
  <svg class="copy-layers" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <!-- 后层：右上 -->
    <rect x="9" y="3" width="11" height="11" rx="2.25" ry="2.25" stroke="currentColor" stroke-width="1.5" />
    <!-- 前层：左下 -->
    <rect x="3" y="9" width="11" height="11" rx="2.25" ry="2.25" stroke="currentColor" stroke-width="1.5" />
  </svg>
</template>

<style scoped>
.copy-layers {
  width: 1em;
  height: 1em;
  display: block;
  color: currentColor;
}
</style>
