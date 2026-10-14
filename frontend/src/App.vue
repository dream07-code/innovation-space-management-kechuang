<script setup lang="ts">
/**
 * 根组件。
 *
 * 布局由路由决定：认证页面（登录、改密、阻断）是独立页面，
 * 业务页面统一走 DefaultLayout。
 */
import { RouterView } from 'vue-router'
</script>

<template>
  <RouterView />
</template>
