import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import test from 'node:test'
import { ref, computed } from 'vue'
import { parse } from '@vue/compiler-sfc'

/** 执行真实页面的 setup 处理函数，以可控请求时序核验翻页、提交和草稿。 */
async function page(filename, overrides = {}) {
  const source = await readFile(new URL(`../src/views/${filename}.vue`, import.meta.url), 'utf8')
  const watchers = [], calls = [], messages = []
  const route = { params: { id: '1' }, query: {} }
  const auth = { isLoggedIn: true, user: { id: 1, canReply: true }, gold: 1000, fetchProfile: async () => {} }
  const api = {
    getTopicDetail: async id => ({ topic: { id: Number(id), replyCount: 21 } }),
    getReplies: async (id, params) => { calls.push({ id, ...params }); return { content: [{ id: 21 }], totalElements: 21, totalPages: 2 } },
    createReply: async () => ({ id: 21, floorNumber: 21 }),
    getBoards: async () => [{ id: 1 }], getMyReports: async () => [],
    createTopic: async () => ({ id: 2 }),
    ...overrides
  }
  const context = vm.createContext({
    ref, computed, nextTick: async () => {},
    watch: (getter, handler, options) => { watchers.push({ getter, handler, options }) },
    onMounted() {}, useRoute: () => route, useRouter: () => ({ push() {} }), useAuthStore: () => auth,
    forumApi: api, ElMessage: Object.fromEntries(['warning', 'error', 'success'].map(key => [key, message => messages.push(message)])),
    ElMessageBox: {}, document: { getElementById: () => ({ scrollIntoView() {} }) }
  })
  const names = filename === 'TopicDetailView'
    ? 'loadDetail,loadReplies,submitReply,replyContent,quoteTarget,replySubmitting,topicDetail,replies,repliesCurrentPage,parseCoordText'
    : 'handleSubmit,form,submitting,loadMyReports,myReports'
  const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*$/gm, '')
  vm.runInContext(`${script}\n globalThis.result = {${names}}`, context)
  return { ...context.result, route, auth, api, watchers, calls, messages }
}

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

test('第21楼提交后定位第二页，保留服务端保存的回复', async () => {
  const p = await page('TopicDetailView')
  p.topicDetail.value = { topic: { id: 1 } }; p.replyContent.value = '我的第21楼'
  await p.submitReply()
  assert.equal(p.calls.at(-1).page, 1)
  assert.equal(p.repliesCurrentPage.value, 1)
  assert.equal(p.replyContent.value, '')
})

test('回复提交失败保留正文与引用，连点只产生一次请求', async () => {
  const pending = deferred(); let count = 0
  const p = await page('TopicDetailView', { createReply: () => { count++; return pending.promise } })
  p.topicDetail.value = { topic: { id: 1 } }; p.replyContent.value = '保留草稿'; p.quoteTarget.value = { id: 3 }
  const first = p.submitReply(); await p.submitReply(); assert.equal(count, 1)
  pending.reject(new Error('网络中断')); await first
  assert.equal(p.replyContent.value, '保留草稿'); assert.equal(p.quoteTarget.value.id, 3)
  assert.equal(p.replySubmitting.value, false)
})

test('快速翻页以最后请求的页码为准，较旧响应不覆盖', async () => {
  const pending = [deferred(), deferred()]; let i = 0
  const p = await page('TopicDetailView', { getReplies: () => pending[i++].promise })
  const old = p.loadReplies(0), latest = p.loadReplies(1)
  pending[1].resolve({ content: [{ id: 21 }], totalPages: 2 }); await latest
  pending[0].resolve({ content: [{ id: 1 }], totalPages: 2 }); await old
  assert.equal(p.repliesCurrentPage.value, 1); assert.equal(p.replies.value[0].id, 21)
})

test('切换帖子时旧提交不清空新帖草稿', async () => {
  const pending = deferred()
  const p = await page('TopicDetailView', { createReply: () => pending.promise })
  p.topicDetail.value = { topic: { id: 1 } }; p.replyContent.value = '旧帖回复'
  const first = p.submitReply(); p.route.params.id = '2'; p.replyContent.value = '新帖草稿'
  pending.resolve({ id: 21, floorNumber: 21 }); await first
  assert.equal(p.replyContent.value, '新帖草稿'); assert.equal(p.calls.length, 0)
  assert.equal(p.watchers.length, 1)
})

test('渲染用户输入时保留换行与坐标，HTML文本经过转义', async () => {
  const p = await page('TopicDetailView')
  const html = p.parseCoordText('<img src=x onerror=alert(1)>\n[coord:5,387] &')
  assert.match(html, /&lt;img/); assert.doesNotMatch(html, /<img/)
  assert.match(html, /<br\/>/); assert.match(html, /坐标\(5,387\)/); assert.match(html, /&amp;/)
})

test('悬赏金额拒绝负数与小数，合法发布连点只提交一次', async () => {
  let count = 0; const pending = deferred()
  const p = await page('NewTopicView', { createTopic: () => { count++; return pending.promise } })
  Object.assign(p.form.value, { boardId: 1, title: '标题', content: '正文', topicType: 'BOUNTY', bountyGold: -1 })
  await p.handleSubmit(); p.form.value.bountyGold = 1.5; await p.handleSubmit(); assert.equal(count, 0)
  p.form.value.bountyGold = 100
  const first = p.handleSubmit(); await p.handleSubmit(); assert.equal(count, 1)
  pending.resolve({ id: 2 }); await first; assert.equal(p.submitting.value, false)
})

test('免密登录晚于挂载完成时仍能获取个人战报', async () => {
  let count = 0
  const p = await page('NewTopicView', { getMyReports: async () => { count++; return [{ id: 1, type: 'battle' }] } })
  assert.equal(p.watchers.length, 1)
  await p.watchers[0].handler(true)
  assert.equal(count, 1); assert.equal(p.myReports.value[0].id, 1)
})
