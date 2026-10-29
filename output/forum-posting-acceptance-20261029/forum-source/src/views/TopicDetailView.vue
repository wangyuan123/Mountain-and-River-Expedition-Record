<template>
  <div v-loading="loading" class="wap-detail-page">
    <div v-if="loadError" role="alert">{{ loadError }} <button class="wap-btn" @click="loadDetail()">重试</button></div>
    <div v-if="topicDetail">
      <!-- 面包屑导航 -->
      <div class="wap-breadcrumb">
        <router-link to="/" class="wap-link">首页</router-link>
        <span class="crumb-sep">></span>
        <router-link :to="`/board/${topic.boardKey}`" class="wap-link">{{ topic.boardName }}</router-link>
        <span class="crumb-sep">></span>
        <span>正文</span>
      </div>

      <!-- 帖子标题与标签 -->
      <div class="topic-header-block">
        <div class="topic-title-text">
          <span v-if="topic.isPinned" class="text-red">[置顶]</span>
          <span v-if="topic.isEssence" class="text-gold">[精选]</span>
          <span v-if="topic.topicType === 'BOUNTY'" class="text-gold">[<img src="/img/gold.webp" class="gold-icon-img" alt="悬赏" />悬赏{{ topic.bountyGold }}金]</span>
          <span v-if="topic.topicType === 'BATTLE'" class="text-green">[战报复盘]</span>
          <span v-if="topic.isLocked" class="text-muted">[锁定]</span>
          {{ topic.title }}
        </div>

        <div class="topic-meta-line text-muted">
          <span>长官: <strong>{{ topic.authorDisplayName || topic.authorName }}</strong></span>
          <MilitaryBadge
            :vip-level="topic.authorVipLevel"
            :vip-badge-visible="topic.authorVipBadgeVisible"
            :rank-tier="topic.authorMilitaryRank"
            :rank-name="topic.authorMilitaryRankName"
            :guild-name="topic.authorGuildName"
          />
          <span>{{ formatTime(topic.createdAt) }}</span>
          <span>阅:{{ topic.viewCount }}</span>

          <!-- 版务操作链接 -->
          <span v-if="canModerate" class="mod-links">
            <span v-if="authStore.isAdmin" class="wap-link" @click="handleModPin">
              [{{ topic.isPinned ? '取消置顶' : '置顶' }}]
            </span>
            <span v-if="authStore.isAdmin" class="wap-link" @click="handleModEssence">
              [{{ topic.isEssence ? '取消精' : '设精' }}]
            </span>
            <span v-if="authStore.isAdmin" class="wap-link" @click="handleModLock">
              [{{ topic.isLocked ? '解锁' : '锁定' }}]
            </span>
            <span class="wap-link text-red" @click="handleModDelete">[删除]</span>
          </span>
        </div>
      </div>

      <!-- 悬赏状态说明行 -->
      <div v-if="topic.topicType === 'BOUNTY'" class="bounty-strip">
        <img src="/img/gold.webp" class="gold-icon-img" alt="黄金" /> <strong>悬赏任务: {{ topic.bountyGold }} 黄金</strong>
        <span :class="topic.bountyStatus === 'SOLVED' ? 'text-green' : 'text-gold'" style="margin-left: 6px;">
          [{{ topic.bountyStatus === 'SOLVED' ? '已结案结算' : '待指点迷津' }}]
        </span>
        <div class="text-muted" style="font-size: 11.5px; margin-top: 2px;">
          {{ topic.bountyStatus === 'SOLVED' ? '楼主已采纳最佳战术，赏金已划入解答者主城。' : '提供有效解法，经楼主采纳后赏金将直接划转至您的主城金库！' }}
        </div>
      </div>

      <!-- 帖子正文内容 -->
      <div class="topic-article-body" v-html="parsedContent"></div>

      <!-- 内嵌战报卡片 -->
      <BattleReportCard v-if="topicDetail.battleReport" :report="topicDetail.battleReport" />

      <!-- 互动动作条 -->
      <div class="topic-action-strip">
        <span class="wap-link" @click="handleLikeTopic">
          {{ isLikedByMe ? '❤️已致敬' : '🤍致敬点赞' }}({{ topic.likeCount }})
        </span>
        <span class="sep">·</span>
        <span class="wap-link text-gold" @click="tipModalVisible = true">
          <img src="/img/gold.webp" class="gold-icon-img" alt="犒劳" />犒劳三军({{ topic.tipCount }})
        </span>
        <span class="sep">·</span>
        <span class="wap-link" @click="scrollToEditor">
          💬回复研讨
        </span>
        <span class="sep">·</span>
        <router-link :to="`/board/${topic.boardKey}`" class="wap-link">
          返回板块
        </router-link>
      </div>

      <!-- 赞助名录行 -->
      <div v-if="recentTips.length > 0" class="tip-wall-box">
        <div style="font-weight: bold; color: #b8860b; margin-bottom: 2px;">
          🎖️ 前线赞助犒劳 (累计 {{ topic.tipTotalGold }} 黄金):
        </div>
        <div class="tip-wall-items">
          <span v-for="t in recentTips" :key="t.id" class="tip-item-span">
            [{{ t.senderName }} +{{ t.amountGold }}金<span v-if="t.message"> “{{ t.message }}”</span>]
          </span>
        </div>
      </div>

      <div class="wap-divider-solid"></div>

      <!-- 回复列表 -->
      <div class="wap-replies-area">
        <div class="wap-section-title">
          【参谋部研讨回复 (共 {{ topic.replyCount }} 条)】
        </div>

        <div v-if="replies.length === 0" class="wap-empty-text">
          暂无作战研讨回复，欢迎长官率先发表真知灼见！
        </div>

        <div
          v-for="r in replies"
          :key="r.id"
          :id="`reply-${r.id}`"
          class="wap-reply-line"
          :class="{ 'bounty-accepted-reply': r.isAcceptedBounty }"
        >
          <div class="reply-meta-row">
            <span class="reply-floor-num">#{{ r.floorNumber }}楼</span>
            <span class="reply-author-name"><strong>{{ r.authorDisplayName || r.authorName }}</strong></span>
            <MilitaryBadge
              :vip-level="r.authorVipLevel"
              :vip-badge-visible="r.authorVipBadgeVisible"
              :rank-tier="r.authorMilitaryRank"
              :rank-name="r.authorMilitaryRankName"
              :guild-name="r.authorGuildName"
            />
            <span class="text-muted">{{ formatTime(r.createdAt) }}</span>

            <span v-if="r.isAcceptedBounty" class="text-green" style="font-weight: bold;">
              🏆[已采纳良策]
            </span>

            <span class="reply-action-links">
              <span class="wap-link" @click="handleLikeReply(r)">
                {{ r.isLikedByMe ? '❤️' : '🤍' }}({{ r.likeCount || 0 }})
              </span>
              <span class="wap-link" @click="handleQuote(r)">[引用]</span>
              <span
                v-if="canAcceptBounty && !r.isAcceptedBounty && r.authorId !== authStore.user?.id"
                class="wap-link text-gold"
                @click="handleAcceptBounty(r.id)"
              >
                🏆[采纳赏金]
              </span>
            </span>
          </div>

          <!-- 引用片段 -->
          <div v-if="r.quoteReply" class="quote-snippet-box">
            引用 #{{ r.quoteReply.floorNumber }} @{{ r.quoteReply.authorName }}: {{ r.quoteReply.snippet }}
          </div>

          <!-- 回复正文 -->
          <div class="reply-content-text" v-html="parseCoordText(r.content)"></div>
        </div>

        <!-- 回复分页 -->
        <div class="wap-pagination-bar" v-if="repliesTotalPages > 1">
          <span
            v-if="repliesCurrentPage > 0"
            class="wap-link page-btn"
            @click="handleRepliesPageChange(repliesCurrentPage)"
          >
            [上一页]
          </span>
          <span class="page-indicator">第 {{ repliesCurrentPage + 1 }}/{{ repliesTotalPages }} 页</span>
          <span
            v-if="repliesCurrentPage < repliesTotalPages - 1"
            class="wap-link page-btn"
            @click="handleRepliesPageChange(repliesCurrentPage + 2)"
          >
            [下一页]
          </span>
        </div>

        <!-- 快速回复输入框 -->
        <div class="wap-reply-editor" id="reply-editor-box" v-if="!topic.isLocked && authStore.isLoggedIn && authStore.user?.canReply">
          <div class="editor-title-row">
            <strong>【发表战务回复】</strong>
            <span v-if="quoteTarget" class="quoting-hint text-gold">
              正在引用 #{{ quoteTarget.floorNumber }} @{{ quoteTarget.authorDisplayName || quoteTarget.authorName }}
              <span class="wap-link text-muted" @click="quoteTarget = null">[取消引用]</span>
            </span>
          </div>

          <textarea
            v-model="replyContent"
            rows="3"
            class="wap-reply-textarea"
            placeholder="严整军风，文明研讨... 支持 [coord:x,y] 战地坐标"
            maxlength="5000"
          ></textarea>

          <div style="margin-top: 6px;">
            <button class="wap-submit-btn" :disabled="replySubmitting" @click="submitReply">
              {{ replySubmitting ? '呈送中...' : '提交回复' }}
            </button>
          </div>
        </div>

        <div v-else-if="topic.isLocked" class="text-muted">
          🔒 该战备文书已被锁定，暂不可提交新回复。
        </div>
        <div v-else-if="!authStore.isLoggedIn" class="text-muted">请先登录游戏账号后回复。</div>
        <div v-else class="text-muted">声望达到 10 后可回复，当前声望 {{ authStore.prestige }}。</div>
      </div>
    </div>

    <!-- 打赏弹窗 -->
    <TipModal
      v-if="topicDetail"
      v-model="tipModalVisible"
      :topic-id="topic.id"
      :author-name="topic.authorDisplayName || topic.authorName"
      @tipped="onTipped"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { forumApi } from '../api/forum'
import { useAuthStore } from '../stores/auth'
import MilitaryBadge from '../components/MilitaryBadge.vue'
import BattleReportCard from '../components/BattleReportCard.vue'
import TipModal from '../components/TipModal.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const loadError = ref('')
let detailRequest = 0
let repliesRequest = 0
const topicDetail = ref(null)
const replies = ref([])
const repliesCurrentPage = ref(0)
const repliesPageSize = ref(20)
const repliesTotalElements = ref(0)
const repliesTotalPages = ref(1)

const replyContent = ref('')
const quoteTarget = ref(null)
const replySubmitting = ref(false)
const tipModalVisible = ref(false)

const topic = computed(() => topicDetail.value?.topic || {})
const isLikedByMe = computed(() => !!topicDetail.value?.isLikedByMe)
const recentTips = computed(() => topicDetail.value?.recentTips || [])

const canModerate = computed(() => {
  if (!authStore.isLoggedIn) return false
  if (authStore.isAdmin) return true
  return authStore.user?.id === topic.value.authorId
})

const canAcceptBounty = computed(() => {
  if (!authStore.isLoggedIn) return false
  return topic.value.topicType === 'BOUNTY' &&
         topic.value.bountyStatus === 'OPEN' &&
         authStore.user?.id === topic.value.authorId
})

const parsedContent = computed(() => {
  if (!topicDetail.value?.content) return ''
  return parseCoordText(topicDetail.value.content)
})

function parseCoordText(text) {
  if (!text) return ''
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br/>')

  return escaped.replace(/\[coord:(\d+),(\d+)\]/g, (match, x, y) => {
    return `<span class="coord-tag" title="滇缅地图战区坐标">📍坐标(${x},${y})</span>`
  })
}

/** 同组件切帖和翻页时，较早返回的请求不能覆盖当前帖子或页码。 */
async function loadDetail(page = 0) {
  const request = ++detailRequest
  const topicId = route.params.id
  loading.value = true
  loadError.value = ''
  try {
    const detail = await forumApi.getTopicDetail(topicId)
    if (request !== detailRequest || topicId !== route.params.id) return
    topicDetail.value = detail
    await loadReplies(page)
  } catch (e) {
    if (request !== detailRequest || topicId !== route.params.id) return
    topicDetail.value = null
    loadError.value = e.response?.data?.error || '帖子加载失败，请重试'
  } finally {
    if (request === detailRequest) loading.value = false
  }
}

async function loadReplies(page = 0) {
  const request = ++repliesRequest
  const topicId = route.params.id
  try {
    const res = await forumApi.getReplies(topicId, { page, size: repliesPageSize.value })
    if (request !== repliesRequest || topicId !== route.params.id) return
    repliesCurrentPage.value = page
    replies.value = res.content || []
    repliesTotalElements.value = res.totalElements || 0
    repliesTotalPages.value = res.totalPages || 1
  } catch (e) {}
}

function handleRepliesPageChange(p) {
  loadReplies(p - 1)
}

async function handleLikeTopic() {
  if (!authStore.isLoggedIn) {
    ElMessage.warning('请先登录游戏账号')
    return
  }
  try {
    const res = await forumApi.toggleLike('TOPIC', topic.value.id)
    topicDetail.value.isLikedByMe = res.liked
    topicDetail.value.topic.likeCount = res.count
  } catch (e) {}
}

async function handleLikeReply(r) {
  if (!authStore.isLoggedIn) {
    ElMessage.warning('请先登录游戏账号')
    return
  }
  try {
    const res = await forumApi.toggleLike('REPLY', r.id)
    r.isLikedByMe = res.liked
    r.likeCount = res.count
  } catch (e) {}
}

function handleQuote(r) {
  quoteTarget.value = r
  scrollToEditor()
}

function scrollToEditor() {
  const el = document.getElementById('reply-editor-box')
  if (el) el.scrollIntoView({ behavior: 'smooth' })
}

async function handleAcceptBounty(replyId) {
  try {
    await ElMessageBox.confirm('确定采纳该回复作为最佳良策？悬赏黄金将立即转账至该玩家账户。', '采纳提示', {
      type: 'warning',
      confirmButtonText: '确定采纳',
      cancelButtonText: '取消'
    })
    const res = await forumApi.acceptBounty(topic.value.id, replyId)
    if (res.success) {
      ElMessage.success('已成功采纳良策并划转赏金！')
      await loadDetail()
    }
  } catch (e) {}
}

async function handleModPin() {
  await forumApi.moderateTopic(topic.value.id, { action: topic.value.isPinned ? 'UNPIN' : 'PIN' })
  await loadDetail()
}
async function handleModEssence() {
  await forumApi.moderateTopic(topic.value.id, { action: topic.value.isEssence ? 'UNESSENCE' : 'ESSENCE' })
  await loadDetail()
}
async function handleModLock() {
  await forumApi.moderateTopic(topic.value.id, { action: topic.value.isLocked ? 'UNLOCK' : 'LOCK' })
  await loadDetail()
}
async function handleModDelete() {
  try {
    await ElMessageBox.confirm('确定要彻底删除该战备话题吗？', '删除确认', { type: 'error' })
    await forumApi.moderateTopic(topic.value.id, { action: 'DELETE' })
    ElMessage.success('文书已删除')
    router.push('/')
  } catch (e) {}
}

async function submitReply() {
  if (replySubmitting.value) return
  if (!authStore.isLoggedIn) {
    ElMessage.warning('请先登录游戏账号')
    return
  }
  if (!replyContent.value.trim()) {
    ElMessage.warning('请输入回帖内容')
    return
  }
  if (!authStore.user?.canReply) {
    ElMessage.warning('声望达到 10 后可回复')
    return
  }
  const topicId = topic.value.id
  replySubmitting.value = true
  try {
    const reply = await forumApi.createReply(topicId, {
      content: replyContent.value.trim(),
      quoteReplyId: quoteTarget.value?.id || null
    })
    if (String(topicId) !== String(route.params.id)) return
    ElMessage.success('回复提交成功！')
    replyContent.value = ''
    quoteTarget.value = null
    // 使用服务端楼层定位提交结果，超过一页时也能立即看到自己的回复。
    await loadDetail(Math.floor((reply.floorNumber - 1) / repliesPageSize.value))
    await nextTick()
    document.getElementById(`reply-${reply.id}`)?.scrollIntoView({ behavior: 'smooth' })
  } catch (e) {
  } finally {
    replySubmitting.value = false
  }
}

function onTipped() {
  loadDetail()
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

watch(() => route.params.id, () => {
  topicDetail.value = null
  replies.value = []
  replyContent.value = ''
  quoteTarget.value = null
  ++repliesRequest
  loadDetail()
}, { immediate: true })
</script>

<style scoped>
.wap-detail-page {
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

.topic-header-block {
  padding: 6px 4px 8px;
  border-bottom: 1px solid #dce2e8;
  margin-bottom: 8px;
}

.topic-title-text {
  font-size: 15px;
  font-weight: bold;
  color: #0b396b;
  line-height: 1.4;
  margin-bottom: 6px;
}

.topic-meta-line {
  font-size: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.mod-links {
  margin-left: auto;
  display: inline-flex;
  gap: 4px;
}

/* 悬赏条 */
.bounty-strip {
  background-color: #fffdf5;
  border: 1px solid #faecc8;
  padding: 6px 8px;
  margin-bottom: 8px;
  font-size: 12.5px;
}

/* 文章正文 */
.topic-article-body {
  font-size: 13.5px;
  line-height: 1.8;
  color: #212529;
  padding: 6px 4px 12px;
  word-break: break-word;
}

:deep(.coord-tag) {
  display: inline-block;
  background: #fff8e1;
  border: 1px solid #d4af37;
  color: #b8860b;
  font-size: 11.5px;
  padding: 0 4px;
  font-weight: bold;
  margin: 0 2px;
}

/* 交互动作条 */
.topic-action-strip {
  padding: 8px 4px;
  margin: 6px 0;
  border-top: 1px dashed #dcdcdc;
  border-bottom: 1px dashed #dcdcdc;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.sep {
  color: #999;
}

/* 赞助名录 */
.tip-wall-box {
  background-color: #fafbfc;
  padding: 6px 8px;
  margin: 6px 0 10px;
  font-size: 12px;
  border: 1px solid #edf0f2;
}

.tip-wall-items {
  line-height: 1.6;
}

.tip-item-span {
  margin-right: 6px;
  color: #495057;
}

/* 回复列表 */
.wap-replies-area {
  margin-top: 8px;
}

.wap-reply-line {
  padding: 8px 4px;
  border-bottom: 1px solid #edf0f2;
  font-size: 13px;
  line-height: 1.6;
}

.bounty-accepted-reply {
  background-color: #f7fdf9;
  border-left: 3px solid #27ae60;
  padding-left: 6px;
}

.reply-meta-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.reply-floor-num {
  color: #777;
  font-size: 11px;
}

.reply-action-links {
  margin-left: auto;
  display: flex;
  gap: 6px;
}

.quote-snippet-box {
  background-color: #f6f8fa;
  border-left: 2px solid #0b396b;
  padding: 3px 6px;
  margin: 4px 0 6px;
  font-size: 11.5px;
  color: #666;
}

.reply-content-text {
  color: #333;
}

/* 回帖编辑框 */
.wap-reply-editor {
  margin-top: 14px;
  padding: 8px;
  background-color: #f9fbfd;
  border: 1px solid #dce2e8;
}

.editor-title-row {
  font-size: 12.5px;
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.wap-reply-textarea {
  width: 100%;
  padding: 6px;
  font-size: 13px;
  border: 1px solid #b0bec5;
  border-radius: 2px;
  box-sizing: border-box;
  font-family: inherit;
  resize: vertical;
}

.wap-submit-btn {
  padding: 4px 14px;
  background-color: #0b396b;
  color: white;
  border: 1px solid #08294e;
  border-radius: 2px;
  font-size: 12.5px;
  cursor: pointer;
}
.wap-submit-btn:disabled {
  opacity: 0.6;
}
</style>
