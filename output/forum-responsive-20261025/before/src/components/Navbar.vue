<template>
  <header class="wap-header">
    <!-- 顶部状态栏: 10002 📬家信(0) 🏡家园 ⭐️空间 🧭超Q >> -->
    <div class="wap-top-status-bar">
      <span class="status-id">🏷️{{ authStore.user?.id || '10002' }}</span>
      <span class="status-item">📬信件(0)</span>
      <a href="http://localhost:8081" class="status-item">🏡主城</a>
      <router-link to="/board/battle_hall" class="status-item">🎖️战报</router-link>
      <span class="status-item">🧭军务 >></span>
    </div>

    <!-- 深蓝主导航栏: 家园 好友 家族 广场 游戏 | 山河远征录- 【1区】军政论坛 -->
    <div class="wap-blue-banner">
      <div class="banner-left-links">
        <router-link to="/" class="banner-link">论坛</router-link>
        <router-link to="/board/strategy" class="banner-link">参谋</router-link>
        <router-link to="/board/tavern" class="banner-link">茶馆</router-link>
        <router-link to="/board/legion" class="banner-link">军团</router-link>
        <a v-if="!isEmbedded" href="http://localhost:8081" class="banner-link highlight-game">游戏</a>
      </div>
      <div class="banner-right-title">
        山河远征录- 【1区】军政论坛
      </div>
    </div>

    <!-- 快捷功能文本链接栏: 聊天 邮箱 军情 任务 好友 首页 -->
    <div class="wap-sub-links-bar">
      <router-link to="/" class="wap-link">首页</router-link>
      <router-link to="/board/strategy" class="wap-link">参谋本部</router-link>
      <router-link to="/board/tavern" class="wap-link">战地茶馆</router-link>
      <router-link to="/board/battle_hall" class="wap-link">战功榜</router-link>
      <router-link to="/new-topic" class="wap-link text-red">✍️起草文书</router-link>
      <a href="http://localhost:8081" class="wap-link">🎮返回游戏</a>
    </div>

    <!-- 玩家实时军备与身份状态行 -->
    <div class="wap-user-status-strip">
      <template v-if="authStore.isLoggedIn">
        <span>长官: <strong>{{ authStore.displayName }}</strong></span>
        <span>🎖️{{ authStore.militaryRankName || '列兵' }}</span>
        <span v-if="authStore.guildName">[{{ authStore.guildName }}]</span>
        <span><img src="/img/gold.webp" class="gold-icon-img" alt="黄金" />黄金: <strong class="text-gold">{{ authStore.gold }}</strong></span>
        <span class="wap-link text-muted" @click="handleLogout">[退出]</span>
      </template>
      <template v-else>
        <span>长官尚未入列识别</span>
        <span class="wap-link" @click="loginDialogVisible = true">[点击登录/入伍]</span>
      </template>
    </div>

    <div class="wap-divider-solid"></div>

    <!-- 登录弹窗 (经典简洁风格) -->
    <el-dialog
      v-model="loginDialogVisible"
      title="二战指挥官身份登录"
      width="90%"
      style="max-width: 360px;"
      align-center
    >
      <div class="wap-login-box">
        <p style="margin: 0 0 10px; font-size: 12px; color: #666;">
          请输入《山河远征录》游戏账号直接通行：
        </p>
        <div style="margin-bottom: 8px;">
          <span style="font-size: 12px;">用户名:</span>
          <el-input v-model="loginForm.username" size="small" placeholder="游戏账号用户名" />
        </div>
        <div style="margin-bottom: 12px;">
          <span style="font-size: 12px;">密码:</span>
          <el-input v-model="loginForm.password" type="password" size="small" placeholder="密码" show-password />
        </div>
      </div>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px;">
          <el-button size="small" @click="loginDialogVisible = false">取消</el-button>
          <el-button type="primary" size="small" :loading="loginLoading" @click="handleLoginSubmit">
            验证入列
          </el-button>
        </div>
      </template>
    </el-dialog>
  </header>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()
// 嵌入游戏聊天页时隐藏返回游戏入口，避免在 iframe 中重复打开游戏。
const isEmbedded = window.self !== window.top

const loginDialogVisible = ref(false)
const loginLoading = ref(false)
const loginForm = ref({
  username: '',
  password: ''
})

async function handleLoginSubmit() {
  if (!loginForm.value.username || !loginForm.value.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loginLoading.value = true
  try {
    const ok = await authStore.login(loginForm.value.username, loginForm.value.password)
    if (ok) {
      ElMessage.success('欢迎归队，长官！')
      loginDialogVisible.value = false
    }
  } catch (err) {
  } finally {
    loginLoading.value = false
  }
}

function handleLogout() {
  authStore.logout()
  ElMessage.info('已退出登录')
}
</script>

<style scoped>
.wap-header {
  width: 100%;
}

/* 第一层: 10002 📬家信(0) 🏡家园 ⭐️空间 🧭超Q >> */
.wap-top-status-bar {
  background: #f7f9fc;
  padding: 3px 8px;
  font-size: 12px;
  color: #333333;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #dce2e8;
  overflow-x: auto;
  white-space: nowrap;
}

.status-id {
  color: #0b396b;
  font-weight: bold;
}

.status-item {
  color: #0044cc;
  text-decoration: none;
  cursor: pointer;
}
.status-item:hover {
  text-decoration: underline;
}

/* 第二层: 深蓝导航条 */
.wap-blue-banner {
  background-color: #0b396b;
  color: #ffffff;
  padding: 5px 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  flex-wrap: wrap;
  gap: 6px;
}

.banner-left-links {
  display: flex;
  gap: 12px;
}

.banner-link {
  color: #ffffff;
  text-decoration: none;
  font-weight: 500;
}

.banner-link:hover, .banner-link.router-link-active {
  color: #ffeb3b;
  text-decoration: underline;
}

.highlight-game {
  color: #ffeaa7;
  font-weight: bold;
}

.banner-right-title {
  color: #ffffff;
  font-weight: bold;
  font-size: 13px;
  letter-spacing: 0.5px;
}

/* 第三层: 快捷操作文本行 */
.wap-sub-links-bar {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  gap: 14px;
  padding: 6px 8px;
  background-color: #ffffff;
  font-size: 12.5px;
  overflow-x: auto;
  white-space: nowrap;
}

/* 第四层: 用户状态行 */
.wap-user-status-strip {
  padding: 4px 8px;
  background-color: #fcfdfe;
  font-size: 12px;
  color: #495057;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
</style>
