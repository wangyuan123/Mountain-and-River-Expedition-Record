<template>
  <div class="wap-new-topic-page">
    <div class="wap-breadcrumb">
      <router-link to="/" class="wap-link">首页</router-link>
      <span class="crumb-sep">></span>
      <span>起草前线战略文书</span>
    </div>

    <div class="wap-section-title">
      【起草前线战略文书】
    </div>

    <div class="wap-form-container">
      <div class="form-row">
        <span class="form-label">长官资产:</span>
        <span>随军黄金: <img src="/img/gold.webp" class="gold-icon-img" alt="黄金" /><strong class="text-gold">{{ authStore.gold }}</strong></span>
      </div>

      <!-- 选择投递板块 -->
      <div class="form-row">
        <span class="form-label">投递板块:</span>
        <select v-model="form.boardId" class="wap-select">
          <option v-for="b in boards" :key="b.id" :value="b.id">
            {{ b.name }}
          </option>
        </select>
      </div>

      <!-- 文书性质 -->
      <div class="form-row">
        <span class="form-label">文书性质:</span>
        <label class="radio-label">
          <input type="radio" v-model="form.topicType" value="NORMAL" /> 研讨
        </label>
        <label class="radio-label">
          <input type="radio" v-model="form.topicType" value="BOUNTY" /> <img src="/img/gold.webp" class="gold-icon-img" alt="悬赏" />战术悬赏
        </label>
        <label class="radio-label">
          <input type="radio" v-model="form.topicType" value="BATTLE" /> 战报复盘
        </label>
      </div>

      <!-- 悬赏黄金设置 -->
      <div v-if="form.topicType === 'BOUNTY'" class="form-row bounty-subrow">
        <span class="form-label">悬赏金额:</span>
        <input
          v-model.number="form.bountyGold"
          type="number"
          min="50"
          step="50"
          class="wap-num-input"
        /> <img src="/img/gold.webp" class="gold-icon-img" alt="黄金" />黄金
        <div class="text-muted" style="margin-top: 2px;">
          (起草发布时暂时冻结扣除黄金，采纳解答者后结算划转)
        </div>
      </div>

      <!-- 关联战报 -->
      <div v-if="form.topicType === 'BATTLE'" class="form-row battle-subrow">
        <span class="form-label">关联战报:</span>
        <select v-model="form.battleReportId" class="wap-select">
          <option :value="null">-- 从我的战报库中选择 --</option>
          <option v-for="r in myReports" :key="r.id" :value="r.id">
            {{ getReportOptionLabel(r) }}
          </option>
        </select>
        <div class="text-muted" style="margin-top: 2px;">
          (正文中将自动内嵌该战役交战纪要、伤亡与回合战况)
        </div>
      </div>

      <!-- 标题 -->
      <div class="form-row">
        <span class="form-label">文书标题:</span>
        <input
          v-model="form.title"
          type="text"
          class="wap-text-input"
          placeholder="如：【战术探讨】怒江渡口配兵防守心得"
          maxlength="100"
        />
      </div>

      <!-- 坐标辅助 -->
      <div class="form-row" style="margin-bottom: 2px;">
        <span class="wap-link text-gold" @click="promptInsertCoord">
          📍[插入战地坐标]
        </span>
        <span class="text-muted" style="font-size: 11.5px; margin-left: 6px;">
          (支持在正文中使用 [coord:X,Y] 标记战区坐标)
        </span>
      </div>

      <!-- 内容正文 -->
      <div class="form-row">
        <textarea
          v-model="form.content"
          rows="10"
          class="wap-textarea"
          placeholder="请详述作战构想、将领搭配、兵力损耗或研讨疑问..."
          maxlength="10000"
        ></textarea>
      </div>

      <!-- 操作按钮 -->
      <div class="form-actions-row">
        <button class="wap-btn primary-btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? '呈送中...' : '确认呈送文书' }}
        </button>
        <button class="wap-btn" @click="$router.back()">返回</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { forumApi } from '../api/forum'
import { useAuthStore } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const boards = ref([])
const myReports = ref([])
const submitting = ref(false)

const form = ref({
  boardId: null,
  topicType: 'NORMAL',
  bountyGold: 100,
  battleReportId: null,
  title: '',
  content: ''
})

async function loadBoards() {
  try {
    boards.value = await forumApi.getBoards()
    if (route.query.boardId) {
      form.value.boardId = Number(route.query.boardId)
    } else if (boards.value.length > 0) {
      form.value.boardId = boards.value[0].id
    }
  } catch (e) {}
}

async function loadMyReports() {
  if (!authStore.isLoggedIn) return
  try {
    const list = await forumApi.getMyReports()
    myReports.value = (list || []).filter((r) => r.type === 'battle')
    if (route.query.share_report_id) {
      const shareId = Number(route.query.share_report_id)
      form.value.battleReportId = shareId
      form.value.topicType = 'BATTLE'
      form.value.title = `【战报复盘】战役纪要 #${shareId}`
    }
  } catch (e) {}
}

function getReportOptionLabel(r) {
  let sub = r.subject || ((r.toName || r.targetName) ? `目标: ${r.toName || r.targetName}` : '野外战役')
  try {
    if (r.data) {
      const parsed = typeof r.data === 'string' ? JSON.parse(r.data) : r.data
      if (parsed.subject) sub = parsed.subject
    }
  } catch (e) {}
  // 游戏战报列表以 time 返回发生时间，保留对旧版 createdAt 的兼容。
  const d = new Date(r.time ?? r.createdAt)
  const timeStr = `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return `[${timeStr}] ${sub} (ID:${r.id})`
}

async function promptInsertCoord() {
  try {
    const { value } = await ElMessageBox.prompt('请输入坐标 (格式如: 120,85)', '插入战地坐标', {
      confirmButtonText: '插入',
      cancelButtonText: '取消',
      inputPattern: /^\d+\s*,\s*\d+$/,
      inputErrorMessage: '格式必须为 数字,数字 例如 120,85'
    })
    const cleaned = value.replace(/\s+/g, '')
    form.value.content += ` [coord:${cleaned}] `
  } catch (e) {}
}

async function handleSubmit() {
  if (!authStore.isLoggedIn) {
    ElMessage.warning('请先登录游戏账号')
    return
  }
  if (!form.value.boardId) {
    ElMessage.warning('请选择发布的板块')
    return
  }
  if (!form.value.title.trim()) {
    ElMessage.warning('请输入标题')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请输入正文内容')
    return
  }

  if (form.value.topicType === 'BOUNTY') {
    if (authStore.gold < form.value.bountyGold) {
      ElMessage.error(`您的黄金储备不足，当前拥有 ${authStore.gold} 黄金`)
      return
    }
  }

  submitting.value = true
  try {
    const res = await forumApi.createTopic({
      boardId: form.value.boardId,
      title: form.value.title.trim(),
      content: form.value.content.trim(),
      topicType: form.value.topicType,
      bountyGold: form.value.topicType === 'BOUNTY' ? form.value.bountyGold : 0,
      battleReportId: form.value.topicType === 'BATTLE' ? form.value.battleReportId : null
    })
    ElMessage.success('战务文书已呈送发布！')
    await authStore.fetchProfile()
    router.push(`/topic/${res.id}`)
  } catch (e) {
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (route.query.type) {
    form.value.topicType = route.query.type
  }
  await loadBoards()
  await loadMyReports()
})
</script>

<style scoped>
.wap-new-topic-page {
  padding-top: 4px;
}

.wap-breadcrumb {
  font-size: 12px;
  color: #666;
  margin: 6px 0 8px;
}

.crumb-sep {
  margin: 0 4px;
  color: #999;
}

.wap-form-container {
  padding: 8px 4px;
}

.form-row {
  margin-bottom: 8px;
  font-size: 13px;
}

.form-label {
  font-weight: 500;
  margin-right: 6px;
  display: inline-block;
  min-width: 65px;
}

.wap-select {
  padding: 3px 6px;
  border: 1px solid #b0bec5;
  border-radius: 2px;
  font-size: 13px;
  background-color: #fff;
  max-width: 100%;
}

.radio-label {
  margin-right: 10px;
  cursor: pointer;
}

.wap-num-input {
  width: 90px;
  padding: 3px 6px;
  border: 1px solid #b0bec5;
  border-radius: 2px;
  font-size: 13px;
}

.wap-text-input {
  width: calc(100% - 80px);
  padding: 4px 6px;
  border: 1px solid #b0bec5;
  border-radius: 2px;
  font-size: 13px;
  box-sizing: border-box;
}

.wap-textarea {
  width: 100%;
  padding: 6px;
  border: 1px solid #b0bec5;
  border-radius: 2px;
  font-size: 13px;
  box-sizing: border-box;
  font-family: inherit;
  resize: vertical;
}

.form-actions-row {
  margin-top: 10px;
  display: flex;
  gap: 10px;
}

.wap-btn {
  padding: 4px 14px;
  border: 1px solid #b0bec5;
  border-radius: 2px;
  font-size: 13px;
  background-color: #f8f9fa;
  cursor: pointer;
}

.primary-btn {
  background-color: #0b396b;
  border-color: #08294e;
  color: white;
}
</style>
