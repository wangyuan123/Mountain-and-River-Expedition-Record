<template>
  <div v-if="report" class="wap-battle-box" :class="report.win ? 'battle-win-box' : 'battle-loss-box'">
    <div class="battle-title-row">
      <span class="battle-flag" :class="report.win ? 'text-green' : 'text-red'">
        {{ report.win ? '【大捷战报】' : '【失利战报】' }}
      </span>
      <span class="battle-subject">{{ report.subject || '野外阵地战役交战报告' }}</span>
    </div>

    <div class="battle-detail-text">
      <div>⚔️ 进攻方: <strong>{{ report.attackerName || '我军先锋团' }}</strong> <span v-if="report.fromName" class="text-muted">({{ report.fromName }} {{ report.fromCoord }})</span></div>
      <div>🛡️ 防守方: <strong>{{ report.toName || '未知敌军部属' }}</strong> <span v-if="report.toCoord" class="text-muted">({{ report.toCoord }})</span></div>
      <div>⏱️ 战役时间: <span class="text-muted">{{ formatTime(report.time) }}</span></div>
      <div v-if="report.prestigeChange || report.exp">
        🎖️ 战果结算:
        <span v-if="report.prestigeChange" :class="report.prestigeChange >= 0 ? 'text-green' : 'text-red'">
          军望{{ report.prestigeChange >= 0 ? '+' : '' }}{{ report.prestigeChange }}
        </span>
        <span v-if="report.exp" class="text-gold"> 将领经验+{{ report.exp }}</span>
      </div>
    </div>

    <!-- 展开战况日志 -->
    <div v-if="report.roundLogs && report.roundLogs.length" class="battle-log-toggle">
      <span class="wap-link" @click="showLogs = !showLogs">
        {{ showLogs ? '▼ 收起交战回合日志' : '▶ 展开交战回合日志 (' + report.roundLogs.length + '回合)' }}
      </span>
      <div v-if="showLogs" class="battle-logs-content">
        <div v-for="(line, idx) in report.roundLogs" :key="idx" class="log-line">
          {{ line }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  report: {
    type: Object,
    default: null
  }
})

const showLogs = ref(false)

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.wap-battle-box {
  margin: 10px 0;
  padding: 8px 10px;
  background-color: #fbfcfe;
  border: 1px solid #d3dfe9;
  border-left: 4px solid #0b396b;
  font-size: 12.5px;
  line-height: 1.6;
}

.battle-win-box {
  border-left-color: #27ae60;
  background-color: #f9fdfa;
}

.battle-loss-box {
  border-left-color: #cc0000;
  background-color: #fdfaf9;
}

.battle-title-row {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: bold;
}

.battle-subject {
  color: #0b396b;
}

.battle-detail-text {
  margin-bottom: 6px;
  color: #333333;
}

.battle-log-toggle {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #e0e0e0;
}

.battle-logs-content {
  margin-top: 6px;
  padding: 6px 8px;
  background-color: #f1f3f5;
  border: 1px solid #dee2e6;
  max-height: 200px;
  overflow-y: auto;
  font-family: monospace;
  font-size: 11.5px;
  line-height: 1.5;
  color: #495057;
}

.log-line {
  margin-bottom: 2px;
}
</style>
