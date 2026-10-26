<template>
  <div class="wap-container">
    <Navbar />
    <main class="wap-main">
      <router-view />
    </main>
    <footer class="wap-footer">
      <div class="wap-divider-solid"></div>
      <div class="wap-footer-links">
        <span class="wap-link" @click="$router.push('/')">论坛首页</span> ·
        <span class="wap-link" @click="reloadPage">刷新</span> ·
        <a href="http://localhost:8081" class="wap-link">返回游戏</a> ·
        <span class="wap-link" @click="scrollToTop">回到顶部</span>
      </div>
      <div class="wap-footer-shortcuts">
        军事 资源 地图 军团 排行 背包 商城 宝物
      </div>
      <div class="wap-footer-sub">
        山河远征录 - 【1区】玩家战术交流所 (WAP文字版)
      </div>
    </footer>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import Navbar from './components/Navbar.vue'
import { useAuthStore } from './stores/auth'

const authStore = useAuthStore()

function reloadPage() {
  window.location.reload()
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(async () => {
  authStore.initFromUrl()
  if (authStore.token) {
    await authStore.fetchProfile()
  }
})
</script>

<style scoped>
.wap-container {
  display: flex;
  flex-direction: column;
}

.wap-main {
  flex: 1;
  padding: 0 8px 20px;
}

.wap-footer {
  padding: 10px 8px 24px;
  text-align: center;
  font-size: 12px;
  color: #666;
  background-color: #fafbfc;
}

.wap-footer-links {
  margin: 6px 0;
  font-size: 13px;
}

.wap-footer-shortcuts {
  margin: 6px 0;
  color: #0b396b;
  letter-spacing: 2px;
  font-size: 12px;
}

.wap-footer-sub {
  margin-top: 6px;
  color: #999;
  font-size: 11px;
}
</style>
