<template>
  <!-- 与 index.html 中 /favicon.svg 一致 -->
  <img
    class="app-favicon-img"
    :class="{ sm: small }"
    src="/favicon.svg"
    alt=""
    width="22"
    height="22"
    decoding="async"
  />
</template>

<script setup lang="ts">
defineProps<{
  small?: boolean
}>()
</script>

<style scoped>
.app-favicon-img {
  display: block;
  width: 22px;
  height: 22px;
  object-fit: contain;
}
.app-favicon-img.sm {
  width: 20px;
  height: 20px;
}
</style>
